package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

import java.util.Locale;

/**
 * 车商准入态（USED R1，D03 §2.1 的派生值对象）。
 *
 * <p><b>它是派生态，不是事实源</b>：真正的证据在三张明细表里
 * （{@code dealer_licenses} / {@code dealer_auction_memberships} /
 * {@code dealer_compliance_checks}）。本列只是把那三方有效性的<b>一次推导结果</b>落下来，
 * 供放款闸门、列表筛选、报表快速读。所以：</p>
 * <ul>
 *   <li>只有 {@code DealerAdmissionPolicy.evaluate} 能产出本枚举的值，别的写入路径一律不许直接 UPDATE；</li>
 *   <li>任何资质明细发生变化（许可换发、会员停摆、合规结论落库）后必须重算，
 *       否则会出现「库里 ADMITTED、实际许可早过期」的假放行——
 *       这正是 AGENTS.md §12「表建了就算功能有了」同型的坑。</li>
 * </ul>
 *
 * <p><b>取值域与库内 CHECK 同源</b>：{@code dealers.ck_dealers_admission_state}
 * 允许 NULL ＋ 下面五个值。NULL 表示「从未推导过」，
 * 语义上等同于「不可放款」（fail-closed），由 {@link #blocksAdvance} 之外的调用方处理。</p>
 *
 * <p><b>没有「白名单/豁免」态</b>：D03 §2.1 明文禁止 {@code relaxedDealers} 之类的入参
 * （Q21 教训，AGENTS.md §8）。要新增绕过必须先是显式业务裁决。</p>
 */
public enum AdmissionState {

    /** 三方证据齐备且全部有效：允许进入放款闸门。 */
    ADMITTED("准入"),

    /** 古物商许可缺失、非 ACTIVE 或已过期（G-A1）。 */
    BLOCKED_LICENSE("许可缺失或过期"),

    /** 四类合规核查未全部拿到未过期的 PASS（G-A3，UNKNOWN 按不通过）。 */
    BLOCKED_COMPLIANCE("合规核查未通过"),

    /** 无任何有效拍卖场会员关系（G-A2）。 */
    BLOCKED_MEMBERSHIP("拍卖场会员无效"),

    /**
     * 证据齐备但许可/会员在预警窗口内即将到期（D03 §2.1 规则 4、D06 §1.5 的 30 天）。
     *
     * <p><b>允许放款</b>，但要产出资质到期预警——把 EXPIRING 当成阻断会把「还有 29 天到期」
     * 的车商全部停掉，业务上站不住；把它当成 ADMITTED 又会丢掉预警。所以它是一枚独立态。</p>
     */
    EXPIRING("即将到期");

    private final String label;

    AdmissionState(String label) {
        this.label = label;
    }

    /** 中文标签，供前端提示文案（AGENTS.md §10：错误码新增要同步前端文案）。 */
    public String getLabel() {
        return label;
    }

    /**
     * 是否阻断放款。
     *
     * <p>只有三个 {@code BLOCKED_*} 阻断；{@code ADMITTED} 与 {@code EXPIRING} 放行。
     * 判定写成白名单式（{@code this == ADMITTED || this == EXPIRING}）反而危险——
     * 将来新增一枚阻断态会被默认放行，所以这里用「前缀」表达阻断族。</p>
     */
    public boolean blocksAdvance() {
        return name().startsWith("BLOCKED_");
    }

    /** 对应 D06 §1.3 的门禁错误码；放行态返回 null（由调用方决定不写错误码）。 */
    public ErrorCode errorCode() {
        return switch (this) {
            // QUAL_001 许可；QUAL_003 合规；QUAL_002 会员。
            // 三段码的对应关系以 D07 §8 逐码表为准（D03 §2.1 规则 3 写的 QUAL_004 在码表里没有位，
            // ErrorCode 枚举里已登记该冲突并按 D07 §8 取 QUAL_002）——不要在别处再分叉一次。
            case BLOCKED_LICENSE -> ErrorCode.QUAL_001;
            case BLOCKED_COMPLIANCE -> ErrorCode.QUAL_003;
            case BLOCKED_MEMBERSHIP -> ErrorCode.QUAL_002;
            case ADMITTED, EXPIRING -> null;
        };
    }

    /**
     * 解析库内文本。
     *
     * <p>NULL / 空串返回 {@code null}，语义是「从未推导」，调用方按 fail-closed 处理
     * （等同不可放款）。非法值直接抛 {@code VALID_001}：脏值必须是响的，
     * 不能被静默降级成某个态——那会让一条拼错的写入把闸门搬动。</p>
     */
    public static AdmissionState from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AdmissionState s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        throw new BusinessException(ErrorCode.VALID_001, "未知的准入态: " + value);
    }
}
