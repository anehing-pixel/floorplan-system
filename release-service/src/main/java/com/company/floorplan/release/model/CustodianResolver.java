package com.company.floorplan.release.model;

import java.util.Locale;
import java.util.Map;

/**
 * 按释放项解析实际接收方 ID —— 纯函数，无 Spring、无 I/O，便于穷举单测。
 *
 * <p><b>解析优先级</b>：显式按项指定 &gt; 继承主表主接收方 &gt; {@code null}。
 * 之所以保留「继承」这一档，是因为既有调用方只在主表传 {@code custodianId}，
 * 改成按项必填会破坏兼容；而返回 {@code null} 也不算错误，它表示
 * 「该向哪一类接收方交接是明确的，但具体机构 ID 尚未配置」，由人工交接单提示。</p>
 *
 * <p>键名大小写不敏感：请求体可能写 {@code VEHICLE} 或 {@code vehicle}。</p>
 */
public final class CustodianResolver {

    private CustodianResolver() {
    }

    /**
     * @param type               释放项类型，决定应交给哪一类接收方
     * @param primaryCustodianId 主表主接收方（放车线默认），可为 {@code null}
     * @param custodianByItem    按项覆盖映射，可为 {@code null} 或空
     * @return 该项实际接收方 ID；未配置时返回 {@code null}
     */
    public static Long resolve(ReleaseItemType type, Long primaryCustodianId,
                               Map<String, Long> custodianByItem) {
        if (type == null) {
            return primaryCustodianId;
        }
        if (custodianByItem != null && !custodianByItem.isEmpty()) {
            for (Map.Entry<String, Long> entry : custodianByItem.entrySet()) {
                String key = entry.getKey();
                if (key != null && type.name().equalsIgnoreCase(key.trim().toUpperCase(Locale.ROOT))) {
                    return entry.getValue();
                }
            }
        }
        return primaryCustodianId;
    }

    /** 该项按日本渠道口径应交给哪一类接收方（用于人工交接单与配置校验提示）。 */
    public static CustodianType expectedType(ReleaseItemType type) {
        return type == null ? null : type.defaultCustodianType();
    }
}
