package com.company.floorplan.release.channel;

import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 默认下发通道：只打日志，不连任何外部系统。
 *
 * <p><b>这是接缝的默认实现，不是"人工转交"的正式实现</b>：
 * Q9（接收方是谁）/ Q10（有无外部 WMS）未确认前，
 * 服务必须能编译、能启动、能走完状态机，所以先用日志实现占位。
 * 它让"创建指令 → 下发"这段链路可观测（日志里有指令号、VIN、接收方），
 * 但不会产生真实的外部副作用。</p>
 *
 * <p><b>TODO（业务确认后替换）</b>：
 * 1. 接真实 WMS / 监管门户，签名走 ADR-009 DMZ 入口；
 * 2. 下发结果回写 {@code delivery_channel} / {@code delivered_at} / {@code receipt_id}；
 * 3. 失败重试 + 降级人工通道 + 告警；
 * 4. 回执落 {@code release_receipts}，确认人记 partner_code（非自然人）。</p>
 */
public class LoggingReleaseChannel implements ReleaseChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingReleaseChannel.class);

    @Override
    public void dispatch(ReleaseOrder order, List<ReleaseOrderItem> items) {
        log.info("[RELEASE_CHANNEL][TODO: 接真实 WMS/监管门户，Q9/Q10 未定] "
                        + "orderNo={} vin={} custodianId={} channel={} items={}",
                order.getOrderNo(), order.getVin(), order.getCustodianId(),
                order.getDeliveryChannel(), items);
    }
}
