package com.company.floorplan.domain.model;

import java.time.OffsetDateTime;

/**
 * 车商双维评分快照（USED R1，{@code dealers} 上的评分四列）。
 *
 * <p>单独成一个只读视图的原因：G-A4（D06 §1.3）要求放款闸门确认
 * 「分数是第二人签认后的终值」。判定要同时看到分数、录入人、签认人、确认时点四件事，
 * 少一件就退化成「信这个数字」。把这四件绑在一个不可变视图里取出来，
 * 调用方没法只拿分数不拿证据——这是防「闸门被软放行」最省事的形状。</p>
 *
 * <p><b>三评分不互相派生</b>（D06 §1.4，[待定源 U-11]）：本视图里的两枚分
 * 与既有的 {@code risk_grade}（授信分）、{@code dealer_risk_profile.risk_tier}（贷后画像分）
 * 是三个独立口径——授信分决定能不能借、质量分决定能借多少、画像分只用于贷后监控。
 * 任何一处用其中一枚推导另一枚，都是在制造 D01 U-R2 要消除的口径打架。</p>
 *
 * @param dealerId            车商主键
 * @param operatingScore      经营资质分（0–100）；null 表示未评
 * @param stockQualityScore   在库车辆质量分（0–100）；null 表示未评
 * @param setBy               录入人；与 {@link #coSignedBy} 成对
 * @param coSignedBy          第二签认人
 * @param confirmedAt         二人确认时点
 * @param scoresRunKey        评分批次幂等键（{@code DSCR:{dealerId}:{ts}}）
 */
public record DealerScores(Long dealerId,
                           Integer operatingScore,
                           Integer stockQualityScore,
                           String setBy,
                           String coSignedBy,
                           OffsetDateTime confirmedAt,
                           String scoresRunKey) {

    /** 是否一个分数都没有（新建车商的正常态，闸门要按「不满足 G-A4」处理）。 */
    public boolean unscored() {
        return operatingScore == null && stockQualityScore == null;
    }

    /**
     * G-A4 是否成立：分数存在，且录入人与第二人都在且不相同。
     *
     * <p>库内 {@code ck_dealers_score_two_person}（V77）保证同一件事，
     * 这里再判一次不是为了防御性编程，而是<b>闸门要能给出「为什么拒」的结论</b>：
     * CHECK 只能让写入失败，不能告诉放款人「你手上的分数没有第二人」。</p>
     */
    public boolean satisfiesTwoPersonRule() {
        return !unscored()
                && setBy != null && !setBy.isBlank()
                && coSignedBy != null && !coSignedBy.isBlank()
                && !coSignedBy.equals(setBy);
    }
}
