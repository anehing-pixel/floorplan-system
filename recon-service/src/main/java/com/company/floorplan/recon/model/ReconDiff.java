package com.company.floorplan.recon.model;

import java.time.LocalDateTime;

/**
 * 差异记录：一条"期望与实际对不上"的事实。
 *
 * <p><b>值不可变、状态可变</b>：expectedValue / actualValue / diffType 是判定那一刻的事实，
 * 写库后不再修改——否则"当初为什么判成差异"就不可考了。
 * 只有 status 会随人工复核推进（OPEN → CONFIRMED / IGNORED / RESOLVED），
 * 对拍作业自身只负责写 OPEN。</p>
 *
 * <p>{@link #pending} 是纯工厂：不碰数据库、不生成时间戳，
 * 供规则（纯函数）构造"待认领"的差异；批次号与检测时间由应用服务在落库前补齐。
 * 这样规则的无参判定结果可以直接在单测里断言。</p>
 */
public class ReconDiff {

    /** 主键（落库后由数据库生成）。 */
    private Long id;

    /** 所属批次号。 */
    private String batchNo;

    /** 命中的规则编号：R1..R8。 */
    private String ruleCode;

    /** 对拍对象：VIN / 额度 ID。 */
    private String subject;

    /** 期望值（整数日元或计数）。 */
    private long expectedValue;

    /** 实际值（整数日元或计数）。 */
    private long actualValue;

    /** 差异类型：BALANCE_MISMATCH / STATE_MISMATCH / CREDIT_MISMATCH / CASH_MISMATCH。 */
    private String diffType;

    /** 闭环状态：OPEN / CONFIRMED / IGNORED / RESOLVED。 */
    private String status;

    /** 判定说明：规则口径 + 数值含义。 */
    private String detail;

    /** 判定时刻（应用侧生成，非数据库默认）。 */
    private LocalDateTime detectedAt;

    public ReconDiff() {
    }

    /**
     * 规则纯函数使用的工厂：只填判定产生的事实字段，不认领批次、不盖时间戳。
     */
    public static ReconDiff pending(String ruleCode, Comparison c, String diffType, String detail) {
        ReconDiff d = new ReconDiff();
        d.ruleCode = ruleCode;
        d.subject = c.subject();
        d.expectedValue = c.expected();
        d.actualValue = c.actual();
        d.diffType = diffType;
        d.status = "OPEN";
        d.detail = detail == null ? c.note() : detail;
        return d;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public long getExpectedValue() { return expectedValue; }
    public void setExpectedValue(long expectedValue) { this.expectedValue = expectedValue; }

    public long getActualValue() { return actualValue; }
    public void setActualValue(long actualValue) { this.actualValue = actualValue; }

    public String getDiffType() { return diffType; }
    public void setDiffType(String diffType) { this.diffType = diffType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }
}
