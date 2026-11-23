package com.company.floorplan.application.dto;

import com.company.floorplan.domain.model.LicenseType;

import java.time.LocalDate;

/**
 * 古物商许可登记入参（USED R1，UC-R1-1）。
 *
 * <p>放在 application 层而不是直接用 web 的请求 DTO：用例的输入形状属于应用契约，
 * 换一个入口（HTTP、批量导入、I-11 之外的入站消息）时不该改动用例签名。</p>
 *
 * @param dealerId            车商主键
 * @param licenseType         许可类型；{@code null} 由用例按 FURIGUSHO 处理（当前取值域只有一个）
 * @param licenseNo           許可番号（必填，自然键的一部分）
 * @param competentAuthority  管辖公安委員会（必填，事后核验要找的机关）
 * @param issuedOn            交付日（必填）
 * @param expiresOn           有效期末日；{@code null} 表示無期限，<b>不是</b>未知
 * @param evidencePath        証書扫描件在附件库的 key
 * @param evidenceDigest      扫描件 SHA-256（防替换）
 */
public record LicenseDraft(Long dealerId,
                           LicenseType licenseType,
                           String licenseNo,
                           String competentAuthority,
                           LocalDate issuedOn,
                           LocalDate expiresOn,
                           String evidencePath,
                           String evidenceDigest) {
}
