package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.UsedConfigParam;

import java.time.LocalDate;
import java.util.Optional;

/**
 * USED 域参数读取端口（R13.4「可调参数必须双层约束」的读侧；定义在领域层，实现在基础设施层）。
 *
 * <p><b>为什么要这张端口</b>：本包所有数值口径都还没签字，纪律是
 * {@code [待定源]} 的数字<b>只能从参数表/配置读，不许写死在 Java 里</b>。
 * 如果只有 {@code used_config_params} 表而没有读端，那张表就永远是死表，
 * 签字之后还得再开一次接口——所以表与读端同批落地，写端不在此包。</p>
 *
 * <p><b>只读，不提供写方法</b>：参数写入口的权限点是 {@code CONFIG_MAINTAIN}（后台配置域，
 * D00 §11），而 D07 §7.1 给本包划的六个端点里没有任何配置写端点。
 * 在本包里加写方法等于自派接口范围，故留给配置域，缺口已在实现说明登记。</p>
 *
 * <p><b>取不到就返回空，不返回默认值</b>：调用方必须自己决定"缺参数"的后果——
 * 预警类窗口（{@code license-expiry-alert-days}）缺失＝不产出 EXPIRING；
 * 红线开关（{@code dealer-rating-two-person-required}）缺失＝按 true 处理。
 * 这两种取舍都写在调用方，端口保持中立。</p>
 */
public interface UsedConfigRepository {

    /**
     * 取某键当前生效的参数行。
     *
     * <p>实现按 {@code status='ACTIVE'} 取（{@code uk_ucp_active_key} 保证同键最多一条 ACTIVE），
     * 并用 {@code isActiveOn(bizDate)} 复核生效区间；区间不满足时返回空，
     * 让调用方走"缺参数"分支，而不是拿一条还没生效的值。</p>
     *
     * @param paramKey 参数键
     * @param bizDate  业务日
     * @return 生效行；无则空
     */
    Optional<UsedConfigParam> findActive(String paramKey, LocalDate bizDate);

    /**
     * 取整数型参数（天数、阈值台数等）。
     *
     * @param paramKey 参数键
     * @param bizDate  业务日
     * @return 值；键缺失或行未生效返回空
     */
    Optional<Integer> findInt(String paramKey, LocalDate bizDate);

    /**
     * 取十进制型参数（比例、权重）。
     *
     * @param paramKey 参数键
     * @param bizDate  业务日
     * @return 值；缺失返回空
     */
    Optional<java.math.BigDecimal> findDecimal(String paramKey, LocalDate bizDate);

    /**
     * 取布尔型参数（开关）。
     *
     * @param paramKey 参数键
     * @param bizDate  业务日
     * @return 值；缺失返回空（调用方自行决定缺省方向）
     */
    Optional<Boolean> findBoolean(String paramKey, LocalDate bizDate);

    /**
     * 本包会用到的参数键常量。
     *
     * <p><b>键名的定源现状（如实记录）</b>：这五个键<b>不在 D00 §12 的键表里</b>——
     * §12 只给了 valuation/ltv/settlement/release 等段的键，R1 资质域的这五个还没有登记点。
     * 现在能查到的出处只有本包 V41 的列注释（那里"只列名不列值"）。
     * 因此本常量的作用是"把名字收在一处，等参数域签字时按同一个命名空间补 §12 与种子行"，
     * <b>不代表这些键已生效</b>：库里没有种子行时，全部走"缺参数"分支。</p>
     */
    /** 合规核查结论的有效天数（{@code [待定源 D-02]}）；缺失则录入时不推导 validUntil。 */
    String COMPLIANCE_VALID_DAYS = "floorplan.used.compliance-valid-days";
    /** 资质临近到期预警窗口天数（{@code [待定源]}）；缺失则不产出 EXPIRING。 */
    String LICENSE_EXPIRY_ALERT_DAYS = "floorplan.used.license-expiry-alert-days";
    /** 里程差异告警阈值 km（{@code [待定源]}）；缺失则只做优先级归一，不做差异告警。 */
    String MILEAGE_DIFF_THRESHOLD_KM = "floorplan.used.mileage-diff-threshold-km";
    /** 主数据差异是否告警（{@code [待定源]}）；缺失则不告警。 */
    String MASTER_DATA_DIFF_ALERT_ENABLED = "floorplan.used.master-data-diff-alert-enabled";
    /** 双维评级是否强制二人签认（<b>红线</b>，签字后必须为 true）；<b>缺失按 true 处理</b>。 */
    String DEALER_RATING_TWO_PERSON_REQUIRED = "floorplan.used.dealer-rating-two-person-required";
}
