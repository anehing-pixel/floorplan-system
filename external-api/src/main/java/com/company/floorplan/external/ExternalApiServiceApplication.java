package com.company.floorplan.external;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * external-api（DMZ 外部接口层）启动入口 —— W-U0 交付 ②，仅骨架。
 *
 * <p><b>职责边界</b>（D00 §4 / D02 §3.1 / ADR009、ADR-U-05/06/07）：
 * 入站 mTLS 终结 → 验签 → 时间窗 → nonce → 限流 → 幂等 → 落 {@code integration.inbound_messages}
 * → 返 202；出站拉 {@code integration.outbound_tasks} → 按 partner 签名 → 超时/退避/熔断
 * → 写 {@code call_logs}；密钥解密与版本管理也在此进程。</p>
 *
 * <p><b>四条「不许」</b>：不连 {@code public} schema、不跑任何业务规则、
 * 不接收内网主动推送、不直接改任何业务状态。</p>
 *
 * <p><b>为什么只有 {@code @EnableScheduling} 而没有 {@code @Import} outbox 两个 bean</b>：
 * 2026-11-22 D13 §3 G9 / §5.1-A8 反转了原稿。给 DMZ 装配 {@code JdbcOutboxPublisher}
 * 等于给它业务写权限，直接破 INV-2；{@code EventDispatcher} 同理。
 * DMZ 侧不 publish 任何 outbox 事件，核心侧只读 {@code integration.inbound_messages}
 * 队列并在业务服务内发冻结名事件（D12 §3 W-U3 事件行）。
 * 但出站任务与回执兜底要按时跑，故调度开关保留。</p>
 *
 * <p><b>骨架 ≠ 能力</b>：本类只满足 D02 §3.3 五件套里「启动类与装配」一部分，
 * 其余四件（拆能力 / 迁数据 / 通链路 / 有断言）归 W-U3，验收以 D11 A5 组为准。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan.external")
@EnableScheduling
@EnableDiscoveryClient
public class ExternalApiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExternalApiServiceApplication.class, args);
    }
}
