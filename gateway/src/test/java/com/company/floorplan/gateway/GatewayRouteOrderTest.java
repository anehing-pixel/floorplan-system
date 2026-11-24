package com.company.floorplan.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关路由顺序回归测试（不启动网关）。
 *
 * <p><b>它防的是哪一类事故</b>：Spring Cloud Gateway 按 YAML 顺序**首个命中即返回**。
 * 一条宽路由（如 {@code /api/dealers/**} → credit-service）如果排在精确路由前面，
 * 会把实际属于别的服务的端点整个吞掉——2026-11 实测
 * {@code POST /api/dealers/{id}/bank-accounts}（web 的 SupplierController）经网关 404，
 * 而直连 web 是通的。这类事故在编译期完全看不见，只能靠路由级断言。</p>
 *
 * <p><b>为什么不启动网关做集成测试</b>：本测试只解析 {@code application.yml}，
 * 按 Gateway 的口径（YAML 顺序 + Ant path 首个匹配）算出一条请求会落到哪个 route id。
 * 它不依赖 Nacos、不依赖下游进程，{@code mvn test} 就能跑；真正的端到端可达性
 * 另由 {@code smoke-test.py} ㉑ 的 bank-accounts 断言覆盖。</p>
 *
 * <p><b>新增/调整路由时必须同步改这里</b>：把「路径 → 期望 route id」写进断言，
 * 尤其是精确路由与宽路由的先后顺序。改错顺序会让本测试直接红，而不是等线上 404。</p>
 */
class GatewayRouteOrderTest {

    private record Route(String id, List<String> pathPatterns) {
    }

    @Test
    void specificRoutesWinOverBroadRoutes() throws IOException {
        List<Route> routes = loadRoutes();
        assertTrue(routes.size() > 5, "路由表解析失败，只读到 " + routes.size() + " 条");

        // 本次修复的核心：web 的经销商结算账户端点必须先于 /api/dealers/** 的 credit 宽路由
        assertEquals("core-dealer-bank-accounts", routeFor(routes, "/api/dealers/123/bank-accounts"),
                "POST /api/dealers/{id}/bank-accounts 必须归 core，不能被 credit 宽路由吞掉");
        assertTrue(indexOf(routes, "core-dealer-bank-accounts") < indexOf(routes, "credit"),
                "core-dealer-bank-accounts 必须排在 credit 路由之前（Gateway 首个命中）");

        // 其它已知的精确路由不能被后面的宽路由抢走
        assertEquals("vehicle-lien-check", routeFor(routes, "/api/vehicles/VIN123/lien-check"));
        assertEquals("bank-fetch", routeFor(routes, "/api/jobs/bank-fetch"));
        assertEquals("bank", routeFor(routes, "/api/payments/matches"));
        assertEquals("collateral-reg", routeFor(routes, "/api/collateral-reg/registration"));
        assertEquals("risk", routeFor(routes, "/api/collateral/ledger"));
        assertEquals("loan", routeFor(routes, "/api/loans/by-vin/VIN123"));
        assertEquals("release", routeFor(routes, "/api/releases/1"));
        assertEquals("recon", routeFor(routes, "/api/recon/diffs"));
        assertEquals("auth", routeFor(routes, "/api/auth/login"));
        assertEquals("console-static", routeFor(routes, "/index.html"));

        // 宽路由仍要覆盖它该覆盖的路径
        assertEquals("credit", routeFor(routes, "/api/dealers/123"));
        assertEquals("credit", routeFor(routes, "/api/dealers/123/vehicles"));
        assertEquals("core", routeFor(routes, "/api/vehicles/VIN123"));
        assertEquals("core", routeFor(routes, "/api/jobs/interest"));
    }

    /** 按 Gateway 语义：YAML 顺序 + Ant path，首个命中即返回。 */
    private static String routeFor(List<Route> routes, String path) {
        AntPathMatcher matcher = new AntPathMatcher();
        for (Route route : routes) {
            for (String pattern : route.pathPatterns()) {
                if (matcher.match(pattern, path)) {
                    return route.id();
                }
            }
        }
        return null;
    }

    private static int indexOf(List<Route> routes, String id) {
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 {@code application.yml} 解析路由顺序与 Path 谓词。
     *
     * <p>YamlPropertySourceLoader 会把列表拍平成
     * {@code spring.cloud.gateway.routes[i].predicates[j] = "Path=/a,/b"}，
     * 这里按这个稳定形状读取；不绑定 GatewayProperties，避免受 predicate shorthand
     * 的解析时机影响。</p>
     */
    private static List<Route> loadRoutes() throws IOException {
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("gateway", new ClassPathResource("application.yml"))
                .stream()
                .filter(s -> s.getProperty("spring.cloud.gateway.routes[0].id") != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("application.yml 里没有 gateway.routes"));

        List<Route> routes = new ArrayList<>();
        for (int i = 0; ; i++) {
            Object id = source.getProperty("spring.cloud.gateway.routes[" + i + "].id");
            if (id == null) {
                break;
            }
            List<String> patterns = new ArrayList<>();
            for (int j = 0; ; j++) {
                Object predicate = source.getProperty(
                        "spring.cloud.gateway.routes[" + i + "].predicates[" + j + "]");
                if (predicate == null) {
                    break;
                }
                String text = String.valueOf(predicate).trim();
                // 本仓库只用 Path 谓词；Method 等谓词就算出现也不影响本测试的路径匹配口径
                if (text.startsWith("Path=")) {
                    for (String p : text.substring("Path=".length()).split(",")) {
                        if (!p.isBlank()) {
                            patterns.add(p.trim());
                        }
                    }
                }
            }
            routes.add(new Route(String.valueOf(id), List.copyOf(patterns)));
        }
        return routes;
    }
}
