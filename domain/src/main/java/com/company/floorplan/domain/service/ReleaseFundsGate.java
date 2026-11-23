package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.UsedReleaseStatus;

/**
 * 放车放证的资金闸门（红线 1 的唯一落点）。
 *
 * <p>条件集<b>逐字取自 D09:164</b>（→{@code PENDING_RELEASE} 的前置列）与
 * D07 §7.6 的「{@code /dispatch} 重校验清单」，两处合起来才是完整闸门：
 * 建单查一次不够，建单到下发之间可能插入冻结、过期、并发取消
 * （D07 §7.6 表尾原话：「建单时查过闸门不等于下发时还能过」）。</p>
 *
 * <p><b>闸门只读结论，不重算金额</b>：敞口是否为 0 由调用方传入
 * （本金＋利息＋费用的清偿结论），闸门不在 domain 里算利息或费用——
 * 冲抵顺序是未签字的 X-1，罚息与费用列本轮不建（§3.6）。
 * 如果在闸门里重算，就等于用一个未定源的算法去决定"能不能放车"。</p>
 *
 * <p><b>不拆错误码</b>：五个条件共用 {@code RELS_002}，条件编号写进 message 与审计，
 * 这样调用方无法按码分叉出自例路径（D07 §8 的 RELS 码表只给了一个"闸门未过"的位）。</p>
 */
public final class ReleaseFundsGate {

    private ReleaseFundsGate() {
    }

    /**
     * 建单前置（D09:164 全五条）。
     *
     * @param productLineUsed       是否 USED 产品线（LEGACY 走原链，不套 USED 的质押前置）
     * @param outstandingExposure   该借据的剩余敞口（日元整数，BIGINT 口径）；null 视为未知＝阻断
     * @param pledgeEvidenced       质押/实控证据是否齐全（USED 必須，见 CERTIFICATE_PLEDGE 前置）
     * @param openReconDiff         是否存在 OPEN 且压住出库的账实差异（D09 S3 前置，A8.2）
     * @param creditLineFrozen      额度线是否冻结
     */
    public static void requireCreatable(boolean productLineUsed, Long outstandingExposure,
                                       boolean pledgeEvidenced, boolean openReconDiff,
                                       boolean creditLineFrozen) {
        if (outstandingExposure == null || outstandingExposure != 0L) {
            throw new BusinessException(ErrorCode.RELS_002,
                    "闸门 G1 未过：剩余敞口必须为 0（本金＋利息＋费用全部清偿），当前 " + outstandingExposure);
        }
        if (productLineUsed && !pledgeEvidenced) {
            // USED 线才查这条：LEGACY 没有車検証质押链，硬套会把旧链路打死（A2.5 回归要求）
            throw new BusinessException(ErrorCode.RELS_002,
                    "闸门 G2 未过：质押或实控证据不齐（車検証未生效即 CERT_005）");
        }
        if (openReconDiff) {
            throw new BusinessException(ErrorCode.RELS_002,
                    "闸门 G3 未过：存在 OPEN 的账实差异，出库与放车一律不放行（D09 S3 前置，A8.2）");
        }
        if (creditLineFrozen) {
            // 【已登记冲突】D07 §7.6 重校验清单第 3 行写的是 CREDIT_003，但现役 ErrorCode 里
            // CREDIT_003 = 「额度尚未审批通过，不可用于放款」，冻结对应的是 CREDIT_002「额度已冻结」。
            // 本轮按<b>码的语义</b>取 CREDIT_002（文案要对得上事实，宁可偏离那一格的引用），
            // 由收口方二选一：改 D07 那一格为 CREDIT_002，或给"冻结"另立码并同步 D00 §12。
            throw new BusinessException(ErrorCode.CREDIT_002,
                    "闸门 G4 未过：授信额度线处于冻结态");
        }
    }

    /**
     * 下发重校验（D07 §7.6 清单，<b>只查不写</b>以保持幂等）。
     *
     * <p>第 2 行「valid_until 未到 → 拒发 ＋ 置 EXPIRED」的"置位"动作不在这里发生：
     * 本方法是纯判定，置 EXPIRED 由 application 层以
     * {@link UsedReleaseStateMachine.TransitionOrigin#SYSTEM} 走状态机，
     * 否则 domain 就有了写库副作用，RB-1 的来源区分也就没意义了。</p>
     *
     * @param status     指令当前态（必须仍是 PENDING_RELEASE，或撤销后重新激活的新号）
     * @param expired    是否已过 valid_until
     * @param creditLineFrozen 当前是否冻结
     * @param allItemsReleasable 每个 item 是否仍处可释放态（未被并发取消）
     */
    public static void requireDispatchable(UsedReleaseStatus status, boolean expired,
                                          boolean creditLineFrozen, boolean allItemsReleasable) {
        if (status != UsedReleaseStatus.PENDING_RELEASE) {
            // 清单第 1 行：状态不符 → RELS_001（不是 RELS_002，这条查的是指令本身而不是钱）
            throw new BusinessException(ErrorCode.RELS_001,
                    "指令当前状态不接受下发: " + status);
        }
        if (expired) {
            throw new BusinessException(ErrorCode.RELS_003,
                    "指令已过 valid_until，拒发并需置 EXPIRED（清单第 2 行）");
        }
        if (creditLineFrozen) {
            throw new BusinessException(ErrorCode.CREDIT_002, "清单第 3 行：额度线当前冻结，拒发");
        }
        if (!allItemsReleasable) {
            // 整单拒发、不留半单（清单第 4 行原文）
            throw new BusinessException(ErrorCode.RELS_004, "清单第 4 行：存在不可释放项，整单拒发");
        }
    }

