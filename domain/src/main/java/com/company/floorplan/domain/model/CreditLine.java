package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

import java.util.Map;

/**
 * 额度聚合根 —— 循环授信的核心。
 *
 * <p><b>业务定位</b>：库存融资是<b>循环授信</b>而非一次性贷款。
 * 金融公司给车商核定一个授信上限，车商在这个额度内反复
 * "垫款占用 → 售出还款 → 额度回补"：卖掉一台还一台，额度随之释放，
 * 可以再去收下一台。这正对应业务闭环的第 ① 与第 ⑦ 环节。</p>
 *
 * <p><b>守护的不变量</b>：
 * <pre>
 *   usedAmount + frozenAmount &lt;= approvedLimit
 * </pre>
 * 即"已占用 + 已冻结"永远不得超过"核定上限"。</p>
 *
 * <p><b>并发要求（关键）</b>：本对象<b>禁止无锁的读-改-写</b>。
 * 调用方必须通过 {@link com.company.floorplan.domain.repository.CreditLineRepository#findByIdForUpdate(Long)}
 * 以数据库行锁（{@code SELECT ... FOR UPDATE}）加载后再修改，
 * 否则并发垫款会导致额度超额——这是金融系统最不能犯的错。</p>
 *
 * <p><b>金额约定</b>：所有金额为整数日元（long）。</p>
 */
public class CreditLine {

    /** 主键。 */
    private Long id;

    /** 所属经销商 ID。 */
    private Long dealerId;

    /** 核定授信上限（整数日元）。由客户经理发起、另一人审批（发起与执行分离的内控要求）。 */
    private long approvedLimit;

    /** 已占用金额（整数日元）。垫款时增加、还款时减少。 */
    private long usedAmount;

    /** 已冻结金额（整数日元）。风控冻结时使用，冻结部分不可再占用。 */
    private long frozenAmount;

    /**
     * 额度状态：{@code ACTIVE} / {@code FROZEN} / {@code CLOSED}。
     * 非 ACTIVE 时禁止新的占用（由 {@link #occupy(long)} 拦截，抛 {@code CREDIT_002}）。
     */
    private String status;

    /** 乐观锁版本号。 */
    private int version;

    /**
     * B-5：授信发起人（自然人账号）。
     *
     * <p>生产启用 {@code floorplan.credit.strict-sod=true} 后，
     * 复核人不得与发起人为同一人（发起与执行分离）。落库是为了让
     * {@code completeCreditLine} 重新从数据库加载额度时仍能拿到发起人，
     * 不能只放在内存里。</p>
     */
    private String initiatedBy;

    public CreditLine() {
    }

    /**
     * 计算可用余额。
     *
     * @return 可用额度 = 核定上限 − 已占用 − 已冻结
     */
    public long available() {
        return approvedLimit - usedAmount - frozenAmount;
    }

