package com.company.floorplan.web.consumer;

import com.company.floorplan.application.service.CollateralLedgerApplicationService;
import com.company.floorplan.common.event.DomainEvent;
import com.company.floorplan.common.event.EventConsumer;
import com.company.floorplan.domain.model.CollateralLedger;
import com.company.floorplan.domain.model.SecurityStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.CollateralLedgerRepository;
import com.company.floorplan.domain.repository.CollateralRegistrationBatchRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * {@code PAYMENT_MATCHED} 的 core（floorplan-core）消费者：资金结清后真正解除担保。
 *
 * <p><b>它补的是哪一刀</b>：bank-service 的 {@code ReleaseOrchestrator} 只能写自己的 V17
 * 「解押留痕」（`lien_releases` RELEASED + 抹消 REQUESTED），因为
 * {@code vehicle_units.security_status} 与 {@code collateral_ledger} 由 core 拥有。
 * 本类在同一事件上做 core 侧的释放：车辆担保状态置 RELEASED、担保物台账标记已解除、
 * 关联登记批次生成抹消待办（REQUESTED）。</p>
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>只认 settled=true</b>：部分回款不解除担保（ADR Q24 / D09 §8.3）。
 *       事件缺 settled 或为 false 一律跳过，不猜；</li>
 *   <li><b>不做额度回补</b>：额度由 credit-service 消费同一事件后在自己的行锁事务里回补；
 *       本类只碰 core 拥有的车辆与担保台账，避免双重回补/跨域直写；</li>
 *   <li><b>解押 ≠ 抹消完成</b>：只把批次推进到 REQUESTED，绝不写 DONE
 *       （日本实务需权利人与登记义务人共同申请）。</li>
 * </ol></p>
 *
 * <p><b>两层幂等</b>：框架层 {@code event_consumptions(event_id, floorplan-core)} 挡同一事件行；
 * 业务层 V81 {@code payment_security_releases.payment_ref} 唯一约束挡「同一笔回款被重新发布成新事件」。
 * 车辆状态本身也是幂等的（{@link VehicleUnit#releaseSecurity()} 对 NONE 直接返回），
 * 但不能只靠它——否则重放会给审计和台账留下重复痕迹。</p>
 *
 * <p><b>与直接还款路径的关系（如实记录）</b>：core 的 {@code RepaymentApplicationService.settle}
 * 也会解除担保并回补额度。两条路径目前没有共用 {@code settle_seq} 幂等键
 * （USED R8 §8.1 未实现），所以「同一笔债既走银行匹配又走直接还款」时额度可能被释放两次。
 * 本类只释放担保，不放大额度侧风险；该缺口登记在 PROGRESS §3 与契约 §7。</p>
 */
@Component
public class PaymentMatchedSecurityConsumer implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentMatchedSecurityConsumer.class);

    /** 与 {@code OutboxPublisher.EVENT_PAYMENT_MATCHED} 同一取值；写死避免反向依赖契约常量类。 */
    private static final String EVENT_TYPE = "PAYMENT_MATCHED";

    /** 消费方名称 = 服务名（web 的 spring.application.name），同时是游标与幂等表的键。 */
    private static final String CONSUMER_NAME = "floorplan-core";

    private final VehicleUnitRepository vehicleUnitRepository;
    private final CollateralLedgerRepository collateralLedgerRepository;
    private final CollateralRegistrationBatchRepository batchRepository;
    private final CollateralLedgerApplicationService collateralService;
    private final JdbcPaymentSecurityReleaseRepository releaseRepository;
    private final SupportRepository supportRepository;

    public PaymentMatchedSecurityConsumer(VehicleUnitRepository vehicleUnitRepository,
                                          CollateralLedgerRepository collateralLedgerRepository,
                                          CollateralRegistrationBatchRepository batchRepository,
                                          CollateralLedgerApplicationService collateralService,
                                          JdbcPaymentSecurityReleaseRepository releaseRepository,
                                          SupportRepository supportRepository) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.collateralLedgerRepository = collateralLedgerRepository;
        this.batchRepository = batchRepository;
        this.collateralService = collateralService;
        this.releaseRepository = releaseRepository;
        this.supportRepository = supportRepository;
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    @Transactional
    public void consume(DomainEvent event) {
        String vin = event.textField("vin");
        String traceId = event.textField("traceId");
        String paymentRef = event.textField("paymentRef");

        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 vin：event_id=" + event.eventId());
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 traceId：event_id=" + event.eventId()
                    + " vin=" + vin);
        }
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new IllegalArgumentException("PAYMENT_MATCHED 缺 paymentRef，无法做业务幂等：event_id="
                    + event.eventId() + " vin=" + vin);
        }

        Boolean settled = event.booleanField("settled");
        if (!Boolean.TRUE.equals(settled)) {
            // 未结清 / 字段缺失：不解除担保。这不是错误，是"本次不释放"。
            log.info("PAYMENT_MATCHED 不触发解押（settled 非 true）：event_id={} paymentRef={} vin={} "
                    + "settled={} traceId={}", event.eventId(), paymentRef, vin, settled, traceId);
            return;
        }

        Long dealerId = event.longField("dealerId");
        String before = null;

        // L2 认领：同一 paymentRef 只解除一次。认领失败直接返回，不碰车辆/台账。
        // before 要在认领后、加锁读取前写入；这里先以 null 认领，随后 complete 回填。
        if (!releaseRepository.claim(paymentRef, event.eventId(), traceId, vin, null)) {
            log.info("PAYMENT_MATCHED 解押业务幂等命中，跳过重复释放：event_id={} paymentRef={} vin={} "
                    + "traceId={}", event.eventId(), paymentRef, vin, traceId);
            return;
        }

        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new IllegalStateException(
                        "PAYMENT_MATCHED 解押目标车辆不存在：vin=" + vin + " paymentRef=" + paymentRef));
        before = unit.getSecurityStatus() == null ? null : unit.getSecurityStatus().name();
        unit.releaseSecurity();
        vehicleUnitRepository.save(unit);
        String after = unit.getSecurityStatus() == null ? null : unit.getSecurityStatus().name();

        boolean ledgerReleased = false;
        boolean cancellationRequested = false;
        String status = SecurityStatus.NONE.name().equals(after) ? "NO_SECURITY" : "RELEASED";
        String note = null;

        Optional<CollateralLedger> ledger = collateralLedgerRepository.findByVin(vin);
        if (ledger.isPresent()) {
            // 台账存在：标记已解除担保，并把需要抹消的登记批次推进到 REQUESTED。
            // markReleased 自身幂等（重复置 true），但 L2 已经在前面挡住重放。
            collateralService.markReleased(vin, "PAYMENT_MATCHED", "system");
            ledgerReleased = true;
            cancellationRequested = batchRepository.findByVin(vin).stream()
                    .anyMatch(b -> "REQUESTED".equals(b.getCancellationStatus()));
            if (!cancellationRequested) {
                note = "无待抹消的登记批次（可能尚未做担保登记）";
            }
        } else {
            // 台账缺失是数据缺口，不静默：车辆侧能解则解，另写 HIGH 告警供人工补建。
            status = "LEDGER_MISSING";
            note = "担保物台账缺失，未执行 markReleased；请人工补建台账并核对担保登记";
            supportRepository.createAlert(dealerId, vin, "SECURITY_RELEASE_LEDGER_MISSING", "HIGH", note);
            log.error("PAYMENT_MATCHED 解押时担保物台账缺失：vin={} paymentRef={} dealerId={}",
                    vin, paymentRef, dealerId);
        }

        releaseRepository.complete(paymentRef, after, ledgerReleased, cancellationRequested, status, note);

        supportRepository.writeAudit("system", "RELEASE_SECURITY", "VEHICLE", vin,
                "status=" + before,
                "status=" + after + ",source=PAYMENT_MATCHED,paymentRef=" + paymentRef
                        + ",ledgerReleased=" + ledgerReleased
                        + ",cancellationRequested=" + cancellationRequested,
                traceId);

        log.info("PAYMENT_MATCHED 解押完成 event_id={} paymentRef={} vin={} security={}->{} "
                        + "ledgerReleased={} cancellationRequested={} status={} traceId={}",
                event.eventId(), paymentRef, vin, before, after, ledgerReleased,
                cancellationRequested, status, traceId);
    }
}
