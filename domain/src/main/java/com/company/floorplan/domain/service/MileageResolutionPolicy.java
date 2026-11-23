package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.MileageSample;
import com.company.floorplan.domain.model.MileageSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 里程采信判定（USED R2.3 / R2.4，纯函数、无 Spring、无库访问）。
 *
 * <p><b>要解决的问题</b>：同一辆车同时存在三个里程事实——
 * {@code vehicle_units.mileage}（主数据权威）、{@code auction_lots.mileage_km}（票面）、
 * {@code vehicle_condition_reports.odometer_km} / {@code release_receipts.odometer_km}（实物快照），
 * 三者数值可以合法地不一样。V50 的列注释把合并口径写成两句话：
 * <b>高优先实物观测胜出时采纳其值并同步改 {@code mileage_source}（D06 §2.3）；
 * 低优先来源（票面／申报）与主数据不一致时只建差异工单、不回写（D04 §5.1）</b>。
 * 两条合起来就是本策略的全部行为，没有第三种情况。</p>
 *
 * <p><b>为什么不静默覆盖</b>：里程直接进 R3 的估值取带，改一次数就等于改一次放款基准。
 * 所以任何一次判定都要能回答「为什么是这个数」——{@link Decision#reasons()} 里的
 * 每条依据都由应用层写进审计，超阈值的那条同时落 {@code wms_reconcile_diffs}（ODOMETER_DIFF）。</p>
 *
 * <p><b>{@code diffThresholdKm} 从配置读</b>（{@code floorplan.used.mileage-diff-threshold-km}，
 * 值由 D06 §2.5 直接给出、非待定源），不在这里写死常量：写死了以后就没人能在不重新发版的情况下收紧口径，
 * 而冒烟也就测不出「这个数到底来自库里还是来自代码」。</p>
 */
public final class MileageResolutionPolicy {

    private MileageResolutionPolicy() {
    }

    /** 判定结论的类型，直接对应应用层要做哪个动作。 */
    public enum Outcome {

        /** 采纳新观测：回写 {@code mileage} + {@code mileage_source} + {@code mileage_captured_on}。 */
        ADOPTED,

        /** 主数据保持不动（新观测优先级不高于现来源）。 */
        KEPT_MASTER,

        /** 无任何可用观测：主数据按库内约束落「UNKNOWN 且 mileage 为 NULL」，并压住放款。 */
        UNDETERMINED
    }

    /**
     * 判定结论。
     *
     * @param outcome     结论类型
     * @param source      结论后的 {@code mileage_source} 取值
     * @param mileageKm   结论后的 {@code mileage}（{@code UNDETERMINED} 时为 {@code null}，见 V50 CHECK）
     * @param capturedOn  结论后的 {@code mileage_captured_on}
     * @param evidenceRef 胜出观测的证据引用（写进审计与差异行，供回查）
     * @param diffKm      与主数据的绝对差（无比较对象时为 {@code null}）
     * @param exceedsThreshold 差异是否超阈值（超阈值必须建 ODOMETER_DIFF 差异）
     * @param blocksAdvance    是否因里程不可信而压住放款（仅 {@code UNDETERMINED} 为真）
     * @param reasons     判定依据，逐条可读
     */
    public record Decision(Outcome outcome,
                           MileageSource source,
                           Integer mileageKm,
                           LocalDate capturedOn,
                           String evidenceRef,
                           Integer diffKm,
                           boolean exceedsThreshold,
                           boolean blocksAdvance,
                           List<String> reasons) {
    }

    /**
     * @param master           主数据当前里程（可为 {@code null} ＝ 从未采集）
     * @param candidates       本轮拿到的观测样本（拍卖票面、车况票、WMS 入库、回执等），顺序无关
     * @param diffThresholdKm  差异阈值（公里）；{@code <= 0} 表示「任何差异都算超阈值」
     */
    public static Decision resolve(MileageSample master, List<MileageSample> candidates, int diffThresholdKm) {
        List<String> reasons = new ArrayList<>();

        // ── 1. 先筛掉不能用的观测：UNKNOWN 与没读到数的样本直接出局（D06 §2.4） ──
        List<MileageSample> usable = new ArrayList<>();
        if (candidates != null) {
            for (MileageSample c : candidates) {
                if (c != null && c.usable()) {
                    usable.add(c);
                } else if (c != null) {
                    reasons.add("忽略样本：source=" + c.source() + " mileage=" + c.mileageKm()
                            + " ref=" + c.evidenceRef() + "（UNKNOWN 或无读数不参与采信）");
                }
            }
        }

        boolean masterUsable = master != null && master.usable();

        // ── 2. 没有任何可用观测 ⟹ UNKNOWN 态：建账允许、放款阻断 ────────────────
        // 为什么不是「直接报错」：D06 §2.4 明确「全为 UNKNOWN 允许建账但禁止放款」——
        // 批发购入的车常常连票面里程都没有，拒绝建账会让这台车在系统里根本不存在，
        // 比「存在但不能放款」更危险（线下就开始了）。
        if (usable.isEmpty()) {
            if (masterUsable) {
                reasons.add("本轮无可用观测，主数据保持 " + master.mileageKm() + "km（来源 " + master.source() + "）");
                return new Decision(Outcome.KEPT_MASTER, master.source(), master.mileageKm(),
                        master.capturedOn(), master.evidenceRef(), null, false, false, reasons);
            }
            reasons.add("主数据与本轮观测全都不可用 → mileage_source=UNKNOWN 且 mileage=NULL"
                    + "（V50 ck_vu_mileage_unknown 要求二者成对出现）");
            return new Decision(Outcome.UNDETERMINED, MileageSource.UNKNOWN, null, null, null,
                    null, false, true, reasons);
        }

        // ── 3. 同优先级取最新（D06 §2.4）：先比来源优先级，再比采集时点 ──────────
        // capturedOn 为 null 的排在有值的后面——「不知道什么时候数的」不能盖过「昨天数的」。
        MileageSample winner = usable.stream()
                .min(Comparator.comparingInt(MileageSample::priority)
                        .thenComparing(s -> s.capturedOn() == null
                                        ? LocalDate.MIN : s.capturedOn(),
                                Comparator.reverseOrder()))
                .orElseThrow();

        // ── 4. 与主数据比：高优先才覆盖，低优先只建差异 ─────────────────────────
        MileageSource masterSource = master == null ? null : master.source();
        boolean higherPriority = winner.source().outranks(masterSource);
        Integer diff = (masterUsable && winner.mileageKm() != null)
                ? Math.abs(winner.mileageKm() - master.mileageKm()) : null;
        boolean exceeds = diff != null && diff > Math.max(diffThresholdKm, 0);

        if (diff != null) {
            reasons.add("与主数据差 " + diff + "km（主数据 " + master.mileageKm() + "km/" + masterSource
                    + "，本次 " + winner.mileageKm() + "km/" + winner.source() + "），阈值 "
                    + diffThresholdKm + "km → " + (exceeds ? "超阈值，建 ODOMETER_DIFF 差异" : "阈值内"));
        } else {
            reasons.add("主数据无里程可比，本次为首个可用观测");
        }

        if (higherPriority) {
            reasons.add("来源升级 " + masterSource + " → " + winner.source()
                    + "（实物观测优先，D06 §2.3）→ 回写主数据；覆盖动作本身写审计，不留静默改数");
            return new Decision(Outcome.ADOPTED, winner.source(), winner.mileageKm(),
                    winner.capturedOn(), winner.evidenceRef(), diff, exceeds, false, reasons);
        }

        reasons.add("来源 " + winner.source() + " 不优于主数据 " + masterSource
                + " → 不回写（D04 §5.1「按主数据不覆盖」），差异" + (exceeds ? "已" : "未") + "超阈值");
        return new Decision(Outcome.KEPT_MASTER, masterSource, master == null ? null : master.mileageKm(),
                master == null ? null : master.capturedOn(), winner.evidenceRef(), diff, exceeds, false, reasons);
    }
}
