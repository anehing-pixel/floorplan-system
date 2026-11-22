package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

/**
 * USED 域可调参数实体（表 {@code used_config_params}，V41）。
 *
 * <p><b>这张表解决的是"数字不许写进 Java"这条纪律</b>：本轮所有带数值或数值效果的口径
 * （合规有效天数、里程差异阈值、双维权重、临近到期窗口等）都还是 {@code [待定源]}，
 * 签字后由<b>独立迁移</b>补种子行；代码侧只负责"按键取值 + 按类型解析"，
 * 因此这里<b>没有任何默认值常量</b>，也不给缺省值兜底。</p>
 *
 * <p><b>{@code value_type} 与解析</b>：类型列（V41 的 {@code ck_ucp_value_type}
 * ∈ INT/BIGINT/DECIMAL/STRING/BOOLEAN）决定解析方式，解析失败是<b>配置写错</b>，
 * 不是业务违规，所以抛 {@code SYS_001}（500）而不是 4xx——
 * 把配置事故报成参数错误会让调用方以为改改请求就能过。</p>
 */
public class UsedConfigParam {

    /** 主键。 */
    private Long id;
    /**
     * 参数键。
     *
     * <p>命名沿用 D00 §12 的统一前缀 {@code floorplan.used.}（该节明写"与 LEGACY 配置物理隔离"），
     * 但<b>本包这五个键本身还没有在 §12 登记</b>，出处仅限 V41 的列注释；
     * 已登记为待收口方补录的文档缺口。</p>
     */
    private String paramKey;
    /** 原始字符串值（解析入口，永远与库里一致）。 */
    private String paramValue;
    /** 值类型：{@code INT} / {@code BIGINT} / {@code DECIMAL} / {@code STRING} / {@code BOOLEAN}。 */
    private String valueType;
    /** 单位（JPY、DAY、PERCENT 等；仅人读，不参与换算）。 */
    private String unit;
    /** 状态：{@code DRAFT} / {@code ACTIVE} / {@code RETIRED}。 */
    private String status;
    /** 生效日（含）。 */
    private LocalDate effectiveOn;
    /** 失效日（含）；NULL 表示未设失效日。 */
    private LocalDate expiredOn;
    /** 定源编号（D-xx/U-xx）；<b>无源号的数值不许进本表</b>（V41 表注释）。 */
    private String sourceRef;
    /** 签认人。 */
    private String approvedBy;
    /** 备注。 */
    private String note;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 创建人。 */
    private String createdBy;
    /** 更新人。 */
    private String updatedBy;
    /** 乐观锁版本（V41:38）。 */
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }

    public String getParamValue() { return paramValue; }
    public void setParamValue(String paramValue) { this.paramValue = paramValue; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getEffectiveOn() { return effectiveOn; }
    public void setEffectiveOn(LocalDate effectiveOn) { this.effectiveOn = effectiveOn; }

    public LocalDate getExpiredOn() { return expiredOn; }
    public void setExpiredOn(LocalDate expiredOn) { this.expiredOn = expiredOn; }

    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    /**
     * 在指定业务日是否生效。
     *
     * <p>口径照 V41 的 {@code effective_on} 列注释：区间是半开的
     * <b>{@code [effective_on, expired_on)}</b>——到期日<b>当天已不生效</b>。
     * 两侧都允许为空（V41 未加 NOT NULL），空表示"该侧不设界"；
     * "无界"与"未采集"用同一列表达，是本表的已知含糊点，已登记实现说明等参数域签字收口。</p>
     *
     * @param bizDate 业务日
     * @return true 表示本行可用作当日取值
     */
    public boolean isActiveOn(LocalDate bizDate) {
        if (!"ACTIVE".equals(status)) {
            return false;
        }
        if (bizDate == null) {
            // 业务日都没给，无法判断区间，按不可用处理（fail-closed）。
            return false;
        }
        if (effectiveOn != null && bizDate.isBefore(effectiveOn)) {
            return false;
        }
        // 半开区间：bizDate == expiredOn 时已失效。
        return expiredOn == null || bizDate.isBefore(expiredOn);
    }

    /** 解析为整数（{@code INT}/{@code BIGINT}）。 */
    public int asInt() {
        return parse(() -> Integer.parseInt(trimmed()), "整数");
    }

    /** 解析为长整数（金额类参数用整数最小单位，禁浮点）。 */
    public long asLong() {
        return parse(() -> Long.parseLong(trimmed()), "长整数");
    }

    /** 解析为十进制（比例、阈值类）。 */
    public BigDecimal asDecimal() {
        return parse(() -> new BigDecimal(trimmed()), "十进制数");
    }

    /** 解析为布尔：仅接受 {@code true/false/1/0}（大小写不敏感），其它一律配置错误。 */
    public boolean asBoolean() {
        String v = trimmed();
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
            return false;
        }
        throw new BusinessException(ErrorCode.SYS_001,
                "参数 " + paramKey + " 声明为 BOOLEAN 但值不是 true/false/1/0：" + v);
    }

    /** 原样字符串（STRING 类型；已 trim）。 */
    public String asString() {
        return trimmed();
    }

    /** 三种数值解析共用：把 NumberFormatException 翻成"配置写错"的 SYS_001。 */
    private <T> T parse(java.util.function.Supplier<T> reader, String expect) {
        try {
            return reader.get();
        } catch (NumberFormatException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_001,
                    "参数 " + paramKey + " 声明为 " + valueType + " 但值无法解析为" + expect + "：" + paramValue);
        }
    }

    private String trimmed() {
        return paramValue == null ? "" : paramValue.trim();
    }
}
