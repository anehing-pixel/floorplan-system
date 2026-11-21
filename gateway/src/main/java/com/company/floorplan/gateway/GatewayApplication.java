package com.company.floorplan.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 统一网关启动入口。
 *
 * <p><b>本阶段职责边界</b>：只做「路由转发 + 请求头透传」，不做 JWT 验签。
 * 鉴权仍由各下游服务自带的 common JwtAuthInterceptor 完成——它们已验证生效，
 * 且保留鉴权能让服务在脱离网关直连时不裸奔（零信任）。等全部端点迁移完成、
 * 服务鉴权链定型后，再把验签上收到网关（写一个 WebFlux GlobalFilter）。</p>
 *
 * <p>因此本模块<b>不依赖 jjwt / application 层</b>，非常轻量。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
