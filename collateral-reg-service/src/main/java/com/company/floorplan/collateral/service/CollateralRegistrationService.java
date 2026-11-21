package com.company.floorplan.collateral.service;

import com.company.floorplan.collateral.model.Registration;
import com.company.floorplan.collateral.model.RegistrationEventType;
import com.company.floorplan.collateral.model.RegistrationItem;
import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;
import com.company.floorplan.collateral.repository.JdbcCollateralRegistrationRepository;
import com.company.floorplan.collateral.strategy.AuthoritativeSourceStrategy;
import com.company.floorplan.collateral.strategy.AuthoritativeSourceStrategy.Decision;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 担保权属应用服务：登记、抹消、门禁查询、三源快照导入。
 *
 * <p><b>Q27 边界</b>：门禁判定规则不写死在这里，全部委托 {@link AuthoritativeSourceStrategy}。
 * 策略默认关闭时，快照导入只留痕、不改当前权属；查询查无记录返回 NONE（fail-closed）。</p>
 */
public class CollateralRegistrationService {

    private final JdbcCollateralRegistrationRepository repository;
    private final AuthoritativeSourceStrategy strategy;

    public CollateralRegistrationService(JdbcCollateralRegistrationRepository repository,
                                         AuthoritativeSourceStrategy strategy) {
        this.repository = repository;
        this.strategy = strategy;
    }

    /** 登记：写当前权属 + 追加 REGISTER 流水。 */
    public Registration register(String vin, String ownerName, String regNo, Long amount,
                                 LocalDateTime registeredAt, String source) {
        Registration r = Registration.registered(vin, ownerName, regNo, amount, registeredAt, source);
        repository.upsertCurrent(r);
        repository.appendItem(RegistrationItem.of(vin, RegistrationEventType.REGISTER, r,
                source == null ? "MANUAL" : source, "人工/批次登记"));
        return r;
    }

    /** 抹消：当前权属改为 CANCELLED + 追加 DISCHARGE 流水。 */
    public Registration discharge(String vin, LocalDateTime at, String note) {
        Registration current = repository.findCurrentByVin(vin).orElse(Registration.none(vin));
        Registration discharged = current.discharged(at);
        repository.upsertCurrent(discharged);
        repository.appendItem(RegistrationItem.of(vin, RegistrationEventType.DISCHARGE, discharged,
                current.getSource(), note));
        return discharged;
    }

    /** 门禁查询：查无记录返回 NONE（fail-closed，core 据此拒付）。 */
    public Registration getCurrent(String vin) {
        return repository.findCurrentByVin(vin).orElse(Registration.none(vin));
    }

    public List<RegistrationItem> getHistory(String vin) {
        return repository.findHistoryByVin(vin);
    }

    /**
     * 三源快照导入：先留痕，再交策略裁决。
     *
     * <p>策略未启用 / 无可采信数据时返回 skipped —— 保持当前权属不变，绝不误放行门禁。
     * 只有策略明确采纳时才覆盖当前权属并追加 SNAPSHOT_ADOPT 流水。</p>
     */
    public Registration importSnapshot(String vin, String source, RegistrationStatus status,
                                       String regNo, String ownerName, Long amount,
                                       String rawPayload, LocalDateTime capturedAt) {
        SourceSnapshot snapshot = new SourceSnapshot();
        snapshot.setVin(vin);
        snapshot.setSource(source);
        snapshot.setStatus(status == null ? RegistrationStatus.NONE : status);
        snapshot.setRegNo(regNo);
        snapshot.setOwnerName(ownerName);
        snapshot.setAmount(amount);
        snapshot.setRawPayload(rawPayload);
        snapshot.setCapturedAt(capturedAt == null ? LocalDateTime.now() : capturedAt);
        repository.saveSnapshot(snapshot);

        Decision decision = strategy.decide(vin, repository.findSnapshotsByVin(vin));
        if (!decision.isAdopted()) {
            return getCurrent(vin);
        }
        Registration current = getCurrent(vin);
        LocalDateTime registeredAt = decision.getStatus() == RegistrationStatus.REGISTERED
                && current.getRegisteredAt() == null ? decision.getEffectiveAt() : null;
        Registration adopted = current.adopt(decision.getStatus().name(), decision.getRegNo(),
                decision.getOwnerName(), decision.getAmount(), decision.getSource(), registeredAt);
        repository.upsertCurrent(adopted);
        repository.appendItem(RegistrationItem.of(vin, RegistrationEventType.SNAPSHOT_ADOPT, adopted,
                decision.getSource(), decision.getReason()));
        return adopted;
    }
}
