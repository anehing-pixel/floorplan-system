package com.company.floorplan.web.controller;

import com.company.floorplan.application.dto.CertificateDraft;
import com.company.floorplan.application.dto.RecordConditionReportCmd;
import com.company.floorplan.application.dto.RegisterUsedVehicleCmd;
import com.company.floorplan.application.service.UsedVehicleMasterApplicationService;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.config.RequestLoggingFilter;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.dto.UsedRequests;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * USED 车辆主数据 / 車検証内部端点（USED R2，D07 §7.2 的 UC-R2-1〜R2-3、R2-5、R2-6）。
 *
 * <p><b>路径</b>：沿用 R1 的裁决方式（D07 §7.0 冲突定案 ②）——用未被网关占用的新前缀
 * {@code /api/used-vehicles/**}，由 core 兜底路由接住，不动 {@code gateway/…/application.yml}
 * （AGENTS §7.1 共享禁区）。D07 §7.2 字面写的是 {@code /api/vehicles/{vin}/used-attributes}，
 * 而 {@code /api/vehicles/**} 已被 LEGACY {@code VehicleController} 占走：把 USED 子资源挂进去，
 * 轻则同名方法互相覆盖，重则冒烟打进 LEGACY 那条链路拿到「看着对」的响应。</p>
 *
 * <p><b>端点只对着真实存在的用例开</b>：本类六个方法逐一对应
 * {@link UsedVehicleMasterApplicationService} 的六个公开方法，没有一个「先挂上路径、业务以后再写」。
 * UC-R2-4（USED 导入模板解析）、R2-6 的批量重算也没有先开空端点——
 * 这正是 AGENTS §12「表建了就算功能有了」在接口层的同型错误。</p>
 *
 * <p><b>操作人一律从令牌取</b>（AGENTS §10）：请求体里出现的 {@code operator} 字段被忽略，
 * 换发的第二人 {@code coSignedBy} 例外——它是业务上的「另一个人」，不是当前调用者身份，
 * 因此允许来自请求体，但用例层会拒绝它与 {@code AuditActor} 同人（AUTH_003）。</p>
 */
@RestController
@RequestMapping("/api/used-vehicles")
public class UsedVehicleController {

    private final UsedVehicleMasterApplicationService usedVehicleService;

    public UsedVehicleController(UsedVehicleMasterApplicationService usedVehicleService) {
        this.usedVehicleService = usedVehicleService;
    }

    /**
     * USED 建账（UC-R2-1）。
     *
     * <p>重复 VIN 由用例回 {@code VEHICLE_003}（409），不在这里伪装成幂等重放：
     * {@code vehicle_units} 没有 idempotency_key 列，缺了它就无法判断第二次请求
     * 是不是同一份数据（已登记为待裁决项）。</p>
     */
    @PostMapping
    @RequirePermission(Permission.USED_VEHICLE_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(
            @RequestBody UsedRequests.UsedVehicleRegisterRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        RegisterUsedVehicleCmd cmd = new RegisterUsedVehicleCmd(
                req.vin, req.dealerId, req.vehicleType, req.purchaseChannel, req.brand, req.model,
                req.modelYear, req.color, req.invoiceAmount, req.supplierName, req.registrationPlate,
                req.firstRegisteredOn, req.inspectionExpiryDate, req.inspectionRemainingMonths,
                req.mileageKm, req.mileageSource, req.mileageCapturedOn, req.repairHistory,
                req.repairHistoryDesc, req.accidentHistoryFlag, req.auctionGrade, req.auctionLotId,
                req.saleChannel, req.warehouseCustodianCode, req.binLocation);
        Map<String, Object> data = withIdempotencyHeader(
                usedVehicleService.registerUsedVehicle(cmd, AuditActor.resolve(request)), idemHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /** 单车完整视图：主数据 + 在场证 + 证链 + 车况票 + 瑕疵结论 + 未决差异（只读，不顺手重算）。 */
    @GetMapping("/{vin}")
    @RequirePermission(Permission.USED_VEHICLE_READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(
            @PathVariable("vin") String vin, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(usedVehicleService.detail(vin), trace(request)));
    }

    /**
     * 车况票登记（UC-R2-2）＋票侧里程勾稽（UC-R2-3）。
     *
     * <p>路径上的 vin 会覆盖请求体里的 vin：票报错了 VIN 时应当以「谁调用的、挂到哪台车」为准，
     * 那由端点路径决定；否则一次批量推送里改一个字段就能把票挪到别的车上。</p>
     */
    @PostMapping("/{vin}/condition-reports")
    @RequirePermission(Permission.USED_VEHICLE_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordConditionReport(
            @PathVariable("vin") String vin,
            @RequestBody UsedRequests.ConditionReportRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        RecordConditionReportCmd cmd = new RecordConditionReportCmd(
                vin, req.reportNo, req.reportType, req.inspectedOn, req.inspector, req.structureCondition,
                req.repairHistory, req.accidentHistoryFlag, req.modificationFlag, req.interiorCondition,
                req.exteriorCondition, req.odometerKm, req.inspectionExpiryDate, req.recycleTicketFlag,
                req.compulsoryInsuranceExpiry, req.automobileTaxStatus, req.photoPaths, req.attachmentPath,
                req.attachmentDigest);
        Map<String, Object> data = withIdempotencyHeader(
                usedVehicleService.recordConditionReport(cmd, AuditActor.resolve(request)), idemHeader);
        return statusOf(data, HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 里程重新判定（UC-R2-3/6 的手动入口）。
     *
     * <p>为什么单独开一个端点而不是「读的时候顺手算」：判定结果要落主数据、要开差异、要写审计，
     * 挂在 GET 上就等于每次查询都是一次并发写点（R6 账实比对踩过）。
     * 外部推送（拍卖场/WMS）走各自的入站切片，落地后调同一个用例方法。</p>
     */
    @PostMapping("/{vin}/mileage/reconcile")
    @RequirePermission(Permission.USED_VEHICLE_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcileMileage(
            @PathVariable("vin") String vin,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        Map<String, Object> data = withIdempotencyHeader(
                usedVehicleService.reconcileMileage(vin, AuditActor.resolve(request)), idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /** 收证登记（UC-R2-5 前半）：一车一证；有瑕疵照实入账并在响应里给出阻断标记。 */
    @PostMapping("/{vin}/certificates")
    @RequirePermission(Permission.CERTIFICATE_RECORD)
    public ResponseEntity<ApiResponse<Map<String, Object>>> attachCertificate(
            @PathVariable("vin") String vin,
            @RequestBody UsedRequests.CertificateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        Map<String, Object> data = withIdempotencyHeader(
                usedVehicleService.attachCertificate(vin, toDraft(req), AuditActor.resolve(request)),
                idemHeader);
        return statusOf(data, HttpStatus.CREATED).body(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 换发車検証（UC-R2-5 后半）：旧证置 REISSUED、新证以 {@code predecessor_id} 成链。
     *
     * <p>用 {@code CERTIFICATE_REISSUE_APPROVE} 而不是收证那个权限点：两者构成 SOD 对 D-6
     * （押印未解先放证＝担保物失控），同一人可以既收证又换证就等于单人改写权属。</p>
     */
    @PostMapping("/{vin}/certificates/rebind")
    @RequirePermission(Permission.CERTIFICATE_REISSUE_APPROVE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> rebindCertificate(
            @PathVariable("vin") String vin,
            @RequestBody UsedRequests.CertificateRebindRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            HttpServletRequest request) {
        String operatorA = AuditActor.resolve(request);
        Map<String, Object> data = withIdempotencyHeader(
                usedVehicleService.rebindCertificate(vin, toDraft(req), operatorA, req.coSignedBy),
                idemHeader);
        return ResponseEntity.ok(ApiResponse.ok(data, trace(request)));
    }

    /**
     * 请求体 → 用例命令。
     *
     * <p>{@code draft.vin} 传<b>报文值</b>而不是路径值：用例里那条「路径 VIN 与报文 VIN 不一致 →
     * CERT_003」（D06 G-D1）靠的就是这两个值不同源。在这里把路径值填进去，等于把报文里的
     * vin 字段直接丢掉，那条防线永远不可能触发——冒烟 R2 的「错 vin 收证 → 409 CERT_003」
     * 实测就是这么被吃掉的（回了 201）。</p>
     *
     * <p>报文不带 vin 时传 null，用例把空值当作「未声明」跳过一致性校验，操作对象仍由路径决定。</p>
     */
    private static CertificateDraft toDraft(UsedRequests.CertificateRequest req) {
        return new CertificateDraft(req.certificateNo, req.vin, req.registrationPlate, req.ownerName,
                req.ownerAddress, req.userName, req.userAddress, req.firstRegisteredOn,
                req.inspectionExpiryDate, req.issueDate, req.securityType, req.mortgageFlag,
                req.encumbranceStatus, req.holderCustodianCode, req.holderType, req.custodyLocation,
                req.evidencePath, req.evidenceDigest, req.checkSource, req.reason);
    }

    /** 响应体里的 traceId：优先用请求上下文里已有的（RequestLoggingFilter 生成），没有就自己造一个。 */
    private static String trace(HttpServletRequest request) {
        Object existing = request.getAttribute(RequestLoggingFilter.ATTR_TRACE_ID);
        return existing == null ? UUID.randomUUID().toString() : String.valueOf(existing);
    }

    /** 把客户端的 Idempotency-Key 原样回显（真实判定走服务端推导的自然键，见用例注释）。 */
    private static Map<String, Object> withIdempotencyHeader(Map<String, Object> data, String idemHeader) {
        data.put("idempotencyKeyHeader", idemHeader);
        return data;
    }

    /** 新建返 201，命中幂等返 200——冒烟据此区分「第一次」与「重推」，不必翻响应体。 */
    private static ResponseEntity.BodyBuilder statusOf(Map<String, Object> data, HttpStatus created) {
        return ResponseEntity.status(Boolean.TRUE.equals(data.get("idempotent")) ? HttpStatus.OK : created);
    }
}
