package com.company.floorplan.domain.model;

/**
 * 修復歴区分（R2.1 主数据属性、R2.3 车况票共有）。
 *
 * <p><b>取值定源</b>：D00 §8.1 {@code RepairHistoryFlag} 🆕——
 * {@code NONE · MINOR · MAJOR · UNKNOWN}。DB 侧双层约束在 V50 的
 * {@code ck_vu_repair_history}（主数据）与 V51 的 {@code ck_vcr_repair_history}（车况票）。</p>
 *
 * <p><b>{@code UNKNOWN} 的放款语义</b>（D00 §8.1 原文）：不得用于放款，
 * 按最审慎档 {@code MAJOR} 处理并建工单。注意"按 MAJOR 处理"发生在<b>取带/估值侧</b>
 * （D08 §4.1），主数据列本身仍写 {@code UNKNOWN}——把它就地改成 MAJOR 会伪造一个
 * 没人确认过的事实，D06 §2.3 的取严规则也就无法复现。</p>
 *
 * <p>取严（多源观测冲突）见 {@code RepairHistoryResolutionPolicy}（MAJOR 优先、UNKNOWN 不优于已知）。</p>
 */
public enum RepairHistoryFlag {

    /** 無（修復歴なし）。 */
    NONE("无"),

    /** 軽微。 */
    MINOR("轻微"),

    /** 重大（放款侧的最审慎档）。 */
    MAJOR("重大"),

    /** 判不清：按 MAJOR 参与取带，但列值保持 UNKNOWN 并建工单。 */
    UNKNOWN("未知");

    /** 中文展示名。 */
    private final String label;

    RepairHistoryFlag(String label) {
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
}
