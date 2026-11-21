package com.company.floorplan.domain.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 分布式互斥锁（<b>不是分布式事务锁</b>）。
 *
 * <p>它只保证"同一时刻只有一个执行者"，不提供原子提交，也没有 2PC。
 * 名字容易误导，所以在这里说清楚：<b>锁保效率，约束保正确。</b></p>
 *
 * <h3>什么时候才需要它</h3>
 * <p>判据只有一句：<b>临界区里有没有数据库管不住的东西？</b></p>
 * <table border="1">
 *   <tr><th>临界区内容</th><th>该用什么</th></tr>
 *   <tr><td>单行状态变更</td><td>DB 行锁 / 条件更新（锁与数据同事务，切换不回退）</td></tr>
 *   <tr><td>多行多表、同库</td><td>事务 + 固定顺序行锁</td></tr>
 *   <tr><td><b>外部调用</b>（拉银行文件、发短信、调支付）</td><td><b>本接口</b> + 幂等键</td></tr>
 * </table>
 *
 * <p>项目里额度、车辆、结清三处继续用 {@code SELECT ... FOR UPDATE}：
 * 锁和数据在同一个事务里，主从切换时整体回滚，不会出现"锁没了但写成功"。
 * 引入 Redis 锁反而会制造"锁与数据不同源"的新窗口。</p>
 *
 * <h3>实现必须遵守的语义</h3>
 * <ol>
 *   <li><b>不阻塞</b>：拿不到立刻返回 {@code empty}，绝不等待。批处理拿不到锁就该退出，
 *       而不是占着 Tomcat 线程自旋。</li>
 *   <li><b>不自动续期</b>：TTL 必须大于外部调用超时。自动续期会掩盖"外部调用卡住了"这个事实，
 *       让一个已经该失败的作业一直活着。</li>
 *   <li><b>释放要比对持有者</b>：只能释放自己拿的锁，不能删掉别人的（见实现里的 Lua）。</li>
 *   <li><b>锁服务不可用时应 fail-closed</b>：拿不到锁就当作"没抢到"，
 *       而不是"当作没锁直接跑"。对资金系统，宁可少跑一次，不可重复入账。</li>
 * </ol>
 */
public interface DistributedLock {

    /**
     * 尝试获取锁，立即返回。
     *
     * @param key 锁键，规范：{@code lock:{域}:{动作}:{业务标识}}，
     *            例如 {@code lock:bank:fetch:ACC-001:2026-11-08}
     * @param ttl 租约时长。必须大于临界区内最长外部调用的超时时间
     * @return 拿到则返回句柄（用 try-with-resources 或 finally 释放），否则 empty
     */
    Optional<LockHandle> tryLock(String key, Duration ttl);

    /** 提供者标识（memory / redis），用于启动日志与健康检查。 */
    String provider();
}
