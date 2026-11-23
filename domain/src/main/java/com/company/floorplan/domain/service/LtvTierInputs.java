package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.LtvTierInput;
import com.company.floorplan.domain.model.UsedVehicleAttrsView;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 分档输入的装配（USED R3.2，D08 §4.1「维度语义与取值来源」的落点）。
 *
 * <p><b>为什么单独立一个类</b>：同一台车在「录入估值」「放款闸门」「政策试算」三处都要换算成
 * {@link LtvTierInput}，三处各写一遍月数差与里程口径，就会出现「试算说 0.75、放款判 0.70」
 * ——这类偏差在报表上看不出来，只在钱上体现。换算只允许有这一处。</p>
 *
 * <p><b>{@code model_heat} 与 {@code dealer_grade} 本切片拿不到值，恒传 {@code null}</b>，
 * 原因写在调用处。缺维度按 D08 §4.4 是<b>不命中</b>该带，所以只有不设限的带能命中，
 * 方向是保守的。若把它们填成 0 或某个具体档，就是把「不知道」变成「知道且很糟」，
 * 那会让高龄带/低热度的规则表被静默套用。</p>
 */
public final class LtvTierInputs {

    /** 里程来源不可信时的标记值（V50 {@code mileage_source} 的取值，实查自 DDL）。 */
    private static final String SOURCE_UNKNOWN = "UNKNOWN";

    private LtvTierInputs() {
    }

    /**
     * 由车辆主数据装配分档输入。
     *
     * @param view        {@code vehicle_units} 的 USED 列视图（含里程三列）
     * @param asOf        口径日：估值场景传 {@code valuation_date}，试算场景传今天
     * @param dealerGrade 车商等级；R1 双维评分的合成口径属 {@code [待定源 U-11]}，
     *                    上层拿不到定值时传 {@code null}（＝只有不限车商档的带能命中）
     */
    public static LtvTierInput fromVehicleMaster(UsedVehicleAttrsView view, LocalDate asOf, String dealerGrade) {
        if (view == null) {
            throw new IllegalArgumentException("view required");
        }
        Integer ageMonths = view.firstRegisteredOn() == null || asOf == null
                ? null : (int) ChronoUnit.MONTHS.between(view.firstRegisteredOn(), asOf);
        // 里程三列里 source 才决定数值可不可信：UNKNOWN 的里程可能来自过期票，
        // 用一个可疑的里程去落档比不填更危险（它会命中一个具体带并给出具体成数）。
        Integer mileageKm = view.mileage() != null && isTrustworthyMileageSource(view.mileageSource())
                ? view.mileage() : null;
        Integer inspection = view.inspectionRemainingMonths();
        if (inspection == null && view.inspectionExpiryDate() != null && asOf != null) {
            // 主数据列没维护就按 D08 §4.1 的公式现算：車検残存 = 車検満了日 − 口径日（月，不足一月不计）。
            // 允许算出负数（車検已过期），它自然命中不了任何 inspection_remaining_min>=0 的带。
            inspection = (int) ChronoUnit.MONTHS.between(asOf, view.inspectionExpiryDate());
        }
        return new LtvTierInput(ageMonths, mileageKm, view.repairHistory(), view.auctionGrade(),
                inspection,
                // model_heat 本切片恒 null：D08 §4.1 要由 trend_30d + sample_count 推导，
                // 但阈值与分档边界全在 [待定源 D-06] 的分档表里，签字前任何数字都是自造口径
                // （AGENTS.md §0 铁律 3）。null 走「该维不设限的带才命中」，方向保守。
                null, dealerGrade);
    }

    /** 里程来源是否可信：未知来源、缺来源都算不可信（D06 §2.4 优先级表的保守侧）。 */
    private static boolean isTrustworthyMileageSource(String source) {
        return source != null && !SOURCE_UNKNOWN.equalsIgnoreCase(source.trim());
    }
}
