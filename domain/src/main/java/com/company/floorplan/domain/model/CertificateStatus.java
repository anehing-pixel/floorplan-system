package com.company.floorplan.domain.model;

/**
 * 車検証状态（USED R5.1，{@code inspection_certificates.status}）。
 *
 * <p><b>六态是定源集，不多不少</b>（D00 §8.1，落库为 V52 {@code ck_cert_status}）。
 * D03 §3.2 的状态图里还有第七个节点 {@code AMENDED_IN_PROGRESS}（换证在途），
 * 它不在定源六态内 ⟹ 「在途」这件事记在
 * {@code certificate_custody_events.event_type = 'AMENDED'}（V52 {@code ck_cce_event_type} 九值有它），
 * 换证完成时旧证直接置 {@link #REISSUED}。七态↔六态的差异由 V52 头注上报，
 * 本类按<b>库内 CHECK</b> 建模，不自造第七个枚举值。</p>
 *
 * <p>「在场 / 退出在场」的分界是 V53 的 {@code uk_cert_active_vin}
 * （{@code WHERE status NOT IN ('REISSUED','LOST')}）：其余四态各只允许一张有效证。
 * 注意 {@link #RELEASED} <b>也在场</b>——证已交还经销商不等于历史可抹，
 * 同 VIN 再插一张 {@link #RECEIVED} 必须先把 RELEASED 那张转成 REISSUED 或申报 LOST
 * （V53 头注的这条口径若要改，属状态语义变更，需业务签认，不由实现侧自决）。</p>
 */
public enum CertificateStatus {

    /** 已收证（尚未设质）。 */
    RECEIVED,

    /** 已设质（资金方持有对抗要件，{@code pledged_at} 必须有值）。 */
    PLEDGED,

    /** 放证待办（解质指令已下、证还没交出去）。 */
    RELEASE_PENDING,

    /** 已交还（{@code released_at} 必须有值；仍占「在场」坑位，见类注释）。 */
    RELEASED,

    /** 已换发（旧证的终态，退出在场，靠 {@code predecessor_id} 成新链）。 */
    REISSUED,

    /** 灭失（{@code lost_at} 必须有值，退出在场，走再発行另立新证）。 */
    LOST;

    /** 是否仍「在场」（V53 一车一证谓词的 Java 侧同源实现，别在业务代码里再抄一遍 NOT IN 清单）。 */
    public boolean isActive() {
        return this != REISSUED && this != LOST;
    }

    /** 是否为不再流转的终态（换发/灭失）。 */
    public boolean isTerminal() {
        return this == REISSUED || this == LOST;
    }

    /** 解析库内文本；空白返回 {@code null}，未知字面量抛出（库里存不下的值不该被静默降级）。 */
    public static CertificateStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return valueOf(raw.trim().toUpperCase());
    }
}
