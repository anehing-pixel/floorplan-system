package com.company.floorplan.bank.controller;

import com.company.floorplan.application.service.BankFetchApplicationService;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * 银行回款流水采集接口（从 web 的 JobController 摘出）。
 *
 * <p>路径、参数、权限注解、响应结构与原 {@code POST /api/jobs/bank-fetch} 完全一致，
 * 调用方（定时任务/前端）无需改动，只是请求被路由到本服务。
 * 原 JobController 里其余作业（计息/库龄/年审等）留在 web，不属于银行上下文。</p>
 *
 * <p>幂等不在作业级而在行级：{@code uk_payment_txn (account_ref, transaction_id)}，
 * 重复拉取的 {@code duplicatedCount > 0} 是健康信号。</p>
 */
@RestController
@RequestMapping("/api/jobs")
public class BankFetchController {

    private final BankFetchApplicationService bankFetchService;

    public BankFetchController(BankFetchApplicationService bankFetchService) {
        this.bankFetchService = bankFetchService;
    }

    /**
     * 银行回款流水采集（BANK_FETCH）。
     *
     * @param bizDate     业务日（拉取窗口右端），默认今天
     * @param accountRefs 账户引用码，逗号分隔；为空则取 floorplan.bank-fetch.accounts
     */
    @PostMapping("/bank-fetch")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> bankFetch(
            @RequestParam(required = false) String bizDate,
            @RequestParam(required = false) String accountRefs) {
        LocalDate date = bizDate == null || bizDate.isBlank() ? LocalDate.now() : LocalDate.parse(bizDate);
        List<String> accounts = accountRefs == null || accountRefs.isBlank()
                ? List.of()
                : Arrays.asList(accountRefs.split(","));
        return ApiResponse.ok(bankFetchService.fetch(date, accounts, "system"),
                UUID.randomUUID().toString());
    }
}
