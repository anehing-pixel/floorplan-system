package com.company.floorplan.bank.controller.dto;

/** 人工绑定请求：故意不含 operator 字段——操作人只从令牌解析。 */
public record ManualMatchRequest(String vin) {
}
