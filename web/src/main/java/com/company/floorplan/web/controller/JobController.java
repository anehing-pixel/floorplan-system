package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.BatchApplicationService;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 批处理作业接口（日终）。
 * 作业幂等：interest_ledger(vin,biz_date) 与 batch_runs(job_name,biz_date) 唯一约束兜底。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final BatchApplicationService batchService;
    private final SupportRepository supportRepository;

    public JobController(BatchApplicationService batchService,
                         SupportRepository supportRepository) {
        this.batchService = batchService;
        this.supportRepository = supportRepository;
    }

    /** 日终计息（支持补算）。 —— 权限：系统管理员。 */
    @PostMapping("/interest")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> interest(
            @RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runDailyInterest(date), UUID.randomUUID().toString());
    }

    /** 库龄推进。 */
    @PostMapping("/aging")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> aging(@RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runAgingAdvance(date), UUID.randomUUID().toString());
    }

    /** 库龄递减计划生成。 */
    @PostMapping("/aging-reduction")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> agingReduction(@RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runAgingReduction(date), UUID.randomUUID().toString());
    }

    /** 结算窗口扫描：超窗口先"告警但允许"，超宽限期才升级为 OOT 嫌疑。 */
    @PostMapping("/settlement-scan")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> settlementScan(@RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runSettlementScan(date), UUID.randomUUID().toString());
    }

    /** 违约处置自动开单：扫描逾期(OOT)车辆自动开处置工单。 */
    @PostMapping("/disposal")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> defaultDisposal(@RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runDefaultDisposal(date), UUID.randomUUID().toString());
    }

    /** 额度年审扫描：对到期且未在进行中的额度自动启动年审（冻结占用）。 */
    @PostMapping("/annual-review-scan")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> annualReviewScan(@RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runAnnualReviewScan(date), UUID.randomUUID().toString());
    }

    /** 计息流水按季度归档：早于本季度首日的流水复制到历史表后删除。 */
    @PostMapping("/archive")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> archive(@RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        return ApiResponse.ok(batchService.runArchive(date), UUID.randomUUID().toString());
    }

    /**
     * 银行回款流水采集（BANK_FETCH）已迁移至 bank-service。
     * 网关按 {@code /api/jobs/bank-fetch} 精确路由到 bank-service；本类不再承载该端点，
     * 其余作业（计息/库龄/年审等核心账务）仍留在此处受本地事务与行锁保护。
     */

    /** 作业执行状态查询。 —— 权限：作业状态读（触发仍是 JOB_TRIGGER 写点）。 */
    @GetMapping("/status")
    @RequirePermission(Permission.JOB_READ)
    public ApiResponse<Map<String, Object>> status(@RequestParam String jobName,
                                                  @RequestParam(required = false) String bizDate) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobName", jobName);
        data.put("bizDate", date.toString());
        data.put("succeeded", supportRepository.batchSucceeded(jobName, date));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    /** 导入/更新营业日历（计息与结算窗口按营业日计算）。 */
    @PostMapping("/holidays")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> upsertHoliday(@RequestBody ApiRequests.HolidayRequest req) {
        LocalDate date = LocalDate.parse(req.bizDate);
        boolean businessDay = req.isBusinessDay == null ? false : req.isBusinessDay;
        supportRepository.upsertHoliday(date, businessDay, req.note);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizDate", date.toString());
        data.put("isBusinessDay", businessDay);
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }
}
