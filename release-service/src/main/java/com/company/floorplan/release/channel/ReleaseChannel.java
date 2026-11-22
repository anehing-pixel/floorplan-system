package com.company.floorplan.release.channel;

import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;

import java.util.List;

/**
 * 指令下发通道接缝（Q9/Q10 已由 ADR 裁决；真有 partner 系统时加 adapter，不动状态机）。
 *
 * <p><b>Q9/Q10 已按项目内证据收敛</b>：接收方按释放项分放车线（经销商库）与
 * 放证线（凭证保管点），起步无外部 WMS、以 MANUAL 人工转交为主，
 * 默认实现见 {@link ManualReleaseChannel}。所以这里不再需要"等答复才能写"，
 * 但仍保留接口——真实 partner 系统的协议、地址、签名方式属于未来扩展，
 * 现在写死任何一家都会让答复来了要改核心链路。</p>
 *
 * <p><b>接口只约定语义</b>：「把一张指令及其释放项送出去」，不规定怎么送。
 * 四种通道（MANUAL / PORTAL / API / MQ）最终汇入同一张 {@code release_orders}，
 * 下游无感知。新增实现走 ADR-009 的 DMZ 入口 + 签名鉴权，
 * 调用方（{@code ReleaseApplicationService}）一行不用改。</p>
 *
 * <p><b>后续扩展（真有 partner 系统时）</b>：实现 {@code PortalReleaseChannel} /
 * {@code ApiReleaseChannel} / {@code MqReleaseChannel}，下发失败要重试 + 降级人工 +
 * 告警；回执 {@code source} 按实际通道落库，确认人记 {@code partner_code}。</p>
 */
public interface ReleaseChannel {

    /**
     * 下发一张指令及其释放项。
     *
     * @param order 已落库的指令（含 orderNo，下游据此回执）
     * @param items 释放项明细（逐项带实际接收方）
     * @throws RuntimeException 下发失败；应用服务据此保持 {@code PENDING_RELEASE}，不推进到 SENT
     */
    void dispatch(ReleaseOrder order, List<ReleaseOrderItem> items);
}
