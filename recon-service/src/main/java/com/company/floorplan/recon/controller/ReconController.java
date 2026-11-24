package com.company.floorplan.recon.controller;

import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.recon.model.ReconDiff;
import com.company.floorplan.recon.service.ReconApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对拍清算接口。
 *
 * <p>写触发（POST /run）按项目一贯口径挂 {@code @RequirePermission(JOB_TRIGGER)}：
 * 对拍会写自己的批次与差异表，属于作业触发动作。
 * 查询（GET /diffs）不挂权限点——沿用 Permission 枚举与 web 控制器的统一约定：
 * "读接口对所有已登录用户开放"，风控/运营都需要看差异明细。</p>
 */
@RestController
@RequestMapping("/api/recon")
public class ReconController {

    private final ReconApplicationService reconService;

    public ReconController(ReconApplicationService reconService) {
        this.reconService = reconService;
    }

    /**
     * 触发一次对拍。
     *
     * @param bizDate 业务日（可选，默认今天）
     * @return 批次号 + 执行规则数 + 差异条数
     */
    @PostMapping("/run")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> run(
            @RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? null : LocalDate.parse(bizDate.trim());
        return ApiResponse.ok(reconService.run(date, "system"), UUID.randomUUID().toString());
    }

    /**
     * 查询差异明细。
     *
     * @param batchNo 批次号（可选）
     * @param status  闭环状态（可选：OPEN/CONFIRMED/IGNORED/RESOLVED）
     */
    @GetMapping("/diffs")
    @RequirePermission(Permission.RECON_READ)
    public ApiResponse<List<ReconDiff>> diffs(
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(reconService.findDiffs(batchNo, status), UUID.randomUUID().toString());
    }
}
