package com.company.floorplan.domain.model;

import java.time.LocalDate;

/**
 * 从银行侧取回的一条流水 —— 采集适配器的输出，尚未入库。
 *
 * <p>用 record 而非实体：它没有身份（没有主键）、没有状态，
 * 只是"银行说了这么一笔钱"，属于传输结构。真正的实体是
 * {@link PaymentTransaction}，它入库后才有 id 与匹配状态。</p>
 *
 * <p><b>为什么单列一个类型而不直接复用实体</b>：
 * 适配器不应该有能力构造带匹配结果的流水。若直接返回实体，
 * 某个适配器可能顺手把 {@code matchStatus} 填成 AUTO_MATCHED，
 * 绕过了匹配层的规则与置信度校验。类型上不给这个能力，比约定"你别填"可靠。</p>
 *
 * @param transactionId 银行流水号（该账户内唯一）
 * @param accountRef    入账账户引用码
 * @param valueDate     起息日（到账日，非交易日）
 * @param amount        金额（整数日元，可为负表示冲正）
 * @param payerName     付款人名称
 * @param payerAccount  付款人账户（可能为空）
 * @param reference     摘要/备注（VIN 常在此）
 * @param raw           原始报文行（争议时自证）
 */
public record BankStatementLine(
        String transactionId,
        String accountRef,
        LocalDate valueDate,
        long amount,
        String payerName,
        String payerAccount,
        String reference,
        String raw) {

    /**
     * 最低限度校验：没有流水号就没有幂等锚点，没有金额就没有事实。
     *
     * <p>这类脏数据必须<b>在入库前拦住</b>，不能靠数据库 NOT NULL 报错——
     * 一条脏数据会让整批采集事务失败，把同批其他正常流水一起拖下水。</p>
     */
    public boolean isUsable() {
        return transactionId != null && !transactionId.isBlank()
                && accountRef != null && !accountRef.isBlank()
                && valueDate != null;
    }
}
