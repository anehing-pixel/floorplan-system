package com.company.floorplan.infrastructure.lock;

import com.company.floorplan.domain.service.DistributedLock;
import com.company.floorplan.domain.service.LockHandle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 JVM 内的互斥锁 —— 默认实现，用于开发验证与单实例部署。
 *
 * <p><b>它的能力边界必须说清楚：只在同一个 JVM 内有效。</b>
 * 两个实例同时跑采集时，各自的内存里都能拿到锁，等于没有锁。
 * 所以多实例部署必须切到 {@code floorplan.lock.provider=redis}——
 * 而切换只是改一行配置，因为上层只认 {@link DistributedLock} 接口。</p>
 *
 * <p>为什么还要有它：本地没有 Redis 时，作业要能跑起来、幂等要能被验证。
 * 一个"必须装好中间件才能启动"的项目，面试时演示不了；
 * 一个"默认假装有锁"的实现，生产会出事。所以留了 default + 显式切换。</p>
 *
 * <h3>租约语义与 Redis 版一致</h3>
 * <ul>
 *   <li>{@code compute} 对同一 key 是原子的 —— 相当于 Redis 的单线程执行；</li>
 *   <li>过期时间到了即视为可抢占 —— 相当于 TTL；</li>
 *   <li>释放时比对 token —— 相当于 Lua 里的 get+del。</li>
 * </ul>
 * <p>三者对齐，才能保证"把 provider 从 memory 改成 redis"时行为不漂移。</p>
 */
@Component
@ConditionalOnProperty(name = "floorplan.lock.provider", havingValue = "memory", matchIfMissing = true)
public class InMemoryDistributedLock implements DistributedLock {

    /** 一条租约：持有者 + 到期时刻。 */
    private record Lease(String token, long expiresAtMillis) {
    }

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    @Override
    public Optional<LockHandle> tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expiresAt = now + ttl.toMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);

        leases.compute(key, (k, current) -> {
            if (current == null || current.expiresAtMillis() <= now) {
                acquired.set(true);
                return new Lease(token, expiresAt);
            }
            return current;   // 别人正持有且未过期 → 保持原样
        });

        if (!acquired.get()) {
            return Optional.empty();
        }
        return Optional.of(new LockHandle(key, token,
                () -> leases.computeIfPresent(key,
                        (k, current) -> current.token().equals(token) ? null : current)));
    }

    @Override
    public String provider() {
        return "memory";
    }
}
