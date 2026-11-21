package com.company.floorplan.release.controller.dto;

import java.time.Instant;

/** POST /api/releases 请求体。操作人不从请求体取，由服务端从令牌解析。 */
public record CreateReleaseRequest(
        Long redemptionId,
        Long dealerId,
        String vin,
        String certificateNo,
        Long custodianId,
        String releaseItems,
        Instant validUntil,
        String deliveryChannel) {
}
