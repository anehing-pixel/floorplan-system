package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 授信评级分级口径（后台动态配置）。
 *
 * <p><b>为什么做成可配置</b>：分级口径在业务上标注为"⚠️【预设】待客户确认"，
 * 意味着它<b>必然会被调整</b>。若写死在代码里，每次调整都要发版；
 * 因此口径落库（{@code rating_configs} 表），由管理接口动态维护，
 * 变更即时生效——下一次垫款建账即按新口径执行。</p>
 *
 * <p><b>口径包含两件事</b>：</p>
 * <ol>
 *   <li><b>利率加点</b>（{@link #rateSurcharge}）：评级越好加点越低。
 *       实际执行利率 = 基准年利率 + 加点；</li>
 *   <li><b>准入开关</b>（{@link #allowed}）：等级停用后，该等级经销商
 *       不再受理<b>新增</b>垫款建账（存量车辆的还款、结清等业务不受影响）。</li>
 * </ol>
 *
 * <p><b>领域层约束</b>：本类是纯 POJO，不依赖 Spring；
 * 变更留痕（操作人/时间）由应用层在保存时写入。</p>
 */
public class RatingConfig {

    /** 等级代码（主键）：如 A / B / C / D，1~8 位大写字母或数字。 */
    private String grade;

    /** 等级名称（展示用），如"优质 / 标准 / 关注 / 观察"。 */
    private String gradeName;

    /**
     * 年化利率加点（小数：0.005 = +0.5%）。
     * 实际执行利率 = 基准年利率(floorplan.interest.default-annual-rate) + 本值。
     */
    private BigDecimal rateSurcharge;

    /** 是否准入新业务：false = 停用（仅限制新增垫款建账，不影响存量业务）。 */
    private boolean allowed;

    /** 展示排序（越小越靠前）。 */
    private int sortOrder;

    /** 备注（如"预设口径，待客户确认"）。 */
    private String remark;

    /** 最后变更时间（口径变更必须留痕）。 */
    private LocalDateTime updatedAt;

    /** 最后变更操作人。 */
    private String updatedBy;

    public RatingConfig() {
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getGradeName() {
        return gradeName;
    }

    public void setGradeName(String gradeName) {
        this.gradeName = gradeName;
    }

    public BigDecimal getRateSurcharge() {
        return rateSurcharge;
    }

    public void setRateSurcharge(BigDecimal rateSurcharge) {
        this.rateSurcharge = rateSurcharge;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
