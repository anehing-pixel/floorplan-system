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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 担保权属应用服务：登记、抹消、门禁查询、三源快照导入。
 *
 * <p><b>ADR-Q27（已裁决，见 {@code docs/ADR-Q27-担保权属权威源裁决.md}）</b>：</p>
 * <ul>
 *   <li><b>V10 {@code collateral_registration_batches + items} 是唯一权威源</b>；
 *       {@link #getCurrent(String)} 是放款门禁查询，直读 V10，不读 V18 投影表；
 *       查不到有效登记即返回 {@code NONE}（fail-closed）。</li>
 *   <li>V18 三张表是<b>投影 + 留痕</b>：{@code collateral_registration} 由 V10 写入时
 *       同事务同步，{@code collateral_registration_history} 只追加流水，
 *       {@code collateral_source_snapshot} 是三源对账体检留痕。
 *       <b>门禁判定不读这三张表</b>，因此投影延迟/缺失都不会误放行。</li>
 *   <li>{@link AuthoritativeSourceStrategy} 从「判定器」降级为<b>对账诊断器</b>：
 *       快照批量落库后只诊断一次，产出一致性结论，<b>绝不 adopt 覆盖任何权属表</b>。</li>
 * </ul>
 */
public class CollateralRegistrationService {

    /** 一次三源快照批量导入的上限：三源各一条。 */
    private static final int MAX_BATCH_SOURCES = 3;

    private final JdbcCollateralRegistrationRepository repository;
    private final AuthoritativeSourceStrategy strategy;

    public CollateralRegistrationService(JdbcCollateralRegistrationRepository repository,
                                         AuthoritativeSourceStrategy strategy) {
        this.repository = repository;
        this.strategy = strategy;
    }

    /**
     * 登记：写 V18 投影 + 追加 REGISTER 流水（留痕）。
     *
     * <p>按 ADR-Q27，本方法只是人工补录留痕，<b>不改变放款门禁结论</b>——
     * 门禁只读 V10 批次表。要真正设立担保，走 core 的
     * {@code CollateralLedgerApplicationService.registerCollateralBatch()}（写 V10）。</p>
     */
    public Registration register(String vin, String ownerName, String regNo, Long amount,
                                 LocalDateTime registeredAt, String source) {
        Registration r = Registration.registered(vin, ownerName, regNo, amount, registeredAt, source);
        repository.upsertCurrent(r);
        repository.appendItem(RegistrationItem.of(vin, RegistrationEventType.REGISTER, r,
                source == null ? "MANUAL" : source, "人工/批次登记留痕（ADR-Q27：不参与门禁判定）"));
        return r;
    }

    /** 抹消：V18 投影改为 CANCELLED + 追加 DISCHARGE 流水（留痕，同样不参与门禁判定）。 */
    public Registration discharge(String vin, LocalDateTime at, String note) {
        Registration current = getProjection(vin);
        Registration discharged = current.discharged(at);
        repository.upsertCurrent(discharged);
        repository.appendItem(RegistrationItem.of(vin, RegistrationEventType.DISCHARGE, discharged,
                current.getSource(), note));
        return discharged;
    }

    /**
     * <b>放款门禁查询（B1）</b>：按 ADR-Q27 直读 V10 权威源。
     *
     * <p>有有效登记批次 → {@code REGISTERED}；查不到 / 已抹消 / 服务不可达 →
     * {@code NONE}，core 据此 fail-closed 拒付。</p>
     */
    public Registration getCurrent(String vin) {
        return repository.findAuthoritativeByVin(vin).orElse(Registration.none(vin));
    }

    /** 内部读 V18 投影当前行（供流水记录、运营查询用；<b>不用于门禁判定</b>）。 */
    public Registration getProjection(String vin) {
        return repository.findCurrentByVin(vin).orElse(Registration.none(vin));
    }

    public List<RegistrationItem> getHistory(String vin) {
        return repository.findHistoryByVin(vin);
    }

    /**
     * 单条三源快照导入（保留原契约）：委托 {@link #importSnapshots(List)}，
     * 返回当前 V10 权威查询结果。
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
        importSnapshots(List.of(snapshot));
        return getCurrent(vin);
    }

    /**
     * <b>ADR-Q27 / #31</b>：一次提交 1~3 条源快照，批量落库后<b>只裁决一次</b>。
     *
     * <p>裁决结果仅作为<b>对账体检结论</b>返回，<b>不 adopt、不覆盖任何权属表</b>——
     * 三源都是派生状态，不能反过来改 V10 这个法律事实源。
     * 这样也彻底消除了旧实现「逐条导入、每条立即裁决」造成的中间态误采纳。</p>
     *
     * @param snapshots 同一 VIN 的 1~3 条源快照，source 不可重复
     * @return 诊断报告：importedCount / sources / consistent / suggestedSource / reason / authoritative
     * @throws IllegalArgumentException 入参为空、超过 3 条、VIN 不一致或来源重复
     */
    public Map<String, Object> importSnapshots(List<SourceSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            throw new IllegalArgumentException("三源快照导入至少 1 条");
        }
        if (snapshots.size() > MAX_BATCH_SOURCES) {
            throw new IllegalArgumentException("一次最多导入 " + MAX_BATCH_SOURCES + " 条源快照（三源各一条）");
        }
        String vin = snapshots.get(0).getVin();
        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("快照缺少 VIN");
        }
        Set<String> seenSources = new HashSet<>();
        List<String> sources = new ArrayList<>();
        for (SourceSnapshot s : snapshots) {
            if (s == null || !vin.equals(s.getVin())) {
                throw new IllegalArgumentException("同一次导入的快照必须属于同一 VIN：" + vin);
            }
            String code = s.getSource() == null ? "" : s.getSource().trim().toUpperCase(Locale.ROOT);
            if (code.isEmpty() || !seenSources.add(code)) {
                throw new IllegalArgumentException("源编码为空或重复：" + code);
            }
            sources.add(code);
            if (s.getCapturedAt() == null) {
                s.setCapturedAt(LocalDateTime.now());
            }
            if (s.getStatus() == null) {
                s.setStatus(RegistrationStatus.NONE);
            }
        }

        // 批量落库：先全部留痕，再一次性裁决，避免中间态
        for (SourceSnapshot s : snapshots) {
            repository.saveSnapshot(s);
        }
        Decision decision = strategy.decide(vin, repository.findSnapshotsByVin(vin));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("vin", vin);
        report.put("importedCount", snapshots.size());
        report.put("sources", sources);
        report.put("diagnosisConsistent", decision.isAdopted());
        report.put("suggestedStatus", decision.getStatus() == null ? "NONE" : decision.getStatus().name());
        report.put("suggestedSource", decision.getSource());
        report.put("reason", decision.getReason());
        report.put("adopted", false); // ADR-Q27：只诊断，绝不改账
        report.put("authoritative", getCurrent(vin)); // 门禁只读 V10，不受快照影响
        return report;
    }
}
