package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.ErrorCode;

/**
 * 准入判定结论（{@code DealerAdmissionPolicy} 的返回值，D03 §2.1 的 {@code AdmissionDecision}）。
 *
 * <p>领域策略只回结论、不回 HTTP：错误码在这里是"结论的标识"，
 * 由应用层决定是否抛 {@code BusinessException}、web 层才变成 4xx（分层纪律 §4）。
 * 三个字段是一个整体——{@code state} 给台账与列表页，{@code errorCode} 给拒单路径，
 * {@code reason} 给人看的说明（含命中了规则 1 还是规则 3）。</p>
 */
public final class AdmissionDecision {

    /** 派生出的准入态。 */
    private final AdmissionState state;

    /** 阻断时对应的错误码；非阻断为 {@code null}。 */
    private final ErrorCode errorCode;

    /** 判定理由（中文，进审计与响应提示，不参与任何谓词）。 */
    private final String reason;

    private AdmissionDecision(AdmissionState state, ErrorCode errorCode, String reason) {
        this.state = state;
        this.errorCode = errorCode;
        this.reason = reason;
    }

    /**
     * 通过态（ADMITTED）。
     *
     * @param reason 理由
     * @return 结论
     */
    public static AdmissionDecision admitted(String reason) {
        return new AdmissionDecision(AdmissionState.ADMITTED, null, reason);
    }

    /**
     * 预警放行态（EXPIRING）：允许，但调用方需产出资质到期预警工单（规则 4）。
     *
     * @param reason 理由
     * @return 结论
     */
    public static AdmissionDecision expiring(String reason) {
        return new AdmissionDecision(AdmissionState.EXPIRING, null, reason);
    }

    /**
     * 阻断态。
     *
     * @param state     三个 {@code BLOCKED_*} 之一
     * @param errorCode 定源错误码（{@code QUAL_*}）
     * @param reason    理由
     * @return 结论
     */
    public static AdmissionDecision blocked(AdmissionState state, ErrorCode errorCode, String reason) {
        return new AdmissionDecision(state, errorCode, reason);
    }

    public AdmissionState getState() {
        return state;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getReason() {
        return reason;
    }

    /**
     * 是否阻断（直接转发 {@link AdmissionState#isBlocking()}，不在这里重复列枚举值）。
     *
     * @return 阻断返回 true
     */
    public boolean isBlocked() {
        return state.isBlocking();
    }
}
