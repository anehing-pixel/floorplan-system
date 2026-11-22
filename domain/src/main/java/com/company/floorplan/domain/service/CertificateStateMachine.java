package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CertificateStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 車検証状态机（R2.5；转移白名单定源 = D03 §3.2 的状态图，取值定源 = D00 §8.2 的六态）。
 *
 * <p><b>实现照抄仓内正面样例</b>：{@code ReleaseStateMachine}（release-service）的
 * 静态 {@code EnumMap} 白名单 + {@code canTransit} + {@code requireTransition} 三件套。
 * D00 §8 约定③ 要求「状态类枚举必须附 canTransit 白名单」，本类就是那份白名单；
 * 约定③ 同时要求配套硬编码单测——本轮禁测，单测欠项已登记在实现说明，不假装已完成。</p>
 *
 * <p><b>白名单不含第七态</b>：D03 §3.2 的图里有 {@code AMENDED_IN_PROGRESS}，
 * D00 §8.2 只冻结六态。本类的表达是：
 * 换证在途由事件 {@code AMENDED}（{@code certificate_custody_events}）记录，
 * 状态侧写的是「旧证 {@code -> REISSUED}」这条真实迁移
 * （对应图里 {@code PLEDGED -> AMENDED_IN_PROGRESS -> RECEIVED} 的旧证那一半）。
 * 于是六态白名单足以覆盖图中的全部旧证迁移，不需要新状态值。</p>
 *
 * <p><b>非法流转的错误码（文档缺口）</b>：D07 §8 的 {@code CERT_001}–{@code CERT_005}
 * 五个码里没有"状态非法流转"（D00 §9 的 {@code CERT_} 前缀释义提到该含义，
 * 但号位未分配，而 D00 §9 又禁止自派新码）。本类沿用 {@code ReleaseStateMachine}
 * 与既有车辆链的写法抛 {@code VEHICLE_002}（422「状态非法流转」），
 * 等 D07 §8 补 {@code CERT_006} 后由收口方一处替换。</p>
 */
public final class CertificateStateMachine {

    /** 状态 -> 允许的后继状态集合。 */
    private static final Map<CertificateStatus, Set<CertificateStatus>> ALLOWED =
            new EnumMap<>(CertificateStatus.class);

    static {
        // RECEIVED -> PLEDGED：权属核查通过且担保设定完成（D03 §3.2 表第 1 行，前置 CERT_005/CERT_003）。
        allow(CertificateStatus.RECEIVED, CertificateStatus.PLEDGED);
        // PLEDGED -> RELEASE_PENDING：该 VIN 债权已消灭（前置 RELS_001，属 W-U4 放证线）。
        allow(CertificateStatus.PLEDGED, CertificateStatus.RELEASE_PENDING);
        // RELEASE_PENDING -> RELEASED：接收方权威回执（前置 RELS_002）。
        allow(CertificateStatus.RELEASE_PENDING, CertificateStatus.RELEASED);
        // 换证：旧证无论处于 RECEIVED 还是 PLEDGED，都可因新证到达而置 REISSUED
        // （D03 §3.2 的 AMENDED_IN_PROGRESS 分支在六态模型下的落点）。
        allow(CertificateStatus.RECEIVED, CertificateStatus.REISSUED);
        allow(CertificateStatus.PLEDGED, CertificateStatus.REISSUED);
        // 灭失：图中只有 RECEIVED / PLEDGED 两条入边（前置「二人审批 + 再发行计划」CERT_004）。
        allow(CertificateStatus.RECEIVED, CertificateStatus.LOST);
        allow(CertificateStatus.PLEDGED, CertificateStatus.LOST);
        // RELEASED / REISSUED / LOST 为终态：不登记任何出边（图中 RELEASED -> [*]、LOST -> [*]，
        // 且 D03 §3.2 明文「禁止 RELEASED -> *」）。
    }

    private CertificateStateMachine() {
        // 工具类：与 ReleaseStateMachine 一样不实例化，避免有人以为可以按聚合 new 一个状态机。
    }

    /** 登记一条允许的迁移。 */
    private static void allow(CertificateStatus from, CertificateStatus to) {
        ALLOWED.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to);
    }

    /**
     * 是否允许该迁移（不抛异常版本，供查询与预览用）。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 允许返回 true
     */
    public static boolean canTransit(CertificateStatus from, CertificateStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<CertificateStatus> next = ALLOWED.get(from);
        return next != null && next.contains(to);
    }

    /**
     * 要求该迁移合法，否则抛业务异常。
     *
     * @param from 当前状态
     * @param to   目标状态
     */
    public static void requireTransition(CertificateStatus from, CertificateStatus to) {
        if (!canTransit(from, to)) {
            throw new BusinessException(ErrorCode.VEHICLE_002,
                    "車検証状态非法流转：" + from + " -> " + to);
        }
    }

    /**
     * 列出某状态的全部合法后继（前端按钮与验收断言用）。
     *
     * @param from 当前状态
     * @return 不可变集合；终态返回空集
     */
    public static Set<CertificateStatus> nextStates(CertificateStatus from) {
        Set<CertificateStatus> next = ALLOWED.get(from);
        return next == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(next));
    }
}
