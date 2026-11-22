package com.company.floorplan.domain.model;

/**
 * 车商准入态（USED 域 R1.1 / R1.5）。
 *
 * <p><b>取值定源</b>：D03 §2.1 的 {@code DealerQualification.admissionState} 行
 * （{@code ADMITTED / BLOCKED_LICENSE / BLOCKED_COMPLIANCE / BLOCKED_MEMBERSHIP / EXPIRING}）
 * 与 D04 §3.4 的 {@code dealers.admission_state}（本包 V46 建列 + {@code ck_dealers_admission_state}）。
 * D03 §2.1 原文把 VO 名写成 {@code AdmissionsState}（多一个 s），本类按单数
 * {@code AdmissionState} 落地——它是 D06 §1.2 服务签名 {@code recalculateAdmission(...) → AdmissionState}
 * 的写法；名称差异已登记进实现说明的文档冲突清单。</p>
 *
 * <p><b>本枚举是派生快照，不是事实源</b>：D03 §2.1 明写「派生，不落库或只落快照」。
 * 落在 {@code dealers.admission_state} 只是为了列表页与闸门判断的读取代价，
 * 权威值每次由 {@code DealerAdmissionPolicy} 从許可／合规／会员三方重算。
 * 因此<b>不存在</b>直接写本列的接口：写入口只有
 * {@code DealerQualificationApplicationService.recalculateAdmission(...)}
 * （UC-R1-4，D07 §7.1 的 {@code POST /api/dealers/{id}/admission/recalculate}）。</p>
 *
 * <p><b>{@code EXPIRING} 是预警态，{@code BLOCKED_*} 是阻断态</b>：判定顺序见
 * {@code DealerAdmissionPolicy}——三个 BLOCKED 任一命中即拒，无绕过开关（AGENTS.md §8 的 Q21 教训），
 * 本枚举不提供 {@code isBlocked()} 之外的第二套谓词，避免"列表按颜色分、闸门按另一套分"。</p>
 */
public enum AdmissionState {

    /** 准入通过。 */
    ADMITTED("准入通过"),

    /** 許可缺失或已过期（D03 §2.1 规则 1，{@code QUAL_001}）。 */
    BLOCKED_LICENSE("許可阻断"),

    /** 无有效合规通过记录（规则 2，{@code QUAL_003}；UNKNOWN 计不通过）。 */
    BLOCKED_COMPLIANCE("合规阻断"),

    /** 申请指向的拍卖场无有效会员资格（规则 3，{@code QUAL_002}）。 */
    BLOCKED_MEMBERSHIP("会员资格阻断"),

    /** 资质临近到期：允许放行但产出预警工单（规则 4，阈值读参数，不写死天数）。 */
    EXPIRING("临近到期");

    /** 中文展示名。 */
    private final String label;

    AdmissionState(String label) {
        this.label = label;
    }

    /**
     * 中文展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否阻断态（三个 {@code BLOCKED_*}）。
     *
     * <p>{@code EXPIRING} 返回 false：D03 §2.1 规则 4 的动作是"允许但建工单"，
     * 把它算进阻断会让预警变成拒单，业务上等于凭空加了一条未签字的规则。</p>
     *
     * @return 阻断返回 true
     */
    public boolean isBlocking() {
        return this == BLOCKED_LICENSE || this == BLOCKED_COMPLIANCE || this == BLOCKED_MEMBERSHIP;
    }
}
