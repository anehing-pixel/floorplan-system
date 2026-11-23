package com.company.floorplan.application.service;

import com.company.floorplan.domain.repository.SupportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SOD 拦截留痕（D10 §4「实现要求」）。
 *
 * <p><b>为什么单独一个类</b>：D10 要求每次 SOD 触发都写
 * {@code audit_events(SOD_VIOLATION_BLOCKED)}，而触发 SOD 的那次业务调用紧接着就抛
 * {@code AUTH_003} ⟹ 整个事务回滚。审计写在同一个事务里等于没写——
 * 「谁试图自己批自己」这条恰好是唯一需要留下的记录，被拒的动作没有别的痕迹。
 * {@code REQUIRES_NEW} 让这个审计独立提交，业务事务怎么回滚都不影响它。</p>
 *
 * <p>本类必须被<b>另一个</b> bean 调用（Spring 代理不拦截同类自调用），
 * 这也是它不与各用例服务合并的原因。</p>
 */
@Service
public class UsedSodAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsedSodAuditRecorder.class);

    /** D10 §4 定源的审计动作名（≤32 字符，受 {@code audit_events.action VARCHAR(32)} 约束）。 */
    public static final String ACTION_SOD_BLOCKED = "SOD_VIOLATION_BLOCKED";

    private static final String TARGET_TYPE = "USED_SOD_PAIR";

    private final SupportRepository supportRepository;

    public UsedSodAuditRecorder(SupportRepository supportRepository) {
        this.supportRepository = supportRepository;
    }

    /**
     * 记一次被拦下的同人操作。
     *
     * @param pair     SOD 对编号与名称，如 {@code D-5 政策维护↔政策审批}
     * @param actor    试图执行的人（令牌身份）
     * @param holder   已经持有互斥角色的那个人（用于审计回答「本该换谁」）
     * @param targetId 业务对象标识（VIN / 政策 id）
     * @param traceId  链路号，可空
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blocked(String pair, String actor, String holder, String targetId, String traceId) {
        supportRepository.writeAudit(actor, ACTION_SOD_BLOCKED, TARGET_TYPE, targetId,
                "pair=" + pair + " holder=" + holder,
                "pair=" + pair + " actor=" + actor + " holder=" + holder + " blocked=AUTH_003",
                traceId);
        log.warn("[USED SOD] 拦截 pair={} target={} actor={} holder={}", pair, targetId, actor, holder);
    }
}
