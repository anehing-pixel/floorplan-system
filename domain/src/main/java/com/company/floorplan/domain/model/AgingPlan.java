package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库龄递减还款计划。
 * rule_version 保证规则变更时存量计划不受影响（规则版本化）。
 */
public class AgingPlan {

    /** 主键。 */
    private Long id;

    /** 车架号（一台车最多一个进行中的递减计划）。 */
    private String vin;

    /**
     * 规则版本号 —— <b>规则版本化</b>的关键字段。
     *
     * <p>业务规则（天数、比例）调整时，存量计划继续沿用旧版本，
     * 避免"改规则导致历史计划重算不一致"。</p>
     */
    private String ruleVersion;

    /** 首次触发日：车辆在库达到约定天数（预设 90 天）的日期。 */
    private LocalDate firstTriggerDate;

    /** 每期间隔天数（预设 30 天）。 */
    private Integer periodDays;

    /** 每期偿还比例：占原始垫款的比例（预设 10%）。 */
    private BigDecimal reduceRatio;

    /** 每期应还金额（整数日元）= 原始垫款 × reduceRatio。 */
    private long reduceAmount;

    /**
     * 已还期数。
     *
     * <p><b>迁移必带字段</b>：数据迁移时若丢失该值，
     * 新系统会从 0 期重新计算，导致还款计划错乱。</p>
     */
    private Integer paidPeriods;

    /** 下一期到期日。 */
    private LocalDate nextDueDate;

    /** 计划状态：ACTIVE(进行中) / COMPLETED(已完成) / DEFAULTED(违约)。 */
    private String status;

    public AgingPlan() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public LocalDate getFirstTriggerDate() {
        return firstTriggerDate;
    }

    public void setFirstTriggerDate(LocalDate firstTriggerDate) {
        this.firstTriggerDate = firstTriggerDate;
    }

    public Integer getPeriodDays() {
        return periodDays;
    }

    public void setPeriodDays(Integer periodDays) {
        this.periodDays = periodDays;
    }

    public BigDecimal getReduceRatio() {
        return reduceRatio;
    }

    public void setReduceRatio(BigDecimal reduceRatio) {
        this.reduceRatio = reduceRatio;
    }

    public long getReduceAmount() {
        return reduceAmount;
    }

    public void setReduceAmount(long reduceAmount) {
        this.reduceAmount = reduceAmount;
    }

    public Integer getPaidPeriods() {
        return paidPeriods;
    }

    public void setPaidPeriods(Integer paidPeriods) {
        this.paidPeriods = paidPeriods;
    }

    public LocalDate getNextDueDate() {
        return nextDueDate;
    }

    public void setNextDueDate(LocalDate nextDueDate) {
        this.nextDueDate = nextDueDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
