package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.ImportResult;
import com.company.floorplan.application.service.PurchaseOrderApplicationService;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.PurchaseOrder;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购订单接口（ADR-007 决策四）。
 *
 * <p><b>核心定位</b>：采购订单是<b>融资放款的前置主单</b>，不是"一个待解析的文本文件"。
 * 两条导入通道（API 直连 / Excel 批量）产出<b>同一份主单</b>，下游无感知。</p>
 *
 * <p><b>权限分级</b>：</p>
 * <ul>
 *   <li><b>读</b>（{@code GET}）：所有已登录用户可访问——风控与运营都要看采购订单台账；</li>
 *   <li><b>写</b>（{@code POST /import}）：仅<b>采购数据录入岗</b>——
 *       导入会真实建单并挂车辆台账，属资金相关写操作。
 *       录入岗<b>不能</b>审核自己导入的单（审核由采购审核岗承担，见
 *       {@link Permission#VEHICLE_VERIFY}），这是采购链路的职责分离。</li>
 * </ul>
 *
 * <p><b>两段式导入</b>：{@code mode=preview} 只校验不落库（返回行级错误明细），
 * {@code mode=commit} 才真正建单。Excel 通道<b>必须</b>走这两段——
 * 解析错一个账户号＝资金打错地方，代价不可逆。</p>
 */
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderApplicationService service;

    public PurchaseOrderController(PurchaseOrderApplicationService service) {
        this.service = service;
    }

    /**
     * Excel/CSV 批量导入（ADR-007 决策四）。
     *
     * @param file     采购清单文件（固定模板）
     * @param mode     {@code preview}（只校验）/ {@code commit}（确认落库）
     * @param dealerId 归属经销商
     * @param request  当前请求（用于解析审计操作人）
     * @return 预校验结果或落库结果
     */
    @PostMapping("/import")
    @RequirePermission(Permission.PURCHASE_IMPORT)
    public ResponseEntity<ApiResponse<Map<String, Object>>> importOrders(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "preview") String mode,
            @RequestParam("dealerId") Long dealerId,
            HttpServletRequest request) {

        String filename = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        try (InputStream in = file.getInputStream()) {
            if ("commit".equalsIgnoreCase(mode)) {
                Map<String, Object> result = service.commitImport(in, filename, dealerId,
                        AuditActor.resolve(request));
                return ResponseEntity.ok(ApiResponse.ok(result, request.getAttribute("traceId") + ""));
            }
            // preview：只校验不落库，返回行级错误明细
            ImportResult result = service.previewImport(in, filename, dealerId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", "preview");
            payload.put("totalRows", result.getTotalRows());
            payload.put("acceptedRows", result.getAccepted().size());
            payload.put("rejectedRows", result.getRejectedRows());
            payload.put("totalAmount", result.getTotalAmount());
            payload.put("fileHash", result.getFileHash());
            List<Object[]> errors = new ArrayList<>();
            for (ImportResult.RowError e : result.getErrors()) {
                errors.add(e.toArray());
            }
            payload.put("errors", errors);
            return ResponseEntity.ok(ApiResponse.ok(payload, request.getAttribute("traceId") + ""));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.fail("VALID_001", "读取上传文件失败：" + e.getMessage(),
                            request.getAttribute("traceId") + "", null));
        }
    }

    /**
     * 列出全部采购订单。
     */
    @GetMapping
    @RequirePermission(Permission.ADVANCE_READ)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest request) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PurchaseOrder p : service.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("orderNo", p.getOrderNo());
            m.put("dealerId", p.getDealerId());
            m.put("creditLineId", p.getCreditLineId());
            m.put("supplierId", p.getSupplierId());
            m.put("supplierType", p.getSupplierType() == null ? null : p.getSupplierType().name());
            m.put("supplierTypeLabel", p.getSupplierType() == null ? null : p.getSupplierType().getLabel());
            // 打款模式是派生值：前端直接展示，不可作为可编辑字段
            m.put("payeeMode", p.derivePayeeMode().name());
            m.put("payeeModeLabel", p.derivePayeeMode().getLabel());
            m.put("payeeAccountSnapshot", p.getPayeeAccountSnapshot());
            m.put("totalAmount", p.getTotalAmount());
            m.put("vehicleCount", p.getVehicleCount());
            m.put("purchasedAt", p.getPurchasedAt());
            m.put("evidenceNo", p.getEvidenceNo());
            m.put("evidenceAttachmentId", p.getEvidenceAttachmentId());
            m.put("status", p.getStatus() == null ? null : p.getStatus().name());
            m.put("statusLabel", p.getStatus() == null ? null : p.getStatus().getLabel());
            m.put("ingestChannel", p.getIngestChannel() == null ? null : p.getIngestChannel().name());
            m.put("ingestChannelLabel", p.getIngestChannel() == null ? null : p.getIngestChannel().getLabel());
            m.put("createdAt", p.getCreatedAt());
            rows.add(m);
        }
        return ResponseEntity.ok(ApiResponse.ok(rows, request.getAttribute("traceId") + ""));
    }

    /**
     * 查询采购订单主单（含车辆清单与收款账户掩码）。
     *
     * @param orderNo 采购订单号
     */
    @GetMapping("/{orderNo}")
    @RequirePermission(Permission.ADVANCE_READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOne(@PathVariable String orderNo,
                                                                   HttpServletRequest request) {
        PurchaseOrder p = service.getByOrderNo(orderNo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("orderNo", p.getOrderNo());
        m.put("dealerId", p.getDealerId());
        m.put("creditLineId", p.getCreditLineId());
        m.put("supplierId", p.getSupplierId());
        m.put("supplierType", p.getSupplierType() == null ? null : p.getSupplierType().name());
        m.put("supplierTypeLabel", p.getSupplierType() == null ? null : p.getSupplierType().getLabel());
        m.put("payeeMode", p.derivePayeeMode().name());
        m.put("payeeModeLabel", p.derivePayeeMode().getLabel());
        m.put("payeeAccountSnapshot", p.getPayeeAccountSnapshot());
        m.put("totalAmount", p.getTotalAmount());
        m.put("vehicleCount", p.getVehicleCount());
        m.put("purchasedAt", p.getPurchasedAt());
        m.put("evidenceNo", p.getEvidenceNo());
        m.put("evidenceAttachmentId", p.getEvidenceAttachmentId());
        m.put("status", p.getStatus() == null ? null : p.getStatus().name());
        m.put("statusLabel", p.getStatus() == null ? null : p.getStatus().getLabel());
        m.put("ingestChannel", p.getIngestChannel() == null ? null : p.getIngestChannel().name());
        m.put("importBatchId", p.getImportBatchId());
        m.put("createdAt", p.getCreatedAt());

        List<Map<String, Object>> vehicles = new ArrayList<>();
        p.getVehicles().forEach(v -> {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("vin", v.getVin());
            vm.put("model", v.getModel());
            vm.put("modelYear", v.getModelYear());
            vm.put("color", v.getColor());
            vm.put("invoiceAmount", v.getInvoiceAmount());
            vm.put("status", v.getStatus() == null ? null : v.getStatus().name());
            vm.put("purchaseChannel", v.getPurchaseChannel());
            vm.put("supplierName", v.getSupplierName());
            vehicles.add(vm);
        });
        m.put("vehicles", vehicles);
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /**
     * 放款前校验链预览（ADR-007 决策五）。
     *
     * <p><b>两套校验链按打款模式分叉</b>：{@code DIRECT_PAY} 要三方委托付款协议与
     * 授权上限；{@code SELF_PAY} 要库存实物在库与独立抵押率。
     * 把协议校验设成全局硬校验，会把"库存抵押补充周转"这条业务直接堵死。</p>
     *
     * @param orderNo 采购订单号
     */
    @GetMapping("/{orderNo}/validation")
    @RequirePermission(Permission.ADVANCE_READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> validation(@PathVariable String orderNo,
                                                                        HttpServletRequest request) {
        Map<String, Object> result = service.validationPreview(orderNo);
        return ResponseEntity.ok(ApiResponse.ok(result, request.getAttribute("traceId") + ""));
    }
}
