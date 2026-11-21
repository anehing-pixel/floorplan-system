package com.company.floorplan.domain.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 锁句柄 —— 持有期间代表"我是当前执行者"，释放后作废。
 *
 * <p><b>为什么不是简单地 {@code void unlock()}</b>：
 * 释放必须带 <i>持有者身份</i>（token）。设想 A 的租约到期、B 拿到锁，
 * 此时 A 才执行到 finally 里的 unlock——如果 unlock 只按 key 删除，
 * 它会删掉 <b>B 的锁</b>，于是 C 又能进来，互斥彻底失效。
 * 所以释放动作必须携带 token，由实现去做比对（Redis 用 Lua 保证原子）。</p>
 *
 * <p>{@link #release()} 可重复调用，只有第一次生效。
 * 这让"try-with-resources + finally 双保险"不会互相干扰。</p>
 */
public final class LockHandle implements AutoCloseable {

    private final String key;

    /** 持有者标识：每次加锁唯一，用于释放时比对，防止误删他人的锁。 */
    private final String token;

    private final Runnable releaser;

    private final Instant acquiredAt = Instant.now();

    private final AtomicBoolean released = new AtomicBoolean(false);

    public LockHandle(String key, String token, Runnable releaser) {
        this.key = key;
        this.token = token;
        this.releaser = releaser;
    }

    /**
     * 释放锁。重复调用无副作用。
     *
     * <p>释放失败（如 Redis 已不可达）不应打断业务流程——锁有 TTL，
     * 最坏情况是等它自然过期。把释放异常抛出去，只会让已经成功的业务变成失败。</p>
     */
    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            releaser.run();
        } catch (RuntimeException ignored) {
            // 主动忽略：TTL 是最终兜底。此处不吞业务异常，只吞释放异常。
        }
    }

    @Override
    public void close() {
        release();
    }

    public String getKey() {
        return key;
    }

    public String getToken() {
        return token;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public boolean isReleased() {
        return released.get();
    }
}