    /**
     * 占用额度（垫款时调用，业务闭环第 ③ 环节的一部分）。
     *
     * @param amount 占用金额（正整数日元）
     * @throws BusinessException 额度非 ACTIVE 抛 {@code CREDIT_002}；
     *                           金额非正抛 {@code VALID_002}；
     *                           超出可用余额抛 {@code CREDIT_001}（details 带可用额与需求额）
     */
    public void occupy(long amount) {
        // T1-4（M12）：授信发起后、复核完成前的额度绝不可用于放款
        if ("PENDING_APPROVAL".equals(status)) {
            throw new BusinessException(ErrorCode.CREDIT_003);
        }
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.CREDIT_002);
        }
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "占用金额必须为正整数日元");
        }
        if (amount > available()) {
            throw new BusinessException(ErrorCode.CREDIT_001, Map.of(
                    "available", available(), "required", amount));
        }
        this.usedAmount += amount;
    }

    /**
     * 释放额度（还款结清时回补调用，业务闭环第 ⑦ 环节的一部分）。
     *
     * @param amount 释放金额（正整数日元）
     * @throws BusinessException 金额非正抛 {@code VALID_002}
     * @implNote 实释放金额取 min(amount, usedAmount)，避免因数据异常导致已占用变负。
     */
    public void release(long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "释放金额必须为正整数日元");
        }
        long actual = Math.min(amount, usedAmount);
        this.usedAmount -= actual;
    }

    /**
     * 冻结额度（风控处置时调用）。
     *
     * @param amount 冻结金额（正整数日元）
     * @throws BusinessException 金额非正抛 {@code VALID_002}
     * @implNote 冻结量不超过当前可用额；冻结后状态置 FROZEN，
     *           此后不能再发生新的占用（由 {@link #occupy(long)} 拦截）。
     */
    public void freeze(long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "冻结金额必须为正整数日元");
        }
        long free = available();
        long actual = Math.min(amount, free);
        this.frozenAmount += actual;
        this.status = "FROZEN";
    }

    /**
     * T1-4（M12 授信审批链）：授信复核通过，把待审批额度激活为可用。
     *
     * @throws BusinessException 非 PENDING_APPROVAL 状态抛 {@code CREDIT_003}
     */
    public void approve() {
        if (!"PENDING_APPROVAL".equals(status)) {
            throw new BusinessException(ErrorCode.CREDIT_003, Map.of(
                    "currentStatus", String.valueOf(status), "reason", "仅待审批额度可审批通过"));
        }
        this.status = "ACTIVE";
    }

    /**
     * T1-4（M12）：授信复核不通过，额度关闭（不可再用）。
     */
    public void rejectApproval() {
        if (!"PENDING_APPROVAL".equals(status)) {
            throw new BusinessException(ErrorCode.CREDIT_003, Map.of(
                    "currentStatus", String.valueOf(status), "reason", "仅待审批额度可驳回"));
        }
        this.status = "CLOSED";
    }

    /**
     * T1-1（B3 处置冻结额度）：处置开单时冻结该车敞口，使可用额度同步下降。
     *
     * <p><b>为什么冻结而不只是告警</b>：经销商一旦进入违约处置，绝不能再占用新额度放款——
     * 冻结后 {@link #occupy(long)} 会因状态非 ACTIVE 被拦截，这是资金安全红线。
     * 冻结量沿用 {@link #freeze(long)} 的"不超过当前可用额"保护，不会把额度扣成负数。</p>
     *
     * <p><b>敞口为 0 的边界</b>：未放款或已还清的车本金敞口为 0，此时无需冻结金额，
     * 但仍须把状态置为 FROZEN，阻止该经销商继续放款（否则"处置中不冻结"的漏洞依旧）。</p>
     *
     * @param exposure 该车未偿本金敞口（整数日元）
     * @return 本次冻结后的累计冻结额（便于调用方审计留痕）
     */
    public long freezeForDisposal(long exposure) {
        if (exposure > 0) {
            freeze(exposure);
        } else {
            // 敞口为 0 也要冻结状态：处置中的经销商不得再占用新额度
            this.status = "FROZEN";
        }
        return this.frozenAmount;
    }

    /**
     * 解冻额度。
     *
     * @param amount 解冻金额（整数日元）
     * @implNote 解冻量不超过已冻结量；冻结归零且非 CLOSED 时，状态恢复为 ACTIVE。
     */
    public void unfreeze(long amount) {
        long actual = Math.min(amount, frozenAmount);
        this.frozenAmount -= actual;
        if (this.frozenAmount == 0 && !"CLOSED".equals(status)) {
            this.status = "ACTIVE";
        }
    }

    /**
     * B-2：违约处置冻结的<b>配对解冻</b>——按本单记录的冻结增量精确回退。
     *
     * <p>与 {@link #unfreeze(long)} 的区别：只有「本额度线已无其他未结案处置单」
     * （{@code lastOpenCase=true}）<b>且</b>冻结金额归零时，才允许状态恢复 ACTIVE。
     * 否则即便本单增量为 0（敞口为 0 只冻状态），仍保持 FROZEN，
     * 避免多单叠加时提前给违约经销商恢复放款能力。</p>
     *
     * @param increment   本次开单的真实冻结增量（冻结前后 frozenAmount 之差）
     * @param lastOpenCase 本单是否为该额度线最后一个未结案处置单
     */
    public void unfreezeForDisposal(long increment, boolean lastOpenCase) {
        long actual = Math.min(increment, frozenAmount);
        this.frozenAmount -= actual;
        if (lastOpenCase && this.frozenAmount == 0 && !"CLOSED".equals(status)) {
            this.status = "ACTIVE";
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public void setDealerId(Long dealerId) {
        this.dealerId = dealerId;
    }

    public long getApprovedLimit() {
        return approvedLimit;
    }

    public void setApprovedLimit(long approvedLimit) {
        this.approvedLimit = approvedLimit;
    }

    public long getUsedAmount() {
        return usedAmount;
    }

    public void setUsedAmount(long usedAmount) {
        this.usedAmount = usedAmount;
    }

    public long getFrozenAmount() {
        return frozenAmount;
    }

    public void setFrozenAmount(long frozenAmount) {
        this.frozenAmount = frozenAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(String initiatedBy) {
        this.initiatedBy = initiatedBy;
    }
}
