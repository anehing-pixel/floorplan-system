package com.company.floorplan.release.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.release.channel.ReleaseChannel;
import com.company.floorplan.release.model.ReleaseItemType;
import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;
import com.company.floorplan.release.model.ReleaseStateHistory;
import com.company.floorplan.release.model.ReleaseStatus;
import com.company.floorplan.release.repository.JdbcReleaseOrderRepository;
import com.company.floorplan.release.statemachine.ReleaseStateMachine;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 出库指令应用服务：创建 / 回执确认 / 放车 / 解抵 / 查询。
 *
 * <p><b>每一步都走状态机 + 落状态历史</b>，这是本服务的硬约束：
 * 应用服务自己不写 if-else 判断"能不能放车"，只调用
 * {@link ReleaseStateMachine#requireTransition}。规则只有一份，单测才能穷举。</p>
 *
 * <p><b>动作与状态的对应</b>（注意 PENDING_RELEASE → SENT 发生在创建时）：
 * <pre>
 *   create          → PENDING_RELEASE，经 ReleaseChannel 下发后推进到 SENT
 *   confirm         → SENT → CONFIRMED       （接收方回执确认）
 *   release         → CONFIRMED → RELEASED   （放车，同时标记明细已释放）
 *   dischargeLien   → RELEASED → LIEN_DISCHARGED（解抵，终态）
 * </pre>
 * 因此"未经 CONFIRMED 不得 RELEASED"与"未 RELEASED 不得 LIEN_DISCHARGED"
 * 由状态机白名单保证，服务层无法绕过。</p>
 *
 * <p><b>事务边界</b>：创建、下发、状态推进、历史落库在同一事务内；
 * 下发失败即整体回滚，不会出现"指令建了却没发"或"状态推进了历史没落"的半截状态。</p>
 */
public class ReleaseApplicationService {

    /** 设计稿 §1.2⑤：指令默认有效期 7 个日历日。 */
    private static final int DEFAULT_VALID_DAYS = 7;

    private final JdbcReleaseOrderRepository repository;
    private final ReleaseChannel releaseChannel;

    public ReleaseApplicationService(JdbcReleaseOrderRepository repository, ReleaseChannel releaseChannel) {
        this.repository = repository;
        this.releaseChannel = releaseChannel;
    }

    /** 创建指令 → 下发 → 推进到 SENT。 */
    @Transactional
    public ReleaseOrder create(CreateReleaseCommand cmd) {
        Instant now = Instant.now();
        ReleaseOrder order = new ReleaseOrder();
        order.setOrderNo(repository.nextOrderNo(now));
        order.setRedemptionId(cmd.redemptionId());
        order.setDealerId(cmd.dealerId());
        order.setVin(cmd.vin());
        order.setCertificateNo(cmd.certificateNo());
        order.setCustodianId(cmd.custodianId());
        order.setReleaseItems(cmd.releaseItems());
        order.setValidUntil(cmd.validUntil() == null ? now.plus(DEFAULT_VALID_DAYS, ChronoUnit.DAYS) : cmd.validUntil());
        order.setStatus(ReleaseStatus.PENDING_RELEASE);
        order.setIssuedAt(now);
        order.setIssuedBy(cmd.operator());
        order.setDeliveryChannel(cmd.deliveryChannel() == null ? "MANUAL" : cmd.deliveryChannel());

        repository.save(order); // 回填主键
        repository.appendHistory(new ReleaseStateHistory(
                order.getId(), null, ReleaseStatus.PENDING_RELEASE, "CREATE", cmd.operator(), null));

        List<ReleaseOrderItem> items = ReleaseItemType.parseList(cmd.releaseItems()).stream()
                .map(type -> new ReleaseOrderItem(order.getVin(), type,
                        type == ReleaseItemType.CERTIFICATE ? order.getCertificateNo() : null))
                .toList();
        repository.saveItems(order.getId(), items);

        // 下发接缝：Q9/Q10 未定，当前实现只打日志；失败则事务回滚，状态停在 PENDING_RELEASE
        releaseChannel.dispatch(order, items);

        advance(order, ReleaseStatus.SENT, "DISPATCH", cmd.operator(), null, Instant.now());
        return repository.findById(order.getId()).orElse(order);
    }

    /** 接收方回执确认：SENT → CONFIRMED。 */
    @Transactional
    public ReleaseOrder confirm(long orderId, String operator, String reason) {
        return advanceExisting(orderId, ReleaseStatus.CONFIRMED, "CONFIRM", operator, reason);
    }

    /** 放车：CONFIRMED → RELEASED；同时把明细逐项标记为已释放。 */
    @Transactional
    public ReleaseOrder release(long orderId, String operator, String reason) {
        ReleaseOrder order = advanceExisting(orderId, ReleaseStatus.RELEASED, "RELEASE", operator, reason);
        repository.markItemsReleased(orderId, Instant.now());
        return order;
    }

    /** 解抵：RELEASED → LIEN_DISCHARGED（终态）。 */
    @Transactional
    public ReleaseOrder dischargeLien(long orderId, String operator, String reason) {
        return advanceExisting(orderId, ReleaseStatus.LIEN_DISCHARGED, "DISCHARGE_LIEN", operator, reason);
    }

    /** 查询指令 + 明细 + 状态历史。 */
    @Transactional(readOnly = true)
    public ReleaseDetail get(long orderId) {
        ReleaseOrder order = requireOrder(orderId);
        return new ReleaseDetail(order,
                repository.findItems(orderId),
                repository.findHistory(orderId));
    }

    private ReleaseOrder advanceExisting(long orderId, ReleaseStatus target,
                                         String action, String operator, String reason) {
        ReleaseOrder order = requireOrder(orderId);
        advance(order, target, action, operator, reason, Instant.now());
        return repository.findById(orderId).orElse(order);
    }

    private void advance(ReleaseOrder order, ReleaseStatus target, String action,
                         String operator, String reason, Instant now) {
        ReleaseStatus from = order.getStatus();
        ReleaseStateMachine.requireTransition(from, target); // 非法迁移在这里被拒绝
        repository.updateStatus(order.getId(), target, now);
        repository.appendHistory(new ReleaseStateHistory(order.getId(), from, target, action, operator, reason));
    }

    private ReleaseOrder requireOrder(long orderId) {
        return repository.findById(orderId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND_001, "出库指令不存在：id=" + orderId));
    }
}
