package com.company.floorplan.common.contract;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 授信额度查询契约（Wave 0 冻结，实现方不许改签名）。
 *
 * <p>供 core / collateral-reg 在放款和登记前查可用额度。注意：额度"占用/释放"的写入
 * 仍在 core 的行锁事务内，本契约只读可用量——把写入挪到跨服务调用会破坏最强一致性边界。</p>
 */
@FeignClient(name = "credit-service", path = "/api/credit-lines",
        // W0-5 本地直连：url 非空即直连该地址（本地不启 Nacos 时用），空串回退服务名 lb://，生产零影响。
        url = "${floorplan.clients.credit.url:}",
        fallback = CreditLineClient.Fallback.class)
public interface CreditLineClient {

    /** 查经销商当前可用额度 */
    @GetMapping("/available")
    BigDecimal getAvailable(@RequestParam("dealerId") Long dealerId);

    /** 降级：返回 0，调用方据此拒绝，绝不因查询失败而放行 */
    class Fallback implements CreditLineClient {
        @Override
        public BigDecimal getAvailable(Long dealerId) {
            return BigDecimal.ZERO;
        }
    }
}
