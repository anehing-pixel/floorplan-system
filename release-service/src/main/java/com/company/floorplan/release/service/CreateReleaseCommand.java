package com.company.floorplan.release.service;

import java.time.Instant;

/**
 * 创建出库指令的入参（应用服务命令对象）。
 *
 * @param redemptionId   赎车申请 ID（跨上下文逻辑引用）
 * @param dealerId       经销商 ID
 * @param vin            车架号
 * @param certificateNo  车辆合格证号（不是法务局登记证明书）
 * @param custodianId    接收方 ID（Q1 未定，可为空）
 * @param releaseItems   释放内容，逗号分隔：VEHICLE,CERTIFICATE,KEY
 * @param validUntil     有效期；为空时按设计稿默认 7 个日历日
 * @param operator       操作人（来自令牌，服务端解析，不信任请求体）
 * @param deliveryChannel 下发通道；为空时默认 MANUAL
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
        String deliveryChannel) {
}
