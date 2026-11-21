package com.company.floorplan.loan.model;

/**
 * DPD 阶段（M 系列账龄分层）。
 *
 * <p>阈值来自设计稿 §4.3 种子数据（1-based，自然日 CALENDAR 口径）：
 * M1=1-30 / M2=31-60 / M3=61-90 / M4≥91。DPD≤0 归 {@link #NONE}（未逾期）。</p>
 *
 * <p>本枚举只描述分档区间，不含计算逻辑；计算见
 * {@code com.company.floorplan.loan.service.DpdCalculator}（纯函数）。</p>
 */
public enum DpdStage {

    NONE("NONE", 0, 0, "未逾期"),
    M1("M1", 1, 30, "早期逾期"),
    M2("M2", 31, 60, "风险升高"),
    M3("M3", 61, 90, "严重逾期"),
    M4("M4", 91, Integer.MAX_VALUE, "待认定违约候选");

    private final String code;
    private final int minDpd;
    private final int maxDpd;
    private final String label;

    DpdStage(String code, int minDpd, int maxDpd, String label) {
        this.code = code;
        this.minDpd = minDpd;
        this.maxDpd = maxDpd;
        this.label = label;
    }

    public String getCode() { return code; }
    public int getMinDpd() { return minDpd; }
    public int getMaxDpd() { return maxDpd; }
    public String getLabel() { return label; }

    /** DPD 是否落在本档闭区间 [minDpd, maxDpd]。 */
    public boolean contains(int dpd) {
        return dpd >= minDpd && dpd <= maxDpd;
    }

    /** 按 code 反查，未知值返回 null（如数据库里的历史 D0）。 */
    public static DpdStage fromCode(String code) {
        if (code == null) return null;
        for (DpdStage s : values()) {
            if (s.code.equals(code)) return s;
        }
        return null;
    }
}