    /**
     * 撤销语义（D09 §5.4 四行表）。
     *
     * <p>{@code ACKNOWLEDGED} 之后<b>系统不得单方面认为已撤</b>，需
     * {@code force_reason} ＋ 二人 ＋ 对方确认回执，否则 {@code RELS_003}；
     * {@code PARTIAL}/{@code CONFIRMED}/{@code RELEASED} 不可撤（RL-4），
     * 只能走追偿/回购（R10）。</p>
     *
     * @param status       当前态
     * @param hasCoSigner  是否已有第二签认人
     * @param hasForceReason 是否带 force_reason
     * @param hasPartnerCancelReceipt 是否已有对方撤销确认回执
     * @return 撤销时点决定的动作标记，供 application 层决定是否发 cancel 出站任务
     */
    public static CancelAction resolveCancel(UsedReleaseStatus status, boolean hasCoSigner,
                                             boolean hasForceReason, boolean hasPartnerCancelReceipt) {
        return switch (status) {
            case PENDING_RELEASE -> CancelAction.DIRECT;
            case SENT -> CancelAction.BEST_EFFORT;
            case ACKNOWLEDGED -> {
                if (!(hasCoSigner && hasForceReason && hasPartnerCancelReceipt)) {
                    throw new BusinessException(ErrorCode.RELS_003,
                            "指令已被对方受理，撤销需 force_reason + 二人 + 对方确认回执（D09 §5.4）");
                }
                yield CancelAction.CONFIRMED_CANCEL;
            }
            // PARTIAL 一并归"不可撤"：本态按聚合规则（ReleaseStatusAggregator 取最不利）
            // 意味着至少一项已经物理离仓，撤单对整单已无对象；剩余未放项走新指令，
            // 不走撤单。与 UsedReleaseStateMachine 的 PARTIAL -> {CONFIRMED, REJECTED}
            // 白名单自洽（那里就没有 PARTIAL→CANCELLED 这条边）。
            // 【待裁决】D09 §5.4 的四行表只枚举了 PENDING/SENT/ACKNOWLEDGED/已释放，
            // 没有给 PARTIAL 定性，本行是按状态机反推的，不是文档原文。
            case PARTIAL, CONFIRMED, RELEASED, LIEN_DISCHARGED -> throw new BusinessException(ErrorCode.RELS_001,
                    "车辆已离仓（至少部分释放），不可撤销，请走追偿/回购（RL-4）");
            // EXPIRED/REJECTED/CANCELLED：作废或已终结，撤销没有对象（EXPIRED→CANCELLED 是人工作废，走状态机）
            case EXPIRED, REJECTED, CANCELLED -> CancelAction.NOOP;
        };
    }

    /** 撤销时点的动作分类，避免调用方用布尔矩阵表达四行表。 */
    public enum CancelAction {
        /** 直接撤：从未发出，不通知对方。 */
        DIRECT,
        /** 尽力撤：需向所有接收方发 cancel 任务，ACKNOWLEDGED 前到达才算成功。 */
        BEST_EFFORT,
        /** 对方确认后撤：需 force_reason＋二人，且额度不回补也不扣减。 */
        CONFIRMED_CANCEL,
        /** 无撤销对象（已终结）。 */
        NOOP
    }

    /**
     * 额度回补的唯一前置（RL-3：{@code LIEN_DISCHARGED} 之前不得回补）。
     *
     * <p>回补只在 {@code SETTLED} 发生（D09 §5.4 末段：撤销不回补也不扣减）。
     * 本方法返回 false 时 application 必须<b>什么都不做</b>，不许"先记一笔待回补"——
     * 那等于在顺序断言之外又建了一条异步补偿链，正是 RL-3 要防的形态。</p>
     */
    public static boolean mayRestoreCredit(UsedReleaseStatus status) {
        return status == UsedReleaseStatus.LIEN_DISCHARGED;
    }
}
