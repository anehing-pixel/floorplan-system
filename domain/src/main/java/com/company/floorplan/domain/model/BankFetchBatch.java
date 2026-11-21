package com.company.floorplan.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 银行流水采集批次 —— 每一次拉取一条记录。
 *
 * <p><b>它是运行日志，不是幂等依据。</b>这个区分很关键：</p>
 * <ul>
 *   <li>幂等靠 {@code payment_transactions} 的唯一约束 {@code (account_ref, transaction_id)}；</li>
 *   <li>批次表只回答"这次拉取发生了什么"——拉了几条、新入几条、几条被判重复、耗时多少。</li>
 * </ul>
 *
 * <p><b>为什么不复用 {@code batch_runs} 表</b>：{@code batch_runs} 的主键是
 * {@code (job_name, biz_date)}，语义是"这个日终作业今天跑过没有"，
 * 一天只能有一条。而采集是每 15 分钟一次的高频作业，
 * 同一 {@code biz_date} 要跑几十次——套用 {@code batch_runs} 会被幂等机制直接挡住。</p>
 *
 * <p><b>为什么同账户同一天允许多批次</b>：银行入账是实时的，
 * 上午到一笔、下午到一笔，本来就是两次采集。多批次是常态，
 * 不是重复执行。真正的重复由唯一约束在行级判定。</p>
 */
public class BankFetchBatch {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private Long id;

    /** 批次号：BF-账户-日期-随机6位。每次采集唯一。 */
    private String batchNo;

    private String accountRef;

    /** 本次拉取窗口起（含）。允许重叠——重叠是刻意的，见 {@code lookbackDays}。 */
    private LocalDate valueDateFrom;

    /** 本次拉取窗口止（含）。 */
    private LocalDate valueDateTo;

    /** 来源：BANK_API / BANK_FILE / MANUAL_UPLOAD / DEMO。 */
    private String source;

    private String status;

    /** 银行返回条数。 */
    private int fetchedCount;

    /** 实际新入账条数。 */
    private int insertedCount;

    /** 被唯一约束挡下的条数 —— 健康系统里这个值大于 0 是正常的。 */
    private int duplicatedCount;

    /** 文件 SHA-256 前 32 位；同一文件重发时日志里一眼可见。 */
    private String fileFingerprint;

    private String errorDetail;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /**
     * 开启一个批次。
     *
     * @param accountRef 账户引用码
     * @param from       拉取窗口起（允许与上次重叠）
     * @param to         拉取窗口止
     */
    public static BankFetchBatch start(String accountRef, LocalDate from, LocalDate to, String source) {
        BankFetchBatch b = new BankFetchBatch();
        b.batchNo = "BF-" + accountRef + "-" + to.toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        b.accountRef = accountRef;
        b.valueDateFrom = from;
        b.valueDateTo = to;
        b.source = source;
        b.status = RUNNING;
        b.startedAt = LocalDateTime.now();
        return b;
    }

    /**
     * 标记成功。
     *
     * @param fetched    银行返回条数
     * @param inserted   新入账条数
     * @param duplicated 重复条数
     */
    public void succeed(int fetched, int inserted, int duplicated) {
        this.fetchedCount = fetched;
        this.insertedCount = inserted;
        this.duplicatedCount = duplicated;
        this.status = SUCCESS;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 标记失败。
     *
     * <p>失败时也要落库：没有失败记录就无法回答"这笔钱为什么没进来"。
     * 采集的失败大多是外部原因（银行接口超时、文件格式变了），
     * 而这类问题只有靠批次记录才能发现"连续三天都失败"。</p>
     */
    public void fail(String errorDetail) {
        this.status = FAILED;
        this.errorDetail = truncate(errorDetail, 2000);
        this.finishedAt = LocalDateTime.now();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public void setAccountRef(String accountRef) {
        this.accountRef = accountRef;
    }

    public LocalDate getValueDateFrom() {
        return valueDateFrom;
    }

    public void setValueDateFrom(LocalDate valueDateFrom) {
        this.valueDateFrom = valueDateFrom;
    }

    public LocalDate getValueDateTo() {
        return valueDateTo;
    }

    public void setValueDateTo(LocalDate valueDateTo) {
        this.valueDateTo = valueDateTo;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getFetchedCount() {
        return fetchedCount;
    }

    public void setFetchedCount(int fetchedCount) {
        this.fetchedCount = fetchedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public void setInsertedCount(int insertedCount) {
        this.insertedCount = insertedCount;
    }

    public int getDuplicatedCount() {
        return duplicatedCount;
    }

    public void setDuplicatedCount(int duplicatedCount) {
        this.duplicatedCount = duplicatedCount;
    }

    public String getFileFingerprint() {
        return fileFingerprint;
    }

    public void setFileFingerprint(String fileFingerprint) {
        this.fileFingerprint = fileFingerprint;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
