package com.company.floorplan.common.contract;

/**
 * 借据快照 DTO（W0-5 冻结契约，字段名写定后不许改）。
 *
 * <p><b>谁产出</b>：loan-service 的 {@code GET /api/loans/by-vin/{vin}}（W1-LOAN 交付）。
 * <b>谁消费</b>：bank-service 的回款匹配（W1-BANK）——按 VIN 找到在贷借据，才能把流水挂到具体债务上。
 * 本项目 D1 断点的根因之一就是 bank 只有 {@code LoggingLoanLookupClient} 存根（按"无在贷债务"处理），
 * 所以本 DTO 存在的意义不是"有个类"，而是让 bank 有一个真实可打到的查询口。</p>
 *
 * <p><b>本类的定位是"投影子集"，不是新的响应形状</b>：loan-service 的实际响应
 * {@code data} 是完整借据对象（{@code docs/接口契约-V16借据.md} §2.3 已冻结 15 个键），
 * 这里只取 bank 做匹配与回补判定必需、且 W1 期间不许改的 7 个。
 * Spring Boot 默认关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}，多余的 JSON 键会被忽略，
 * 因此调用方可以只读这 7 个字段而不必跟着借据实体的字段漂移。</p>
 *
 * <p><b>口径约束</b>：
 * <ul>
 *   <li>{@code principal} 是 BIGINT 整数日元，禁止 float/double 参与金额（AGENTS §6.3）；</li>
 *   <li>{@code dueDate} 用 ISO 字符串（{@code yyyy-MM-dd}），与已冻结的
 *       {@link CollateralClient.RegistrationVO} 时间字段风格一致，避免跨服务反序列化依赖 JavaTimeModule 配置；</li>
 *   <li>外层是统一 {@link ApiResponse}，成功 {@code code = "OK"}；
 *       <b>查不到借据时是 HTTP 404 + {@code NOT_FOUND_001}，不是 200 + data=null</b>
 *       ——实现方 {@code LoanController.notFound()} 就是这么写的，契约必须照实冻结。</li>
 * </ul></p>
 */
public class LoanSnapshotDto {

    /** 借据主键（{@code loans.id}） */
    private Long id;

    /** 车辆 VIN，匹配入口键 */
    private String vin;

    /** 关联垫款单 id（LOAN_CREATED 的幂等键就是它） */
    private Long advanceId;

    /** 经销商 id，额度回补按它归户 */
    private Long dealerId;

    /** 借据状态（如 ACTIVE / OVERDUE / SETTLED），取值口径以 loan-service 为准 */
    private String status;

    /** 在贷本金，整数日元 */
    private Long principal;

    /** 到期日，ISO 日期字符串 yyyy-MM-dd */
    private String dueDate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public Long getAdvanceId() { return advanceId; }
    public void setAdvanceId(Long advanceId) { this.advanceId = advanceId; }

    public Long getDealerId() { return dealerId; }
    public void setDealerId(Long dealerId) { this.dealerId = dealerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getPrincipal() { return principal; }
    public void setPrincipal(Long principal) { this.principal = principal; }

    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
}
