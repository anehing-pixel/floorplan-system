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
 * 默认权威源裁决：登记批次表 &gt; 担保台账 &gt; 车辆主数据。
 *
 * <p><b>待 Q27 定源——本实现的优先级只是占位默认值，不是业务结论。</b>
 * 开关注入自 {@code floorplan.collateral.authoritative-source.enabled}，默认 {@code false}：
 * 关闭时 {@link #decide} 返回 skipped，调用方不据此改权属，core 门禁拿不到 REGISTERED 即拒付。
 * 只有业务书面确认后才会打开开关；届时若口径不同，替换本实现即可，不动仓储与服务。</p>
 *
 * <p>裁决分三步：同源多份取最新 → 已上报源状态一致则直接采用 → 冲突时按默认优先级取。
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
        // TODO(Q27): 业务尚未书面确认"担保以哪张表为准/三源冲突如何裁决"。
        // 这里用 @Value 开关承载，默认关闭；开启时的优先级是占位默认值，待 Q27 定源后确认。
        if (!enabled) {
            return Decision.skipped("Q27 定源未下发：权威源策略未启用，不参与门禁判定（fail-closed）");
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
                    "已上报源状态一致：" + statuses.iterator().next().name());
        }

        // 冲突：按默认优先级采信。TODO(Q27): 此优先级为占位值，待业务书面确认。
        for (SourceSystem sys : BY_PRIORITY) {
            SourceSnapshot chosen = latest.get(sys.code());
            if (chosen != null) {
                return Decision.adopt(
                        chosen.getStatus() == null ? RegistrationStatus.NONE : chosen.getStatus(),
                        chosen.getRegNo(), chosen.getOwnerName(), chosen.getSource(),
                        "三源冲突：按默认优先级采信 " + sys.code() + "（" + sys.getDescription()
                                + "）——Q27 定源前的占位规则");
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
