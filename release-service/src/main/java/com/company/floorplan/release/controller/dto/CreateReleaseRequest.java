package com.company.floorplan.release.controller.dto;

import java.time.Instant;
import java.util.Map;

/**
 * POST /api/releases 请求体。操作人不从请求体取，由服务端从令牌解析。
 *
 * @param custodianByItem 按释放项覆盖接收方，键为 {@code VEHICLE / CERTIFICATE / KEY}
 *                        （大小写不敏感），值为 {@code custodians.id}。
 *                        未指定的项继承 {@code custodianId}；不传则全部继承。
 *                        用于表达「放车给经销商库、放证给凭证保管点」两条线。
 */
public record CreateReleaseRequest(
        Long redemptionId,
        Long dealerId,
        String vin,
        String certificateNo,
        Long custodianId,
        String releaseItems,
        Instant validUntil,
        String deliveryChannel,
        Map<String, Long> custodianByItem) {
}
