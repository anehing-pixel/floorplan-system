package com.company.floorplan.release.channel;

import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;

import java.util.List;

/**
 * 指令下发通道接缝（Q9/Q10 业务未定的落地点）。
 *
 * <p><b>为什么是接口而不是直接写死一套下发逻辑</b>：
 * 设计稿 §7 待确认项 #1/#6——接收方是谁（第三方监管仓 / 驻场监管员 / 合格证保管点 /
 * 经销商自有仓库）、下发走人工还是系统直连，业务都还没拍板。
 * 现在写死任何一家 WMS 的协议，等答复来了就要改核心链路。
 * 这里只约定"把指令 + 明细送出去了"这一语义，具体实现可替换。</p>
 *
 * <p><b>四种通道最终汇入同一张 release_orders</b>（设计稿 §1.2⑥）：
 * 人工转交 / 监管方门户 / API 直连 / MQ，下游无感知。
 * 未来接真实通道时，新增实现走 ADR-009 的 DMZ 入口 + 签名鉴权，
 * 调用方（{@code ReleaseApplicationService}）一行不用改。</p>
 *
 * <p><b>TODO（Q9/Q10 确认后）</b>：
 * 实现 {@code PortalReleaseChannel} / {@code ApiReleaseChannel} / {@code MqReleaseChannel}，
 * 下发失败要重试 + 降级人工 + 告警；回执落 {@code release_receipts} 与 {@code integration_calls}。</p>
 */
public interface ReleaseChannel {

    /**
     * 下发一张指令及其释放项。
     *
     * @param order 已落库的指令（含 orderNo，下游据此回执）
     * @param items 释放项明细
     * @throws RuntimeException 下发失败；应用服务据此保持 {@code PENDING_RELEASE}，不推进到 SENT
     */
    void dispatch(ReleaseOrder order, List<ReleaseOrderItem> items);
}
