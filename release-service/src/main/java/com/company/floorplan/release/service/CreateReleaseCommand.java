package com.company.floorplan.release.service;

import java.time.Instant;
import java.util.Map;

/**
 * 创建出库指令的入参（应用服务命令对象）。
 *
 * @param redemptionId    赎车申请 ID（跨上下文逻辑引用）
 * @param dealerId        经销商 ID
 * @param vin             车架号
 * @param certificateNo   车辆合格证号（不是法务局登记证明书）
 * @param custodianId     主接收方 ID（默认放车线）；各释放项可在 custodianByItem 覆盖
 * @param releaseItems    释放内容，逗号分隔：VEHICLE,CERTIFICATE,KEY
 * @param validUntil      有效期；为空时按设计稿默认 7 个日历日
 * @param operator        操作人（来自令牌，服务端解析，不信任请求体）
 * @param deliveryChannel 下发通道；为空时默认 MANUAL
 * @param custodianByItem 按释放项覆盖接收方；未覆盖的项继承 custodianId
 */
public record CreateReleaseCommand(
        Long redemptionId,
        Long dealerId,
        String vin,
        String certificateNo,
        Long custodianId,
        String releaseItems,
        Instant validUntil,
        String operator,
        String deliveryChannel,
        Map<String, Long> custodianByItem) {
}
