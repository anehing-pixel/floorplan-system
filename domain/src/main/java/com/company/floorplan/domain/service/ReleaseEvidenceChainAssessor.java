package com.company.floorplan.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据链闭合判定（D09 §11「什么叫做闭环」的八条，一条不缺）。
 *
 * <p><b>三态而不是布尔</b>：每条证据的取值是 {@code TRUE / FALSE / null}，
 * {@code null} 表示"本轮无法取证"。缺的项和取不到证的项都必须出现在未闭合清单里——
 * D09 §11 的原话是「现状最大的问题不是没做完，而是看起来完成了」，
 * 把"取不到证"折叠成 true 或 false 都会正好造出这句话描述的那个假象
 * （A 类：{@code closed()} 只在全部为 TRUE 时才 true，RL-7）。</p>
 *
 * <p><b>本轮已知无法取证的两条</b>（实测：分支库 V1–V26 ＋ V65–V69 里没有这两张表）：
 * ④ {@code integration.outbound_tasks}（W-U3 的 V70+ 号段）；
 * ⑥ {@code inventory_releases}（R9 未签字，本轮绝不裸建）。
 * 这两条只能由调用方传 {@code null}，判定结果恒为"未闭合"，
 * 直到对应包把表建出来——这是<b>设计上的未闭合</b>，不是接线遗漏，必须在报表里这么显示。</p>
 */
public final class ReleaseEvidenceChainAssessor {

    private ReleaseEvidenceChainAssessor() {
    }

    /** 八条证据的事实载体；由 application 层从各自表读出来后注入，domain 不碰 JdbcTemplate。 */
    public static final class EvidenceFacts {
        /** ① 资金闸门通过记录（audit_events GATE_* ＋ release_state_history）。 */
        public Boolean gateRecorded;
        /** ② 担保实体权利消灭时间戳（release_orders.lien_extinguished_at）。 */
        public Boolean lienExtinguished;
        /** ③ 每个 item 的接收方可解析且有历史（receiver_* 三键）。 */
        public Boolean receiverResolvable;
        /** ④ 出站任务 SENT（integration.outbound_tasks，本轮不可取证）。 */
        public Boolean outboundSent;
        /** ⑤ 权威回执（signature_ok 且 inbound_message_id 非空）或显式降级标记。 */
        public Boolean authoritativeReceipt;
        /** ⑥ 库存出库记录（inventory_releases，R9 未签字，本轮不可取证）。 */
        public Boolean inventoryReleased;
        /** ⑦ 車検証终态（RELEASED/REISSUED）＋保管事件链。 */
        public Boolean certificateClosed;
        /** ⑧ 登记抹消完成（来自入站证据）＋台账 SETTLED＋回补事件消费成功。 */
        public Boolean settleAndDischarge;

        /** 全 true 构造（给单测与"确实齐了"的场景用）。 */
        public static EvidenceFacts allTrue() {
            EvidenceFacts f = new EvidenceFacts();
            f.gateRecorded = f.lienExtinguished = f.receiverResolvable = f.outboundSent = Boolean.TRUE;
            f.authoritativeReceipt = f.inventoryReleased = f.certificateClosed = f.settleAndDischarge = Boolean.TRUE;
            return f;
        }
    }

    /** 判定结果：是否闭合 ＋ 缺失/不可取证清单（RL-7 要求视图能直接返回这些项）。 */
    public static final class EvidenceAssessment {
        private final boolean closed;
        private final List<String> missing;
        private final List<String> notAssessable;

        EvidenceAssessment(boolean closed, List<String> missing, List<String> notAssessable) {
            this.closed = closed;
            this.missing = List.copyOf(missing);
            this.notAssessable = List.copyOf(notAssessable);
        }

        /** 是否八条全部为 TRUE（A 类断言）。 */
        public boolean isClosed() {
            return closed;
        }

        /** 明确缺失（FALSE）的证据项。 */
        public List<String> getMissing() {
            return missing;
        }

        /** 无法取证（null）的证据项：不等于缺失，但同样不算闭合。 */
        public List<String> getNotAssessable() {
            return notAssessable;
        }
    }

    /** 逐条判定，顺序与 D09 §11 表的编号一致，便于报表按 # 对齐。 */
    public static EvidenceAssessment assess(EvidenceFacts f) {
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        check(f.gateRecorded, "1 资金闸门通过记录", missing, unknown);
        check(f.lienExtinguished, "2 担保实体权利消灭时间戳", missing, unknown);
        check(f.receiverResolvable, "3 接收方可解析且有历史", missing, unknown);
        check(f.outboundSent, "4 出站任务 SENT", missing, unknown);
        check(f.authoritativeReceipt, "5 权威回执或显式降级标记", missing, unknown);
        check(f.inventoryReleased, "6 库存出库记录", missing, unknown);
        check(f.certificateClosed, "7 車検証终态与保管事件链", missing, unknown);
        check(f.settleAndDischarge, "8 登记抹消完成＋台账结清＋回补消费成功", missing, unknown);
        return new EvidenceAssessment(missing.isEmpty() && unknown.isEmpty(), missing, unknown);
    }

    /** 单条归集：null 进"不可取证"，false 进"缺失"，true 不计。 */
    private static void check(Boolean fact, String name, List<String> missing, List<String> unknown) {
        if (fact == null) {
            unknown.add(name);
        } else if (!fact) {
            missing.add(name);
        }
    }
}
