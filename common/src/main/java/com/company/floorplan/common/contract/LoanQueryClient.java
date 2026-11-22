package com.company.floorplan.common.contract;

import com.company.floorplan.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 借据查询契约（W0-5 新增）。实现方 = loan-service，计划调用方 = bank-service（W1-BANK 回款匹配）。
 *
 * <p><b>为什么需要它</b>：回款匹配必须先知道"这台车有没有未结清借据、欠多少、归属哪个经销商"。
 * 现在 bank 用的是 {@code LoggingLoanLookupClient} 存根（一律按无债务处理），所以匹配永远不成功，
 * 后续解押与额度回补全部空转（PROGRESS §8.1 D1）。</p>
 *
 * <p><b>本契约不是新发明的形状</b>：{@code GET /api/loans/by-vin/{vin}} 在 loan-service 已实现
 * （{@code LoanController.getByVin}），响应结构已由 {@code docs/接口契约-V16借据.md} §2 冻结。
 * 本接口按<b>实现方现状</b>声明：外层 {@link ApiResponse} 包装、{@code code="OK"}、
 * 未命中返回 <b>HTTP 404</b>（{@code NOT_FOUND_001}）。之前拆分方案里"新定义一个裸 DTO 返回值"
 * 的写法会让 Feign 把 {@code {code,message,data}} 直接反序列化成全 null 的对象——
 * 正是 AGENTS §12 记过的"契约路径/形状对不上实现 → 死代码"。</p>
 *
 * <p><b>禁止绕道</b>：bank 不许直连 {@code loans} 表。共库让这件事现在"能跑"，
 * 物理拆库那天链路当场断（AGENTS §12 反模式）。跨服务取数只走本契约或事件。</p>
 *
 * <p><b>装配条件</b>：调用方服务必须自己声明
 * {@code @EnableFeignClients(basePackages = "com.company.floorplan.common.contract")}，
 * 否则接口不会生成 bean。本项目有过"契约写了但没人能注入"的先例。</p>
 */
@FeignClient(name = "loan-service", path = "/api/loans",
        // url 非空即直连（本地不启 Nacos 时用），空串回退服务名 lb://，生产配置零影响
        url = "${floorplan.clients.loan.url:}")
public interface LoanQueryClient {

    /**
     * 按 VIN 查该 VIN 当前<b>未结清</b>借据（一个 VIN 唯一指向一笔，口径见 V16 契约 §3）。
     *
     * <p>调用方必须区分两种"没拿到借据"：</p>
     * <ul>
     *   <li>HTTP 404 + {@code code=NOT_FOUND_001} → 业务事实：这台车没有在贷借据，
     *       匹配走人工/忽略分支；</li>
     *   <li>连接失败 / 5xx → 故障：不许当成"没有在贷"，必须让本次匹配失败并可重试
     *       （否则服务抖动一次就把一笔真实回款误判成无关流水）。</li>
     * </ul>
     *
     * <p>W0 不提供 fallback：把上面两类压成同一个返回值属于业务口径，未定源前不擅自实现
     * （AGENTS 铁律 3）。由 W1-BANK 连同降级语义一起定。</p>
     */
    @GetMapping("/by-vin/{vin}")
    ApiResponse<LoanSnapshotDto> getByVin(@PathVariable("vin") String vin);
}
