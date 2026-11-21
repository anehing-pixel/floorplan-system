package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 账号加解密（AES-GCM）。
 *
 * <p><b>为什么用 GCM 而不是 CBC</b>：GCM 带认证标签，能发现密文被篡改；
 * CBC 不带认证，密文被改动后解密会静默产出垃圾明文——对账号这种"改一位就打到别人账户"的场景，
 * 静默失败是不可接受的。</p>
 *
 * <p><b>密文格式</b>：{@code base64(iv || ciphertext || tag)}。
 * IV 每次随机（12 字节，GCM 推荐长度），因此同一账号两次加密结果不同——
 * 这正好防止"密文相同即账号相同"的关联分析。</p>
 *
 * <p><b>密钥来源</b>：配置注入（环境变量），<b>不入库不入代码</b>——
 * 与既有 {@code PAYMENT_WEBHOOK_SECRET} 同一纪律。</p>
 */
@Component
public class AccountCryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public AccountCryptoService(@Value("${floorplan.accounts.encryption-key:dev-account-key-change-me-32bytes}") String key) {
        // AES-256 需要 32 字节密钥；不足则用 SHA-256 派生，保证任何配置长度都能工作。
        byte[] material = key.getBytes(StandardCharsets.UTF_8);
        if (material.length != 32) {
            try {
                material = java.security.MessageDigest.getInstance("SHA-256").digest(material);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用，无法派生账号加密密钥", e);
            }
        }
        this.keySpec = new SecretKeySpec(material, "AES");
    }

    /**
     * 加密账号明文。
     *
     * @param plain 明文账号
     * @return base64 密文
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYS_001, "账号加密失败：" + e.getMessage());
        }
    }

    /**
     * 解密账号密文。
     *
     * <p><b>注意</b>：解密出的明文<b>禁止进入日志与审计</b>，
     * 仅用于回显给授权操作员核对。</p>
     *
     * @param cipherText base64 密文
     * @return 明文账号
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYS_001, "账号解密失败（密文可能已被篡改或密钥已变更）");
        }
    }

    /**
     * 生成账号掩码（对外只返这个）。
     *
     * <p>规则：保留前 4 位与后 4 位，中间以 {@code ****} 替代。
     * 长度不足 8 位时全部打码。</p>
     *
     * @param accountNo 明文账号
     * @return 掩码文本
     */
    public static String mask(String accountNo) {
        if (accountNo == null) {
            return null;
        }
        String s = accountNo.trim();
        if (s.length() <= 4) {
            return "****";
        }
        if (s.length() <= 8) {
            return s.substring(0, 2) + "****";
        }
        return s.substring(0, 4) + " **** " + s.substring(s.length() - 4);
    }
}
