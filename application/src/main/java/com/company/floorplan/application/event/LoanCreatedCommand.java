package com.company.floorplan.application.event;

/**
 * {@code LOAN_CREATED} 事件的<b>发布命令</b>（应用层端口，纯 POJO）。
 *
 * <p><b>为什么在 application 层另立一个命令，而不是直接用 {@code common} 的
 * {@code OutboxPublisher}</b>：依赖方向是 {@code common → application}
 * （{@code JwtAuthInterceptor} 要用 {@code AuthApplicationService.verify}），
 * 所以 application <b>不能</b>反向 import {@code common.contract.OutboxPublisher}——
 * 加了就是 Maven 循环依赖，{@code clean package} 当场炸。
 * 于是这里只声明"要发什么"，把"怎么序列化、写到哪张表"留给 web 层的适配器
 * （{@code OutboxLoanCreatedPublisher}），它同时看得见 application 与 common。</p>
 *
 * <p><b>字段口径以 {@code docs/接口契约-事件与Feign.md §2.1} 为唯一冻结源</b>，
 * 与消费方 {@code loan-service} 的
 * {@code LoanApplicationService.LoanCreatedEvent} 逐字段对齐。本类刻意保持可纯单测：
 * 不注入 {@code JdbcTemplate}、不读配置、不 {@code new} 客户端（{@code AGENTS §10}）。</p>
 *
 * <p><b>为什么没有 {@code dueDate} / {@code gracePeriodDays}</b>：这两个字段在契约里是"建议"，
 * core 侧<b>没有权威源</b>——唯一像样的出处 {@code financing_terms}（V11）全仓零写入方，
 * 表是空的。业务未定源（{@code PROGRESS §6} 的 Q-EVT3），按"不发"处理，
 * <b>不许</b>用 {@code paidAt + 90 天}这类自造默认值凑完整（AGENTS 铁律 3）。
 * 后果是可接受的下界：借据照建，消费方 {@code generateRepaymentPlan} 遇 {@code dueDate == null}
 * 打 WARN 后跳过还款计划，不抛异常、不阻断建据。</p>
 *
 * @param vin          车架号，发布端 {@code JsonPayloadReader.requireVinAndTraceId} 校验必填
 * @param advanceId    垫款主键 {@code advances.id}。<b>同时是 aggregate_key 与业务幂等锚点</b>
 * @param dealerId     经销商 id（画像归户用），取自放款确认时已加载的车辆台账
 * @param creditLineId 授信额度 id（额度归属用）
 * @param principal    借据本金，<b>整数日元</b>（BIGINT 口径，禁止小数参与金额计算）
 * @param traceId      全链路追踪号，必须由 {@code AdvanceController} 从请求属性显式透传上来
 */
public record LoanCreatedCommand(String vin,
                                 long advanceId,
                                 Long dealerId,
                                 Long creditLineId,
                                 long principal,
                                 String traceId) {
}
