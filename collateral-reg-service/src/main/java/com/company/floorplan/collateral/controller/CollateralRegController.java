package com.company.floorplan.collateral.controller;

import com.company.floorplan.collateral.model.Registration;
import com.company.floorplan.collateral.model.RegistrationItem;
import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.service.CollateralRegistrationService;
import com.company.floorplan.common.contract.CollateralClient;
import com.company.floorplan.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 担保权属权威源接口。
 *
 * <p><b>契约对齐</b>：{@code GET /registration} 直接返回
 * {@link CollateralClient.RegistrationVO}（<b>不包 ApiResponse</b>），
 * 路径 / 入参 / 字段名与只读契约 CollateralClient 完全一致，
 * 否则 core 侧 Feign 解码失败会走 NONE fallback 拒付。</p>
 *
 * <p><b>装配</b>：本类与各 bean 均不加 stereotype 注解，由启动类 {@code @Import} 显式注册，
 * 避免与 scanBasePackages 的组件扫描重复注册。</p>
 */
@RequestMapping("/api/collateral-reg")
@ResponseBody
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

    @PostMapping("/registration")
    public ApiResponse<Registration> register(@RequestBody RegisterRequest body) {
        return ApiResponse.ok(service.register(body.vin, body.ownerName, body.regNo, body.amount,
                parse(body.registeredAt), body.source), UUID.randomUUID().toString());
    }

    @PostMapping("/discharge")
    public ApiResponse<Registration> discharge(@RequestBody DischargeRequest body) {
        return ApiResponse.ok(service.discharge(body.vin, parse(body.dischargedAt), body.note),
                UUID.randomUUID().toString());
    }

    /** 三源快照导入：Q27 定源前只留痕，策略默认关闭不改当前权属。 */
    @PostMapping("/snapshot/import")
    public ApiResponse<Registration> importSnapshot(@RequestBody SnapshotImportRequest body) {
        return ApiResponse.ok(service.importSnapshot(body.vin, body.source,
                RegistrationStatus.fromCode(body.status), body.regNo, body.ownerName, body.amount,
                body.rawPayload, parse(body.capturedAt)), UUID.randomUUID().toString());
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
}
