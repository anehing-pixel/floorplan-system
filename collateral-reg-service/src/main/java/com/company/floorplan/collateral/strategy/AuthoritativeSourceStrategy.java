package com.company.floorplan.collateral.strategy;

import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;

import java.util.List;

/**
 * 三源冲突裁决策略——把"担保以哪张表为准"做成可替换的策略，而不是写死在查询里。
 *
 * <p><b>Q27 阻塞</b>：业务口径尚未书面确认。因此：
 * <ul>
 *   <li>默认实现 {@link DefaultAuthoritativeSourceStrategy} 由开关控制，<b>默认关闭</b>；</li>
 *   <li>关闭时不采纳任何裁决，调用方保持当前权属不变（fail-closed，不放行放款）；</li>
 *   <li>开启时的优先级只是占位默认值，待 Q27 定源后确认或替换实现。</li>
 * </ul>
 * 这样既把骨架搭好，又不把未确认的业务规则伪装成既定事实。</p>
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
