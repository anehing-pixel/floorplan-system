package com.company.floorplan.common.contract;

import com.company.floorplan.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 授信额度"可用量"查询契约（W0-5 新增，按额度线主键查）。
 *
 * <p><b>与已有 {@link CreditLineClient} 的区别</b>：那个按 {@code dealerId} 查、签名返回裸
 * {@code BigDecimal}，且它声明的路径 {@code GET /api/credit-lines/available} <b>全仓没有任何实现</b>
 * （credit-service 只有 {@code POST /api/credit-lines}、{@code /{id}}、{@code /{id}/freeze}、
 * {@code /{id}/unfreeze}），目前只被 bank 的本地存根 {@code LoggingCreditLineClient} 实现着——
 * 也就是说它是一个"有接口、无对端"的死契约。本类不重复那个错误：路径与返回形状都按
 * W1-CREDIT <b>将要实现</b> 的样式冻结，并在契约文档里要求把死契约一并处置。</p>
 *
 * <p><b>谁消费</b>：W1-BANK 回款匹配后按额度线取"回补前可用额度"快照，
 * 以及 W1-CREDIT 自身在回补作业里做前后对账。</p>
 *
 * <p><b>返回形状为什么是 {@code ApiResponse<Long>} 而不是裸 {@code long}</b>：
 * 全系统 REST 响应统一 {@link ApiResponse} 包装（AGENTS §10），credit-service 的既有端点也都这样。
 * 若契约声明裸 {@code long}，Feign 会把 {@code {code,message,data}} 当成数字反序列化，
 * 直接抛解码异常——这正是"契约形状对不上实现"的另一种死法。</p>
 *
 * <p><b>{@code contextId} 为什么必须写</b>：本接口与 {@link CreditLineClient} 的 {@code name}
 * 同为 {@code "credit-service"}。OpenFeign 默认用 {@code name} 当上下文 id，两个同名 Feign client
 * 会让 web 启动时报 {@code FeignClientSpecification} bean 注册冲突。显式 contextId 才是正解。</p>
 *
 * <p><b>金额口径</b>：{@code data} 是整数日元（BIGINT），不是 BigDecimal、不是"万"。
 * 未结清借据占用、冻结额都不在这里再算一遍——可用量的唯一权威口径属于 credit-service，
 * 本契约只读它算好的数。</p>
 */
@FeignClient(name = "credit-service", contextId = "creditLineQueryClient", path = "/api/credit-lines",
        // url 非空即直连（本地不启 Nacos 时用），空串回退服务名 lb://，生产配置零影响
        url = "${floorplan.clients.credit.url:}")
public interface CreditLineQueryClient {

    /**
     * 按额度线主键查可用额度。
     *
     * <p>待 W1-CREDIT 实现的端点：{@code GET /api/credit-lines/{id}/available}，
     * 成功返回 {@code {"code":"OK","data":<long>}}；额度线不存在返回 HTTP 404 + {@code NOT_FOUND_001}。
     * 冻结中的额度线仍返回数值（可用量可以是 0），不许用异常表达"冻结"这种业务状态。</p>
     */
    @GetMapping("/{id}/available")
    ApiResponse<Long> getAvailable(@PathVariable("id") Long id);
}
