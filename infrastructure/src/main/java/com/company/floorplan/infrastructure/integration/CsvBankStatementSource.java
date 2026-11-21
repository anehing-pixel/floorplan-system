package com.company.floorplan.infrastructure.integration;

import com.company.floorplan.domain.exception.BankStatementException;
import com.company.floorplan.domain.model.BankStatementLine;
import com.company.floorplan.domain.model.BankStatementResult;
import com.company.floorplan.domain.service.BankStatementSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件交换模式的采集适配器 —— 读取网银导出的 CSV。
 *
 * <p><b>为什么默认实现是文件而不是 API</b>：日本中小银行（地方銀行・信用金庫）
 * 绝大多数只提供网银导出或 SFTP 文件推送，银企直连需要签约且多为大企业客户。
 * 按真实可行性排序，文件是第一顺位，API 是加分项。先做能落地的那个。</p>
 *
 * <h3>文件约定</h3>
 * <pre>
 * 目录：floorplan.bank-fetch.csv-dir（默认 ./data/bank）
 * 文件名：{accountRef}-{yyyyMMdd}.csv      例：ACC-001-20261108.csv
 * 列顺序：transaction_id, value_date, amount, payer_name, payer_account, reference
 * 首行可为表头（含 transaction，自动跳过）
 * </pre>
 *
 * <blockquote>
 * 真实银行的 MT940 是定长/带标签格式（:20:、:25:、:61:），比 CSV 复杂得多。
 * 这里选 CSV 是因为<b>格式不是这个类要证明的东西</b>——
 * 要证明的是"采集与匹配解耦、重复拉取由唯一约束兜底"。
 * 换成 MT940 只需替换 {@link #parse}，上层一行不用改。
 * </blockquote>
 */
@Component
@ConditionalOnProperty(name = "floorplan.bank-fetch.source", havingValue = "csv", matchIfMissing = true)
public class CsvBankStatementSource implements BankStatementSource {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path dir;

    public CsvBankStatementSource(@Value("${floorplan.bank-fetch.csv-dir:./data/bank}") String csvDir) {
        this.dir = Paths.get(csvDir);
    }

    @Override
    public String source() {
        return "BANK_FILE";
    }

    @Override
    public BankStatementResult fetch(String accountRef, LocalDate from, LocalDate to) {
        if (!Files.isDirectory(dir)) {
            // 目录不存在 = 配置错误，必须炸出来。
            // 若返回空结果，作业会显示 SUCCESS、0 条，"钱没到账"和"没配目录"就分不清了。
            throw new BankStatementException("BANK_FILE", accountRef,
                    "CSV 目录不存在：" + dir.toAbsolutePath() + "（检查 floorplan.bank-fetch.csv-dir）", null);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .filter(p -> p.getFileName().toString().startsWith(accountRef))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new BankStatementException("BANK_FILE", accountRef, "列举 CSV 目录失败", e);
        }

        if (files.isEmpty()) {
            // 这不是错误：窗口内确实没有导出文件（比如非营业日）。
            // 与"目录不存在"区别对待，正是上面那条 throw 的意义。
            return BankStatementResult.empty(null);
        }

        MessageDigest digest = sha256();
        List<BankStatementLine> lines = new ArrayList<>();
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
                parse(new String(bytes, StandardCharsets.UTF_8), accountRef, from, to, lines, file);
            } catch (IOException e) {
                throw new BankStatementException("BANK_FILE", accountRef, "读取文件失败：" + file.getFileName(), e);
            }
        }
        return new BankStatementResult(lines, hex(digest.digest()).substring(0, 32));
    }

    /**
     * 解析一个 CSV 文件并把窗口内的行追加到 {@code out}。
     *
     * <p>脏行跳过而不是抛异常：一条坏行不该让整批采集失败——
     * 银行文件的格式漂移是常态（多一列、少一个引号、金额带千分位），
     * 若一条坏行炸掉整批，等于每天都可能在等一个永远不会修好的文件。</p>
     */
    private void parse(String content, String accountRef, LocalDate from, LocalDate to,
                       List<BankStatementLine> out, Path file) {
        String[] rawLines = content.split("\n");
        for (String rawLine : rawLines) {
            String line = rawLine.replace("\r", "").replace("\uFEFF", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            // 表头：第一列不是数字/字面量 transaction_id 就跳过
            if (line.toLowerCase().startsWith("transaction_id") || line.startsWith("取引")) {
                continue;
            }
            String[] c = splitCsv(line);
            if (c.length < 4) {
                continue;
            }
            LocalDate valueDate = parseDate(c[1]);
            Long amount = parseAmount(c[2]);
            if (valueDate == null || amount == null) {
                continue;
            }
            if (valueDate.isBefore(from) || valueDate.isAfter(to)) {
                continue;   // 文件里可能有历史行，只取窗口内的
            }
            String txnId = c[0].trim();
            if (txnId.isEmpty()) {
                continue;
            }
            out.add(new BankStatementLine(
                    txnId,
                    accountRef,
                    valueDate,
                    amount,
                    c.length > 3 ? c[3] : null,
                    c.length > 4 ? c[4] : null,
                    c.length > 5 ? c[5] : null,
                    file.getFileName() + "|" + line));
        }
    }

    /**
     * 支持双引号包裹的切分。
     *
     * <p>不能直接用 {@code split(",")}：摘要列里经常出现
     * "振込 1,250,000 JPY" 这种带逗号的文本，简单切分会让后面所有列错位，
     * 而错位后的金额会被解析成摘要的一部分——<b>数据看起来正常，只是全错了一列</b>。</p>
     */
    private static String[] splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString().trim());
        return cells.toArray(new String[0]);
    }

    private static LocalDate parseDate(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v.replace("/", "-"));
        } catch (RuntimeException ignored) {
            // 继续尝试紧凑格式
        }
        try {
            return LocalDate.parse(v, BASIC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Long parseAmount(String s) {
        String v = s == null ? "" : s.replace(",", "").replace("\"", "").trim();
        if (v.isEmpty() || "-".equals(v)) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
