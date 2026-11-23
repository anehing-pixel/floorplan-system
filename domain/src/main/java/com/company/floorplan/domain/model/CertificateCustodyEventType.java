package com.company.floorplan.domain.model;

/**
 * 車検証保管/核查流水的事件类型（{@code certificate_custody_events.event_type}，九值）。
 *
 * <p>取值集落库为 V52 {@code ck_cce_event_type}：
 * {@code RECEIVED / PLEDGED / TRANSFERRED / RELEASED / REISSUED / LOST /
 * AMENDED / ENCUMBRANCE_FOUND / CHECK_DONE}。</p>
 *
 * <p>九值比 {@link CertificateStatus} 的六态多出 {@code TRANSFERRED}（换保管者，状态不变）、
 * {@code AMENDED}（换证/記載変更<b>在途</b>）、{@code ENCUMBRANCE_FOUND}（发现瑕疵）、
 * {@code CHECK_DONE}（核查完成）。<b>后四个正是「六态装不下的事实」的存放处</b>——
 * D03 §3.2 状态图里的第七节点「换证在途」就记在这里而不是加状态（见 {@link CertificateStatus} 类注释）。
 * 因此本枚举不是状态枚举的别名，两者不能互相推导。</p>
 */
public enum CertificateCustodyEventType {

    /** 收证（对应进入 RECEIVED）。 */
    RECEIVED,

    /** 设质（对应进入 PLEDGED）。 */
    PLEDGED,

    /** 移交：保管者变了，状态可以不变（监管仓之间调证实录）。 */
    TRANSFERRED,

    /** 交还经销商（对应进入 RELEASED）。 */
    RELEASED,

    /** 换发：旧证退出在场（对应进入 REISSUED）。 */
    REISSUED,

    /** 灭失申报（对应进入 LOST）。 */
    LOST,

    /** 换证/記載変更<b>在途</b>：只有流水，没有对应的第七态。 */
    AMENDED,

    /** 核查发现权利瑕疵：瑕疵明细记本行 {@code encumbrance_type}，聚合结论回写证表。 */
    ENCUMBRANCE_FOUND,

    /** 核查完成且无瑕疵（证明「查过」这件事本身也要留痕，否则无法区分查过与没查）。 */
    CHECK_DONE;

    /** 该事件是否会改变证的状态（{@link #TRANSFERRED} / {@link #ENCUMBRANCE_FOUND} / {@link #CHECK_DONE} 不改）。 */
    public boolean changesStatus() {
        return switch (this) {
            case RECEIVED, PLEDGED, RELEASED, REISSUED, LOST -> true;
            case TRANSFERRED, AMENDED, ENCUMBRANCE_FOUND, CHECK_DONE -> false;
        };
    }

    /** 解析库内文本。 */
    public static CertificateCustodyEventType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return valueOf(raw.trim().toUpperCase());
    }
}
