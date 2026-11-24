package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.SupplierApplicationService;
import com.company.floorplan.domain.model.BankAccount;
import com.company.floorplan.domain.model.Supplier;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游主数据与收款账户接口（ADR-007 决策三 / 决策七）。
 *
 * <p><b>权限分级</b>：</p>
 * <ul>
 *   <li><b>读</b>：所有已登录用户可访问；</li>
 *   <li><b>写</b>（新增上游 / 新增账户 / 改状态）：仅 {@code ADMIN}。
 *       账户是资金去向，改错就是打错钱。</li>
 * </ul>
 *
 * <p><b>敏感数据</b>：账号明文<b>只在录入请求里存在</b>，落库即加密；
 * 对外一律只返掩码。本控制器不提供任何返回明文账号的接口。</p>
 */
@RestController
@RequestMapping("/api")
public class SupplierController {

    private final SupplierApplicationService service;

    public SupplierController(SupplierApplicationService service) {
        this.service = service;
    }

    /* ---------------- 上游 ---------------- */

    /** 列出上游主数据。 —— 权限：供应商主数据读。 */
    @GetMapping("/suppliers")
    @RequirePermission(Permission.SUPPLIER_READ)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSuppliers(
            @RequestParam(value = "supplierType", required = false) String supplierType,
            HttpServletRequest request) {
        List<Supplier> list = (supplierType == null || supplierType.isBlank())
                ? service.listSuppliers() : service.listSuppliersByType(supplierType);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Supplier s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("supplierCode", s.getSupplierCode());
            m.put("name", s.getName());
            m.put("supplierType", s.getSupplierType() == null ? null : s.getSupplierType().name());
            m.put("supplierTypeLabel", s.getSupplierType() == null ? null : s.getSupplierType().getLabel());
            // 打款模式是派生值，前端只展示不可编辑
            m.put("payeeMode", s.derivePayeeMode().name());
            m.put("payeeModeLabel", s.derivePayeeMode().getLabel());
            m.put("status", s.getStatus() == null ? null : s.getStatus().name());
            m.put("riskGrade", s.getRiskGrade());
            m.put("guaranteeAbility", s.getGuaranteeAbility() == null ? null : s.getGuaranteeAbility().name());
            m.put("guaranteeAbilityLabel", s.getGuaranteeAbility() == null ? null : s.getGuaranteeAbility().getLabel());
            m.put("contactPerson", s.getContactPerson());
            m.put("contactPhone", s.getContactPhone());
            m.put("remark", s.getRemark());
            rows.add(m);
        }
        return ResponseEntity.ok(ApiResponse.ok(rows, request.getAttribute("traceId") + ""));
    }

    /** 新增上游（平台管理域：上游准入是管理决策，非导入动作）。 */
    @PostMapping("/suppliers")
    @RequirePermission(Permission.SUPPLIER_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSupplier(
            @RequestBody ApiRequests.SupplierCreateRequest body, HttpServletRequest request) {
        Supplier s = service.createSupplier(body.supplierCode, body.name, body.supplierType,
                body.riskGrade, body.guaranteeAbility, body.contactPerson,
                body.contactPhone, body.remark);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("supplierCode", s.getSupplierCode());
        m.put("name", s.getName());
        m.put("supplierType", s.getSupplierType().name());
        m.put("payeeMode", s.derivePayeeMode().name());
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /** 更新上游（平台管理域）。 */
    @PutMapping("/suppliers/{id}")
    @RequirePermission(Permission.SUPPLIER_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSupplier(
            @PathVariable Long id, @RequestBody ApiRequests.SupplierUpdateRequest body,
            HttpServletRequest request) {
        Supplier s = service.updateSupplier(id, body.name, body.supplierType, body.riskGrade,
                body.guaranteeAbility, body.contactPerson, body.contactPhone, body.remark);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("supplierType", s.getSupplierType().name());
        m.put("payeeMode", s.derivePayeeMode().name());
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /** 变更上游状态（平台管理域）。 */
    @PutMapping("/suppliers/{id}/status")
    @RequirePermission(Permission.SUPPLIER_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> changeSupplierStatus(
            @PathVariable Long id, @RequestBody ApiRequests.StatusChangeRequest body,
            HttpServletRequest request) {
        int n = service.changeSupplierStatus(id, body.status);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("updated", n);
        m.put("status", body.status);
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /* ---------------- 收款账户 ---------------- */

    /** 列出全部收款账户（只返掩码）。 —— 权限：账户列表读（比供应商主数据更窄）。 */
    @GetMapping("/bank-accounts")
    @RequirePermission(Permission.BANK_ACCOUNT_READ)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAccounts(HttpServletRequest request) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BankAccount a : service.listAccounts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("ownerType", a.getOwnerType().name());
            m.put("ownerTypeLabel", a.getOwnerType() == BankAccount.OwnerType.SUPPLIER ? "上游账户" : "经销商结算账户");
            m.put("supplierId", a.getSupplierId());
            m.put("dealerId", a.getDealerId());
            m.put("bankName", a.getBankName());
            m.put("accountHolder", a.getAccountHolder());
            // 对外只返掩码，永不返明文
            m.put("accountNoMask", a.getAccountNoMask());
            m.put("accountRef", a.getAccountRef());
            m.put("status", a.getStatus() == null ? null : a.getStatus().name());
            m.put("statusLabel", a.getStatus() == null ? null : a.getStatus().getLabel());
            m.put("effectiveFrom", a.getEffectiveFrom());
            m.put("effectiveTo", a.getEffectiveTo());
            m.put("reviewer", a.getReviewer());
            rows.add(m);
        }
        return ResponseEntity.ok(ApiResponse.ok(rows, request.getAttribute("traceId") + ""));
    }

    /** 新增上游收款账户（采购录入岗：账户是导入流程的配套动作）。 */
    @PostMapping("/suppliers/{id}/accounts")
    @RequirePermission(Permission.BANK_ACCOUNT_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSupplierAccount(
            @PathVariable Long id, @RequestBody ApiRequests.BankAccountCreateRequest body,
            HttpServletRequest request) {
        BankAccount a = service.createSupplierAccount(id, body.bankName, body.accountHolder,
                body.accountNo, body.accountRef, parseDate(body.effectiveFrom),
                AuditActor.resolve(request));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("accountRef", a.getAccountRef());
        m.put("accountNoMask", a.getAccountNoMask());
        m.put("status", a.getStatus().name());
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /** 新增经销商结算账户（采购录入岗，场景 C：库存抵押补充周转）。 */
    @PostMapping("/dealers/{id}/bank-accounts")
    @RequirePermission(Permission.BANK_ACCOUNT_MAINTAIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> createDealerAccount(
            @PathVariable Long id, @RequestBody ApiRequests.BankAccountCreateRequest body,
            HttpServletRequest request) {
        BankAccount a = service.createDealerAccount(id, body.bankName, body.accountHolder,
                body.accountNo, body.accountRef, parseDate(body.effectiveFrom),
                AuditActor.resolve(request));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("accountRef", a.getAccountRef());
        m.put("accountNoMask", a.getAccountNoMask());
        m.put("status", a.getStatus().name());
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /** 变更账户状态（冻结 / 关闭 / 恢复）——采购审核岗：账户有效性复核。 */
    @PutMapping("/bank-accounts/{id}/status")
    @RequirePermission(Permission.BANK_ACCOUNT_CONTROL)
    public ResponseEntity<ApiResponse<Map<String, Object>>> changeAccountStatus(
            @PathVariable Long id, @RequestBody ApiRequests.StatusChangeRequest body,
            HttpServletRequest request) {
        int n = service.changeAccountStatus(id, body.status);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("updated", n);
        m.put("status", body.status);
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
