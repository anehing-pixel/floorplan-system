package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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

    // ==================== T2-4（M6 库龄递减增强） ====================

    /** 分层动作：监控 / 预警 / 强制还款 / 强制还款+冻结额度 / 转处置。 */
    public enum AgingAction {
        MONITOR, WARN, REPAY, FREEZE_REPAY, DISPOSE
    }

    /** 库龄分层定义（下界含、上界含；toDays=-1 表示无上限）。 */
    public record Tier(int fromDays, int toDays, AgingAction action,
                       BigDecimal ratio, int periodDays) {
    }

    /**
     * 内置默认分层规则（G1：从单层到分层）。
     *
     * <p><b>兼容说明</b>：91–120 天档沿用现有 10% 与 30 天周期，与存量计划一致，
     * 避免升级后既有 V1 计划的每期金额突变；更高档位按设计稿递增。
     * 后台可配的 aging_rule_tiers 表需新增迁移（本次禁改迁移文件），故先内置。</p>
     */
    private static final List<Tier> TIERS = List.of(
            new Tier(0, 60, AgingAction.MONITOR, BigDecimal.ZERO, 0),
            new Tier(61, 90, AgingAction.WARN, BigDecimal.ZERO, 0),
            new Tier(91, 120, AgingAction.REPAY, new BigDecimal("0.10"), 30),
            new Tier(121, 180, AgingAction.REPAY, new BigDecimal("0.20"), 30),
            new Tier(181, 270, AgingAction.FREEZE_REPAY, new BigDecimal("0.30"), 30),
            new Tier(271, -1, AgingAction.DISPOSE, BigDecimal.ZERO, 0)
    );

    /** 按当前库龄命中分层。 */
    public static Tier tierFor(int agingDays) {
        for (Tier t : TIERS) {
            if (agingDays >= t.fromDays() && (t.toDays() == -1 || agingDays <= t.toDays())) {
                return t;
            }
        }
        return TIERS.get(0);
    }

    /**
     * G2：应还额动态重算——按当前分层比例 × 当前未偿本金，而不是生成计划时算死的金额。
     *
     * @param currentBalance 当前未偿本金
     * @param agingDays      当前库龄
     * @param fallbackAmount 非还款档位/无库龄时沿用的计划金额（兼容存量）
     */
    public static long currentAmount(long currentBalance, Integer agingDays, long fallbackAmount) {
        Tier tier = tierFor(agingDays == null ? 0 : agingDays);
        if (tier.action() == AgingAction.REPAY || tier.action() == AgingAction.FREEZE_REPAY) {
            return BigDecimal.valueOf(currentBalance)
                    .multiply(tier.ratio()).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return fallbackAmount;
    }

    /** G4：豁免判定——命中名单的车不生成递减计划（如主机厂调拨中、整备运输中）。 */
    public static boolean isExempt(String vin, Set<String> exemptVins) {
        return vin != null && exemptVins != null && exemptVins.contains(vin);
    }
}
