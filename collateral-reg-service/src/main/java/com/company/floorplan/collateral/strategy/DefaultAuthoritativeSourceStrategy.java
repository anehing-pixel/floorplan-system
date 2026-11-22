package com.company.floorplan.collateral.strategy;

import com.company.floorplan.collateral.model.RegistrationStatus;
import com.company.floorplan.collateral.model.SourceSnapshot;
import com.company.floorplan.collateral.model.SourceSystem;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 默认三源对账诊断：登记批次表 &gt; 担保台账 &gt; 车辆主数据。
 *
 * <p><b>ADR-Q27（已裁决）</b>：本类是<b>对账诊断器</b>，不是判定器。
 * 优先级「登记批次表 &gt; 担保台账 &gt; 车辆主数据」已按 V10 是唯一权威源定稿
 * （法务局登记是公示依据，后两者是派生状态），不再是占位值。
 * 诊断结论只写进对账报告，<b>不会 adopt 覆盖任何权属表</b>；
 * 放款门禁直读 V10，与本策略开关无关。</p>
 *
 * <p>开关注入自 {@code floorplan.collateral.authoritative-source.enabled}，默认 {@code false}：
 * 关闭时 {@link #decide} 返回 skipped（不产出诊断）。</p>
 *
 * <p>诊断分三步：同源多份取最新 → 已上报源状态一致则直接采信 → 冲突时按优先级取。
 * 未知来源不参与比较（既不采信也不误伤）。</p>
 */
public class DefaultAuthoritativeSourceStrategy implements AuthoritativeSourceStrategy {

    private static final List<SourceSystem> BY_PRIORITY = Arrays.stream(SourceSystem.values())
            .sorted(Comparator.comparingInt(SourceSystem::getPriority))
            .toList();

    private final boolean enabled;

    public DefaultAuthoritativeSourceStrategy(
            @Value("${floorplan.collateral.authoritative-source.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Decision decide(String vin, List<SourceSnapshot> snapshots) {
        // ADR-Q27：本策略是对账诊断器，不参与门禁判定；开关只控制是否产出诊断。
        if (!enabled) {
            return Decision.skipped("对账诊断开关未启用（ADR-Q27：不影响门禁，门禁直读 V10）");
        }
        if (snapshots == null || snapshots.isEmpty()) {
            return Decision.skipped("无三源快照：无可采信数据，保持当前权属不变");
        }

        Map<String, SourceSnapshot> latest = latestPerSource(snapshots);
        if (latest.isEmpty()) {
            return Decision.skipped("快照来源均未知：无可采信数据，保持当前权属不变");
        }

        Set<RegistrationStatus> statuses = new HashSet<>();
        for (SourceSnapshot s : latest.values()) {
            statuses.add(s.getStatus() == null ? RegistrationStatus.NONE : s.getStatus());
        }
        if (statuses.size() == 1) {
            SourceSnapshot any = latest.values().iterator().next();
            return Decision.adopt(
                    any.getStatus() == null ? RegistrationStatus.NONE : any.getStatus(),
                    any.getRegNo(), any.getOwnerName(), any.getSource(),
                    any.getAmount(), any.getCapturedAt(),
                    "已上报源状态一致：" + statuses.iterator().next().name());
        }

        // ADR-Q27：冲突时按已定稿优先级采信（V10 批次表为法律公示依据）。
        for (SourceSystem sys : BY_PRIORITY) {
            SourceSnapshot chosen = latest.get(sys.code());
            if (chosen != null) {
                return Decision.adopt(
                        chosen.getStatus() == null ? RegistrationStatus.NONE : chosen.getStatus(),
                        chosen.getRegNo(), chosen.getOwnerName(), chosen.getSource(),
                        chosen.getAmount(), chosen.getCapturedAt(),
                        "三源冲突：按 ADR-Q27 优先级建议采信 " + sys.code() + "（" + sys.getDescription()
                                + "）；仅作对账诊断，不改账");
            }
        }

        return Decision.skipped("仅有未知来源快照：不可采信，保持当前权属不变");
    }

    /** 同一来源多次上报只取 capturedAt 最新的一份。 */
    private static Map<String, SourceSnapshot> latestPerSource(List<SourceSnapshot> snapshots) {
        Map<String, SourceSnapshot> latest = new LinkedHashMap<>();
        for (SourceSnapshot s : snapshots) {
            if (s == null || s.getSource() == null || s.getSource().isBlank()) {
                continue;
            }
            String code = s.getSource().trim().toUpperCase(Locale.ROOT);
            // 未知来源不参与裁决：既不能被当作"状态一致"采纳，也不能影响优先级比较
            if (SourceSystem.fromCode(code) == null) {
                continue;
            }
            SourceSnapshot prev = latest.get(code);
            if (prev == null || isLater(s.getCapturedAt(), prev.getCapturedAt())) {
                latest.put(code, s);
            }
        }
        return latest;
    }

    private static boolean isLater(LocalDateTime candidate, LocalDateTime current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return candidate.isAfter(current);
    }
}
