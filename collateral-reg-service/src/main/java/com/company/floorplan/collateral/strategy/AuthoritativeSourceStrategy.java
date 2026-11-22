package com.company.floorplan.collateral.strategy;

import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;

import java.util.List;

/**
 * 三源<b>对账体检</b>策略——产出「三源是否一致 / 建议采信哪个源」的诊断结论。
 *
 * <p><b>ADR-Q27（已裁决）</b>：V10 登记批次表是唯一权威源，三源快照都是派生状态。
 * 因此本策略<b>不参与放款门禁判定</b>，也不改任何权属表；
 * 门禁由 {@code CollateralRegistrationService#getCurrent} 直读 V10 决定。
 * 本接口的产物只用于运营对账报告与冲突告警，避免派生状态反过来污染法律事实。</p>
 *
 * <p>开关 {@code floorplan.collateral.authoritative-source.enabled} 仅控制是否产出诊断，
 * 默认关闭时返回 skipped；关闭<b>不影响</b>门禁（门禁走 V10），也不会放行或阻断放款。</p>
 */
public interface AuthoritativeSourceStrategy {

    /** 依据三源快照给出裁决。snapshots 可为 null/空。 */
    Decision decide(String vin, List<SourceSnapshot> snapshots);

    /** 裁决结果：是否采纳、采信的状态与权属字段、可读理由。 */
    final class Decision {
        private final boolean adopted;
        private final RegistrationStatus status;
        private final String regNo;
        private final String ownerName;
        private final String source;
        private final Long amount;
        /** 被采信快照的时间，供登记时间兜底。 */
        private final java.time.LocalDateTime effectiveAt;
        private final String reason;

        private Decision(boolean adopted, RegistrationStatus status, String regNo,
                         String ownerName, String source, Long amount,
                         java.time.LocalDateTime effectiveAt, String reason) {
            this.adopted = adopted;
            this.status = status;
            this.regNo = regNo;
            this.ownerName = ownerName;
            this.source = source;
            this.amount = amount;
            this.effectiveAt = effectiveAt;
            this.reason = reason;
        }

        /** 不采纳：策略关闭 / 无可采信数据时使用，调用方应保持当前权属不变。 */
        public static Decision skipped(String reason) {
            return new Decision(false, RegistrationStatus.NONE, null, null, null, null, null, reason);
        }

        /** 采纳：按裁决结果覆盖当前权属。 */
        public static Decision adopt(RegistrationStatus status, String regNo, String ownerName,
                                     String source, Long amount,
                                     java.time.LocalDateTime effectiveAt, String reason) {
            return new Decision(true, status == null ? RegistrationStatus.NONE : status,
                    regNo, ownerName, source, amount, effectiveAt, reason);
        }

        public boolean isAdopted() { return adopted; }
        public RegistrationStatus getStatus() { return status; }
        public String getRegNo() { return regNo; }
        public String getOwnerName() { return ownerName; }
        public String getSource() { return source; }
        public Long getAmount() { return amount; }
        public java.time.LocalDateTime getEffectiveAt() { return effectiveAt; }
        public String getReason() { return reason; }
    }
}
