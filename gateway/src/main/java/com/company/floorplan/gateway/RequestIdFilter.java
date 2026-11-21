package com.company.floorplan.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求 ID 透传：下游 ApiResponse.traceId 与审计链都依赖它。
 *
 * <p>调用方带 X-Request-Id 就沿用（便于跨网关链路追踪），否则网关生成一个。
 * 通过 mutate 写回下游请求头——Gateway 默认透传所有 header，这里只负责"补一个"。</p>
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        final String id = requestId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(HEADER, id)
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
