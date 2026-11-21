package com.company.floorplan.domain.model;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;

import java.util.Map;

/**
 * 经销商（借款人）聚合根。
 *
 * <p><b>业务定位</b>：本系统服务于金融公司内部、面向<b>新车渠道</b>的 B2B 业务，
 * 借款人是<b>品牌授权经销商（法人）</b>，不是个人消费者。
 * 这也是为什么本场景不适用个人借款的"总量规制"约束（设计文档 2.6）。</p>
 *
 * <p><b>与额度的关系</b>：经销商本身不直接持有额度余额，
 * 授信上限的约束由 {@link CreditLine} 聚合根守护，
 * 两者通过 dealerId 关联（一个经销商可有多条额度/多个产品）。</p>
 *
 * <p><b>权限边界</b>：经销商是"被授信方"。第一版<b>不提供对外车商门户</b>
 * （设计文档 1.2 明确不做），相关操作由金融公司内部人员代为执行。</p>
 *
 * <p><b>档案与台账的分离</b>：本类只承载<b>主数据</b>（档案信息 + 状态 + 评级）。
 * 名下的车辆台账、授信额度属于其他聚合，通过 dealerId 关联，
 * 不可塞进经销商对象里——否则一个经销商有几万台车时，这个对象会被撑爆。</p>
 */
public class Dealer {

    /** 主键。 */
    private Long id;

    /** 经销商编号（业务唯一键，用于业务对账与外部系统对接）。 */
    private String dealerCode;

    /** 经销商名称（法人全称）。 */
    private String name;

    /**
     * 状态：{@link DealerStatus}。
     * 仅 {@code ACTIVE} 的经销商可发生新的垫款业务。
     */
    private DealerStatus status;

    /**
     * 授信评级：A / B / C / D。
     *
     * <p>影响利率加点幅度（评级越好加点越低）。
     * 等级取值必须在评级口径表（{@code rating_configs}）中存在且未停用，
     * 该校验由应用层负责（领域层不依赖其他聚合）。</p>
     */
    private String riskGrade;

    /** 经营年限（年），授信评级的参考因子之一。 */
    private Integer businessYears;

    /** 联系人姓名（授信准入阶段登记的接口人）。 */
    private String contactPerson;

    /** 联系电话。 */
    private String contactPhone;

    /** 营业地址。 */
    private String address;

    /** 备注（如"客户确认口径 2026-10 版"）。 */
    private String remark;

    public Dealer() {
    }

    /**
     * 是否处于正常经营（可授信）状态。
     *
     * @return true 表示状态为 ACTIVE
     */
    public boolean isActive() {
        return status == DealerStatus.ACTIVE;
    }

    /**
     * 修改档案信息（编辑经销商）。
     *
     * <p><b>为什么把字段判空放在领域层</b>：编号与名称是主数据的主键性字段，
     * 一旦被写成空白，后续所有对账与外部接口定位都会失效。
     * 在聚合根守住这个不变量，比在每个调用方各写一遍校验更可靠。</p>
     *
     * @param dealerCode     新编号
     * @param name           新名称
     * @param contactPerson  联系人
     * @param contactPhone   联系电话
     * @param address        营业地址
     * @param remark         备注
     * @throws BusinessException 编号或名称为空抛 {@code VALID_001}
     */
    public void updateProfile(String dealerCode, String name, String contactPerson,
                               String contactPhone, String address, String remark) {
        if (dealerCode == null || dealerCode.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "经销商编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "经销商名称不能为空");
        }
        this.dealerCode = dealerCode.trim();
        this.name = name.trim();
        this.contactPerson = blankToNull(contactPerson);
        this.contactPhone = blankToNull(contactPhone);
        this.address = blankToNull(address);
        this.remark = blankToNull(remark);
    }

    /**
     * 状态流转（后台启停 / 终止合作）。
     *
     * @param target 目标状态
     * @throws BusinessException 非法流转抛 {@code DEALER_002}
     */
    public void changeStatus(DealerStatus target) {
        if (target == null) {
            throw new BusinessException(ErrorCode.DEALER_002, "目标状态不能为空");
        }
        if (!this.status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.DEALER_002, Map.of(
                    "currentStatus", this.status.name(),
                    "targetStatus", target.name(),
                    "reason", "状态流转非法（终止后不可回退）"));
        }
        this.status = target;
    }

    /** 空白字符串统一归一为 null，避免库里存一堆空串。 */
    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDealerCode() {
        return dealerCode;
    }

    public void setDealerCode(String dealerCode) {
        this.dealerCode = dealerCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DealerStatus getStatus() {
        return status;
    }

    public void setStatus(DealerStatus status) {
        this.status = status;
    }

    public String getRiskGrade() {
        return riskGrade;
    }

    public void setRiskGrade(String riskGrade) {
        this.riskGrade = riskGrade;
    }

    public Integer getBusinessYears() {
        return businessYears;
    }

    public void setBusinessYears(Integer businessYears) {
        this.businessYears = businessYears;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public void setContactPerson(String contactPerson) {
        this.contactPerson = contactPerson;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
