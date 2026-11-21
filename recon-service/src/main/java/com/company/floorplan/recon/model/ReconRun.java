package com.company.floorplan.recon.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对拍批次：一次"开跑 → 逐规则判定 → 差异落库 → 收尾"的运行记录。
 *
 * <p><b>先落 RUNNING，再干活</b>：进程在半途被杀时，库里会留下一条 RUNNING，
 * 事后能区分"没跑"与"跑了但死了"。这是批次表的全部价值——
 * 若只在结束后 INSERT 一行成功，崩溃就等同于从未发生。</p>
 *
 * <p>失败也落库（FAILED + errorDetail）：连续失败是"账本表结构变了/连接坏了"的证据。</p>
 */
public class ReconRun {

    /** 主键。 */
    private Long id;

    /** 批次号。 */
    private String batchNo;

    /** 对拍业务日。 */
    private LocalDate bizDate;

    /** RUNNING / SUCCEEDED / FAILED。 */
    private String status;

    /** 执行规则条数（占位规则不计入）。 */
    private int ruleCount;

    /** 发现差异条数。 */
    private int diffCount;

    /** 开始时刻。 */
    private LocalDateTime startedAt;

    /** 结束时刻。 */
    private LocalDateTime finishedAt;

    /** 失败原因。 */
    private String errorDetail;

    public ReconRun() {
    }

    /** 新建一条 RUNNING 批次，开始时刻由应用侧生成。 */
    public static ReconRun start(String batchNo, LocalDate bizDate, LocalDateTime startedAt) {
        ReconRun run = new ReconRun();
        run.batchNo = batchNo;
        run.bizDate = bizDate;
        run.status = "RUNNING";
        run.startedAt = startedAt;
        return run;
    }

    /** 正常收尾。 */
    public void succeed(int executedRules, int diffs, LocalDateTime finishedAt) {
        this.status = "SUCCEEDED";
        this.ruleCount = executedRules;
        this.diffCount = diffs;
        this.finishedAt = finishedAt;
    }

    /** 异常收尾：保留已执行的规则数与差异数，便于定位跑了几条才炸。 */
    public void fail(int executedRules, int diffs, LocalDateTime finishedAt, String error) {
        this.status = "FAILED";
        this.ruleCount = executedRules;
        this.diffCount = diffs;
        this.finishedAt = finishedAt;
        this.errorDetail = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRuleCount() { return ruleCount; }
    public void setRuleCount(int ruleCount) { this.ruleCount = ruleCount; }

    public int getDiffCount() { return diffCount; }
    public void setDiffCount(int diffCount) { this.diffCount = diffCount; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
}
