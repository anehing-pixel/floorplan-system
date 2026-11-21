package com.company.floorplan.infrastructure.lock;

import com.company.floorplan.domain.service.DistributedLock;
import com.company.floorplan.domain.service.LockHandle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis 的互斥锁（{@code SET key token NX PX ttl}）。
 *
 * <p>启用：{@code floorplan.lock.provider=redis}。</p>
 *
 * <h3>三个必须做对的地方</h3>
 * <ol>
 *   <li><b>value 必须是每次唯一的 token</b>。若用固定值（如 "1"），
 *       释放时就无法区分"这是我加的锁"还是"别人的锁"，会把别人的锁删掉。</li>
 *   <li><b>释放必须走 Lua 比对</b>。{@code GET} 再 {@code DEL} 是两步，
 *       中间可能过期并被别人抢到——于是删掉的是别人的锁。
 *       Redis 执行 Lua 是原子的，比对与删除不会被打断。</li>
 *   <li><b>不续期</b>。看门狗续期会掩盖"外部调用卡住了"这个事实：
 *       一个本该失败的作业会被无限延长，锁也就永远不释放。</li>
 * </ol>
 *
 * <h3>它保证不了什么（必须知道）</h3>
 * <p><b>主从切换会丢锁</b>。{@code SET NX} 在主节点成功，未同步到从节点时主节点宕机，
 * 从节点升主后不知道这把锁存在——于是同一时刻有两个执行者。
 * 这不是实现缺陷，而是"租约式锁"的固有性质，换 etcd 也一样。</p>
 *
 * <p>所以本锁只用在<b>外部副作用可幂等</b>的场景（银行流水采集、通知派发）：
 * 丢锁最多导致重复拉一次，而重复由数据库唯一约束兜住。
 * <b>额度、车辆、结清三处绝不使用本锁</b>——那里的互斥必须与数据同事务提交，
 * 用 {@code SELECT ... FOR UPDATE}。</p>
 *
 * <h3>fail-closed</h3>
 * <p>Redis 不可达时抛异常中止作业，而不是"当作没锁直接跑"。
 * 理由：采集重复执行会导致流水重复入账（虽然唯一约束能兜，但会污染批次统计），
 * 通知重复执行会真的发两条短信。宁可少跑一次，不可多跑一次——
 * 少跑可以补，多跑收不回。</p>
 */
@Component
@ConditionalOnProperty(name = "floorplan.lock.provider", havingValue = "redis")
public class RedisDistributedLock implements DistributedLock {

    /**
     * 释放脚本：只有 value 与自己持有的 token 一致时才删除。
     *
     * <p>返回 1 表示真的删了，0 表示"锁已不属于我"（过期后被别人拿走）。
     * 后者不是错误，是租约到期的正常结果。</p>
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "  return redis.call('del', KEYS[1]) "
                    + "else "
                    + "  return 0 "
                    + "end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<LockHandle> tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        } catch (RuntimeException e) {
            // fail-closed：锁服务不可用 → 作业失败，而不是无锁裸奔。
            throw new IllegalStateException(
                    "分布式锁服务不可用，作业中止（fail-closed）：key=" + key, e);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new LockHandle(key, token,
                () -> redis.execute(RELEASE_SCRIPT, List.of(key), token)));
    }

    @Override
    public String provider() {
        return "redis";
    }
}
