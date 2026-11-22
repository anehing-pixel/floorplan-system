package com.company.floorplan.collateral.controller;

import com.company.floorplan.collateral.model.Registration;
import com.company.floorplan.collateral.model.RegistrationItem;
import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;
import com.company.floorplan.collateral.service.CollateralRegistrationService;
import com.company.floorplan.common.contract.CollateralClient;
import com.company.floorplan.common.dto.ApiResponse;
import com.company.floorplan.common.security.RequirePermission;
import com.company.floorplan.domain.model.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 担保权属权威源接口。
 *
 * <p><b>契约对齐</b>：{@code GET /registration} 直接返回
 * {@link CollateralClient.RegistrationVO}（<b>不包 ApiResponse</b>），
 * 路径 / 入参 / 字段名与只读契约 CollateralClient 完全一致，
 * 否则 core 侧 Feign 解码失败会走 NONE fallback 拒付。</p>
 *
 * <p><b>ADR-Q27</b>：{@code GET /registration} 是放款门禁查询，
 * 由 service 直读 V10 登记批次表（唯一权威源），不读 V18 投影表。
 * 写端点只做投影/留痕与对账体检，不改变门禁结论。</p>
 *
 * <p><b>#29 权限</b>：所有写端点均挂 {@code @RequirePermission}，
 * 且必须使用 {@code com.company.floorplan.common.security.RequirePermission}——
 * 本服务装配的是 common 包的 {@code JwtAuthInterceptor}，
 * 用 web 包的同名注解不会被拦截器识别，等于没加。</p>
 *
 * <p><b>装配（2026-11-22 修正）</b>：本类用 {@code @RestController} 交给
 * {@code scanBasePackages} 组件扫描注册，与 risk / release 等服务保持一致；
 * 其余 service / repository / strategy 不加 stereotype，仍由启动类 {@code @Import} 显式装配。
 * 早期版本本类也不加 stereotype、只靠 @Import，结果 Spring MVC 未把它识别为 handler，
 * 请求被当作静态资源返回 500（冒烟未覆盖该端点，故一直未被发现）——已修正。</p>
 */
@RestController
@RequestMapping("/api/collateral-reg")
public class CollateralRegController {

    private final CollateralRegistrationService service;

    public CollateralRegController(CollateralRegistrationService service) {
        this.service = service;
    }

    /**
     * Feign 命中点：必须与 CollateralClient#getRegistration 的签名/返回结构一致。
     * 不包 ApiResponse——契约返回类型就是 RegistrationVO。
     */
    @GetMapping("/registration")
    public CollateralClient.RegistrationVO getRegistration(@RequestParam("vin") String vin) {
        return toVo(service.getCurrent(vin));
    }

    /** 权属流水（运营/审计用，非契约端点）。 */
    @GetMapping("/registration/history")
    public ApiResponse<List<RegistrationItem>> history(@RequestParam("vin") String vin) {
        return ApiResponse.ok(service.getHistory(vin), UUID.randomUUID().toString());
    }

    /** 登记补录：写 V18 投影与流水留痕，不参与门禁判定（ADR-Q27）。 */
    @PostMapping("/registration")
    @RequirePermission(Permission.COLLATERAL_MAINTAIN)
    public ApiResponse<Registration> register(@RequestBody RegisterRequest body) {
        return ApiResponse.ok(service.register(body.vin, body.ownerName, body.regNo, body.amount,
                parse(body.registeredAt), body.source), UUID.randomUUID().toString());
    }

    /** 抹消补录：写 V18 投影与流水留痕，不参与门禁判定（ADR-Q27）。 */
    @PostMapping("/discharge")
    @RequirePermission(Permission.COLLATERAL_MAINTAIN)
    public ApiResponse<Registration> discharge(@RequestBody DischargeRequest body) {
        return ApiResponse.ok(service.discharge(body.vin, parse(body.dischargedAt), body.note),
                UUID.randomUUID().toString());
    }

    /** 单条三源快照导入（保留原契约，内部委托批量实现）。 */
    @PostMapping("/snapshot/import")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Registration> importSnapshot(@RequestBody SnapshotImportRequest body) {
        return ApiResponse.ok(service.importSnapshot(body.vin, body.source,
                RegistrationStatus.fromCode(body.status), body.regNo, body.ownerName, body.amount,
                body.rawPayload, parse(body.capturedAt)), UUID.randomUUID().toString());
    }

    /**
     * <b>#31 / ADR-Q27</b>：一次提交 1~3 条源快照，批量落库后只裁决一次。
     * 裁决结论仅作对账体检，不 adopt 覆盖任何权属表。
     */
    @PostMapping("/snapshot/import-batch")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> importSnapshotBatch(@RequestBody SnapshotBatchRequest body) {
        List<SnapshotImportRequest> sources = body == null ? null : body.sources;
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("三源快照导入至少 1 条");
        }
        List<SourceSnapshot> snapshots = new ArrayList<>();
        for (SnapshotImportRequest r : sources) {
            SourceSnapshot s = new SourceSnapshot();
            s.setVin(r.vin);
            s.setSource(r.source);
            s.setStatus(RegistrationStatus.fromCode(r.status));
            s.setRegNo(r.regNo);
            s.setOwnerName(r.ownerName);
            s.setAmount(r.amount);
            s.setRawPayload(r.rawPayload);
            s.setCapturedAt(parse(r.capturedAt));
            snapshots.add(s);
        }
        return ApiResponse.ok(service.importSnapshots(snapshots), UUID.randomUUID().toString());
    }

    /** 领域对象 -> 契约 VO：只暴露门禁需要的四个字段。 */
    private static CollateralClient.RegistrationVO toVo(Registration r) {
        CollateralClient.RegistrationVO vo = new CollateralClient.RegistrationVO();
        vo.setStatus(r.getStatus() == null ? "NONE" : r.getStatus().name());
        vo.setRegNo(r.getRegNo());
        vo.setOwnerName(r.getOwnerName());
        vo.setRegisteredAt(r.getRegisteredAt() == null ? null : r.getRegisteredAt().toString());
        return vo;
    }

    private static LocalDateTime parse(String text) {
        return text == null || text.isBlank() ? null : LocalDateTime.parse(text);
    }

    /** 登记请求。source 省略时按 MANUAL 记流水。 */
    public static class RegisterRequest {
        public String vin;
        public String ownerName;
        public String regNo;
        public Long amount;
        public String registeredAt;
        public String source;
    }

    public static class DischargeRequest {
        public String vin;
        public String dischargedAt;
        public String note;
    }

    public static class SnapshotImportRequest {
        public String vin;
        public String source;
        public String status;
        public String regNo;
        public String ownerName;
        public Long amount;
        public String rawPayload;
        public String capturedAt;
    }

    /** 批量三源快照导入请求：一次提交 1~3 条，落库后只裁决一次。 */
    public static class SnapshotBatchRequest {
        public List<SnapshotImportRequest> sources;
    }
}
