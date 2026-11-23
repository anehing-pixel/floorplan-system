package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CertificateStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 車検証状态机（USED R5.1 / R2.5）。
 *
 * <p><b>存在这件事的理由是 D06 §2.5 的验收点：「非法状态流转被拒」。</b>
 * V52 的列注释写得更硬——「状态一律经领域方法流转，禁止 {@code UPDATE ... SET status=...}」，
 * AGENTS.md §6.3 也是同一条。所以仓储层只暴露本类认可的目标态，
 * 任何绕过本类的 SQL 改状态都算缺陷，不算优化。</p>
 *
 * <p><b>迁移表按 V52 的六态定源手写死，不做成配置</b>：状态图是业务裁决的产物
 * （D03 §3.2），可配置化等于给「谁能加一条边」开洞，而多出来的边正是 Q21 那类
 * 白名单绕过的形态。要改边，先改文档裁决，再改这里。</p>
 *
 * <p><b>错误码用 {@code VEHICLE_002}（状态非法流转）</b>：D00 §9 的 {@code CERT_} 前缀释义包含
 * 「状态非法流转」，但 D07 §8 的逐码表没给它分配码位，而 AGENTS.md §10 要求新增码同步前端文案。
 * 本包不自造 {@code CERT_006}，沿用 {@code UsedReleaseStateMachine} 用通用段码的先例
 * （{@code ErrorCode} 的 CERT 段注释已把这条冲突登记为待裁决项）。</p>
 */
public final class CertificateStateMachine {

    /** 合法迁移表：键是当前态，值是该态允许去的目标态集合。 */
    private static final Map<CertificateStatus, Set<CertificateStatus>> ALLOWED = buildAllowedTransitions();

    private CertificateStateMachine() {
    }

    /**
     * 迁移表本身。逐条理由：
     * <ul>
     *   <li>{@code RECEIVED → PLEDGED / RELEASED / REISSUED / LOST}：刚收进来的证可以直接设质、
     *       也可以发现收错了直接交还；</li>
     *   <li>{@code PLEDGED → RELEASE_PENDING / REISSUED / LOST}：解质要先下指令（放证线 R7），
     *       不允许「设质中」一步跳到交还——那会让「无回执即结清」这条红线失去拦截点；</li>
     *   <li>{@code RELEASE_PENDING → RELEASED / PLEDGED}：拿到保管方回执才 RELEASED；
     *       撤销解质则回到 PLEDGED（<b>不是</b> RECEIVED，质权从未消灭）；</li>
     *   <li>{@code RELEASED → RECEIVED}：同 VIN 换发新证后重新入库
     *       （V53 的 {@code uk_cert_active_vin} 要求旧 RELEASED 证先转 REISSUED/LOST，
     *       所以这条边只在旧证已让位、新证以 RECEIVED 入场时对旧证本身不再使用——
     *       留着是为了让「已交还的证」在业务上不是死记录，可再收一次）；</li>
     *   <li>{@code REISSUED / LOST} 是终态，出边为空。</li>
     * </ul>
     */
    private static Map<CertificateStatus, Set<CertificateStatus>> buildAllowedTransitions() {
        Map<CertificateStatus, Set<CertificateStatus>> table = new EnumMap<>(CertificateStatus.class);
        table.put(CertificateStatus.RECEIVED, EnumSet.of(
                CertificateStatus.PLEDGED, CertificateStatus.RELEASED,
                CertificateStatus.REISSUED, CertificateStatus.LOST));
        table.put(CertificateStatus.PLEDGED, EnumSet.of(
                CertificateStatus.RELEASE_PENDING, CertificateStatus.REISSUED, CertificateStatus.LOST));
        table.put(CertificateStatus.RELEASE_PENDING, EnumSet.of(
                CertificateStatus.RELEASED, CertificateStatus.PLEDGED, CertificateStatus.LOST));
        table.put(CertificateStatus.RELEASED, EnumSet.of(
                CertificateStatus.RECEIVED, CertificateStatus.REISSUED));
        table.put(CertificateStatus.REISSUED, EnumSet.noneOf(CertificateStatus.class));
        table.put(CertificateStatus.LOST, EnumSet.noneOf(CertificateStatus.class));
        return table;
    }

    /** 该迁移是否合法（判定与执行分离：闸门要能问「能不能设质」而不必先改状态）。 */
    public static boolean canTransition(CertificateStatus from, CertificateStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验迁移，非法即抛 {@code VEHICLE_002}。
     *
     * <p>异常消息里带上 from/to 与全部合法目标：只说「状态非法」的提示，
     * 客服和前端都无从判断是操作顺序错了还是数据本身错了。</p>
     */
    public static void assertTransition(CertificateStatus from, CertificateStatus to) {
        if (canTransition(from, to)) {
            return;
        }
        Set<CertificateStatus> legal = from == null ? Set.of() : ALLOWED.getOrDefault(from, Set.of());
        throw new BusinessException(ErrorCode.VEHICLE_002,
                "車検証状态非法流转: " + from + " → " + to
                        + "（该态合法目标: " + legal.stream().map(Enum::name).sorted().toList() + "）",
                Map.of("from", String.valueOf(from), "to", String.valueOf(to),
                        "allowed", legal.stream().map(Enum::name).toList()));
    }

    /**
     * 设质前置判定（P-7「无質押不放款」的正向那一半：<b>有瑕疵就不能设质</b>）。
     *
     * <p>只回答「状态机允不允许进 PLEDGED」＋「当前态已是 PLEDGED 时的幂等」。
     * 权利瑕疵是另一件事，由 {@code EncumbrancePolicy} 判——两件事分开，
     * 是因为「证在不在场」和「证干不干净」在业务上是两个独立失败原因，
     * 合成一个谓词后就没法告诉操作员该补哪一样。</p>
     *
     * @return true＝可以从当前态走设质动作
     */
    public static boolean mayPledge(CertificateStatus current) {
        return current == CertificateStatus.RECEIVED;
    }

    /** 当前态下所有合法目标（供前端把可执行动作列出来，而不是靠人背状态图）。 */
    public static Set<CertificateStatus> allowedTargets(CertificateStatus current) {
        return current == null ? Set.of() : Set.copyOf(ALLOWED.getOrDefault(current, Set.of()));
    }
}
