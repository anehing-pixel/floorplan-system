package com.company.floorplan.domain.repository;

import java.util.Optional;

/**
 * USED 域可调参数读取（{@code used_config_params}，D06 §16 / D04 §11 / V41）。
 *
 * <p><b>为什么要有这条路</b>：V41 的注释把读侧链条写死了——
 * 「读到 ACTIVE 行 ⇒ 用库内值；读不到 ⇒ 用请求显式传入值；两者都没有 ⇒ fail-closed」。
 * 本接口实现第一段，并<b>只</b>负责取原始值，语义解释交给调用方。</p>
 *
 * <p><b>为什么参数落在库里而不是 yml 或 Java 常量</b>：</p>
 * <ul>
 *   <li>Java 常量：未签字数值硬编码后不可回收，业务改一次要发一次版；</li>
 *   <li>yml：V41 明确反对（「把未签数值换个地方硬编码」），且改值要重启，
 *       而风控参数的调整通常发生在营业中；</li>
 *   <li>库表：带 {@code effective_on/expired_on} 版本区间与 {@code source_ref} 溯源列，
 *       正好满足 D04 §11「可调参数必须双层约束（谁能改 + 从什么时候生效）」。</li>
 * </ul>
 *
 * <p><b>缺行不是错误</b>：返回 {@code Optional#empty()} 由调用方决定 fail-closed 的具体形状
 * （是拒绝业务、还是走更保守的默认判定）。在这里抛异常会让所有「还没配参数」的环境直接起不来。</p>
 */
public interface UsedConfigRepository {

    /** 取某键当前生效的原始字符串值（{@code status='ACTIVE'} 且在生效区间内）。 */
    Optional<String> findActiveValue(String paramKey);

    /** 取整型值；值存在但不是合法整数时返回 empty（脏值不猜，由调用方 fail-closed）。 */
    default Optional<Integer> findInt(String paramKey) {
        return findActiveValue(paramKey).flatMap(v -> {
            try {
                return Optional.of(Integer.valueOf(v.trim()));
            } catch (NumberFormatException ex) {
                // 参数写错必须是「查不到值」而不是「应用起不来」，但也不能静默：
                // 由实现类在日志里打 WARN（见 JdbcUsedConfigRepository），此处只降级为 empty。
                return Optional.empty();
            }
        });
    }

    /** 取布尔值：只认 {@code true/false/1/0/Y/N}（大小写不敏感），其余视为脏值 → empty。 */
    default Optional<Boolean> findBoolean(String paramKey) {
        return findActiveValue(paramKey).flatMap(v -> {
            String s = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("y")) {
                return Optional.of(Boolean.TRUE);
            }
            if (s.equals("false") || s.equals("0") || s.equals("n")) {
                return Optional.of(Boolean.FALSE);
            }
            return Optional.empty();
        });
    }

    /**
     * 取比率/阈值型参数（{@code value_type='DECIMAL'}）为 {@link java.math.BigDecimal}。
     *
     * <p>不用 {@code double}：本仓所有比例都参与金额乘法（{@code FLOOR(prudent × ratio)}），
     * 二进制浮点会让同一个数在不同机器上落到上限的另一侧。值脏（非数字）时同样回 empty，
     * 由调用方 fail-closed。</p>
     */
    default Optional<java.math.BigDecimal> findDecimal(String paramKey) {
        return findActiveValue(paramKey).flatMap(v -> {
            try {
                return Optional.of(new java.math.BigDecimal(v.trim()));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        });
    }

    /** 参数台账（后台列表用）：按状态筛，状态为 null 表示全部。 */
    java.util.List<ConfigParam> list(String status);
    /**
     * 参数行视图。
     *
     * <p>{@code sourceRef} 与 {@code approvedBy} 必须带出来：D04 §11 要求「无源号的数值不许进本表」，
     * 后台页面若看不到源号，就无法在改值时判断这个数是不是已签字口径。</p>
     */
    record ConfigParam(String paramKey, String paramValue, String valueType, String unit,
                       String status, java.time.LocalDate effectiveOn, java.time.LocalDate expiredOn,
                       String sourceRef, String approvedBy, String note) {
    }

    /** 新增或替换某键的生效值：旧 ACTIVE 行转 {@code RETIRED}（本表禁 DELETE），新行 {@code ACTIVE}。 */
    void putActive(ConfigParam param, String operator);
}
