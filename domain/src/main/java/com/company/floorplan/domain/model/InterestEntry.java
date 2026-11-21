package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计息流水（按日）。
 * rate_snapshot 保存计息当时的利率，支持利率变更后的历史追溯与重算。
 */
public class InterestEntry {

    /** 主键。 */
    private Long id;

    /** 车架号。 */
    private String vin;

    /** 计息业务日 —— 与 vin 组成唯一约束，是防重复计息的基石。 */
    private LocalDate bizDate;

    /** 当日的未偿本金基数（整数日元）。 */
    private long principalBalance;

    /** 当日利息（整数日元，已按舍入规则量化）。 */
    private long dailyInterest;

    /**
     * 计息当时的利率快照。
     *
     * <p>保存快照而非只存当前利率，是为了支持：
     * 利率中途变更后，仍能追溯"某一天是按什么利率算的"，
     * 并可在必要时按历史快照重算。</p>
     */
    private BigDecimal rateSnapshot;

    public InterestEntry() {
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

    public LocalDate getBizDate() {
        return bizDate;
    }

    public void setBizDate(LocalDate bizDate) {
        this.bizDate = bizDate;
    }

    public long getPrincipalBalance() {
        return principalBalance;
    }

    public void setPrincipalBalance(long principalBalance) {
        this.principalBalance = principalBalance;
    }

    public long getDailyInterest() {
        return dailyInterest;
    }

    public void setDailyInterest(long dailyInterest) {
        this.dailyInterest = dailyInterest;
    }

    public BigDecimal getRateSnapshot() {
        return rateSnapshot;
    }

    public void setRateSnapshot(BigDecimal rateSnapshot) {
        this.rateSnapshot = rateSnapshot;
    }
}
