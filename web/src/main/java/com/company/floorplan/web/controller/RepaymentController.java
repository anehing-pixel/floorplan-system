package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.RepaymentApplicationService;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 还款结清接口（业务闭环第 ⑦ 环节）。
 *
 * <p><b>一次调用完成三件事</b>：还款核销 + 解除担保 + 额度回补。
 * 调用方只需传"该台车应收总额（本金 + 利息）"，
 * 服务端会校验金额是否与应收一致（不一致抛 REPAY_001，并回传 expected 明细）。</p>
 *
 * <p><b>金额来源</b>：调用前可先 {@code GET /api/vehicles/{vin}}
 * 读取本金与累计利息，相加后作为 settlementAmount。</p>
 */
@RestController
@RequestMapping("/api/repayments")
public class RepaymentController {

    private final RepaymentApplicationService service;
    private final SupportRepository supportRepository;

    public RepaymentController(RepaymentApplicationService service, SupportRepository supportRepository) {
        this.service = service;
        this.supportRepository = supportRepository;
    }

    @PostMapping
    /** 还款核销：结算岗。 */
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> settle(
            @RequestBody ApiRequests.RepaymentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String key = idempotencyKey != null ? idempotencyKey
                : (req.idempotencyKey != null ? req.idempotencyKey : UUID.randomUUID().toString());
        String actor = AuditActor.resolve(request);
        Long operatorId = supportRepository.findUserIdByUsername(actor);
        Map<String, Object> data = service.settle(req.vin, req.settlementAmount, operatorId, key, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /**
     * 质押中提前赎回：车辆尚未售出时经销商提前还清，物权回归、车留在库里可再次融资。
     *
     * <p><b>与 {@code POST /api/repayments} 的区别</b>：结清要求车辆已售出（SOLD_PENDING）
     * 或已逾期，且结清后车辆为 SETTLED（车已离场，终态）；
     * 而赎回针对还在库的车（IN_STOCK），结束后车辆为 REDEEMED，
     * <b>可直接再次申请垫款</b>——同一台车循环融资的正常入口。</p>
     */
    @PostMapping("/redeem")
    /** 提前赎回核销：结算岗。 */
    @RequirePermission(Permission.REPAYMENT_EXECUTE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeem(
            @RequestBody ApiRequests.RepaymentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String key = idempotencyKey != null ? idempotencyKey
                : (req.idempotencyKey != null ? req.idempotencyKey : UUID.randomUUID().toString());
        String actor = AuditActor.resolve(request);
        Long operatorId = supportRepository.findUserIdByUsername(actor);
        Map<String, Object> data = service.redeem(req.vin, req.settlementAmount, operatorId, key, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }

    /**
     * 提前部分还款（P1：区别于整台结清）。
     *
     * <p>只扣减未偿本金，<b>不解除担保、不回补额度</b>；利息照常累计直到整台结清。
     * 典型场景：车商资金宽裕时先还一部分本金，降低后续日息基数。</p>
     */
    @PostMapping("/partial")
    /** 部分还款核销：催收岗。 */
    @RequirePermission(Permission.CURTAILMENT_EXECUTE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> partialRepay(
            @RequestBody ApiRequests.PartialRepaymentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String key = idempotencyKey != null ? idempotencyKey
                : (req.idempotencyKey != null ? req.idempotencyKey : UUID.randomUUID().toString());
        Map<String, Object> data = service.partialRepay(req.vin,
                req.principalAmount == null ? 0L : req.principalAmount, key, AuditActor.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, UUID.randomUUID().toString()));
    }
}