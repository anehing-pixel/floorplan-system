package com.company.floorplan.credit.controller;

import com.company.floorplan.application.service.RatingConfigApplicationService;
import com.company.floorplan.common.config.AuditActor;
import com.company.floorplan.credit.dto.ApiRequests;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.RatingConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 授信评级分级口径管理接口（后台动态配置）。路径与响应结构与原 web 模块一致。
 *
 * <p>口径变更即时生效：下一次垫款建账即按新口径计算执行利率。</p>
 */
@RestController
@RequestMapping("/api/rating-configs")
public class RatingConfigController {

    private final RatingConfigApplicationService service;

    public RatingConfigController(RatingConfigApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RatingConfig>> list() {
        return ApiResponse.ok(service.list(), UUID.randomUUID().toString());
    }

    @PutMapping("/{grade}")
    @RequirePermission(Permission.CONFIG_MAINTAIN)
    public ApiResponse<RatingConfig> upsert(
            @PathVariable String grade,
            @RequestBody ApiRequests.RatingConfigRequest req,
            HttpServletRequest request) {
        RatingConfig config = service.upsert(grade, req.gradeName, req.rateSurcharge,
                req.allowed, req.remark, AuditActor.resolve(request));
        return ApiResponse.ok(config, UUID.randomUUID().toString());
    }
}
