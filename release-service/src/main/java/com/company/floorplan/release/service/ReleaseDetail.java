package com.company.floorplan.release.service;

import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;
import com.company.floorplan.release.model.ReleaseStateHistory;

import java.util.List;

/** 指令详情：主表 + 释放项明细 + 状态历史，供 GET 一次返回。 */
public record ReleaseDetail(
        ReleaseOrder order,
        List<ReleaseOrderItem> items,
        List<ReleaseStateHistory> history) {
}
