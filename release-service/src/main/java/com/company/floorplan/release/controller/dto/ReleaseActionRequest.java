package com.company.floorplan.release.controller.dto;

/** 状态推进动作的可选请求体：只带原因说明；操作人由服务端从令牌解析。 */
public record ReleaseActionRequest(String reason) {
}
