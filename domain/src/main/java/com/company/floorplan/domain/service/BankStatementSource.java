package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BankStatementException;
import com.company.floorplan.domain.model.BankStatementResult;

import java.time.LocalDate;

/**
 * 银行流水采集端口（防腐层入口）。
 *
 * <p><b>为什么是端口而不是直接用某个银行的 SDK</b>：
 * 日本中小银行大多只提供文件交换（MT940/CSV，走 SFTP 或网银导出），
 * 少数能银企直连。若业务代码直接依赖某一种通道，
 * 换一家银行就要改业务流程——而业务流程是最不该跟着银行变的东西。</p>
 *
 * <p>本接口的实现都落在 infrastructure 层，application 只认这个接口。
 * 这也是"采集方式与匹配逻辑完全解耦"的落点：
 * 无论流水从 API、文件还是人工上传进来，下游看到的都是 {@link BankStatementResult}。</p>
 */
public interface BankStatementSource {

    /**
     * 来源标识，写入 {@code bank_fetch_batches.source} 与
     * {@code payment_transactions.source}（BANK_API / BANK_FILE / MANUAL_UPLOAD / DEMO）。
     */
    String source();

    /**
     * 拉取指定账户在 {@code [from, to]} 区间内的流水。
     *
     * <p><b>允许返回重复数据</b>：本方法不负责去重。原因有两个——</p>
     * <ol>
     *   <li>拉取窗口刻意重叠（银行入账有延迟，近几天的钱随时可能补记），
     *       重叠必然带来重复；</li>
     *   <li>去重必须发生在"写入"那一刻才可靠。在内存里去重，
     *       两个实例各自拉取时仍会各写一遍。</li>
     * </ol>
     * <p>所以去重的唯一责任方是数据库唯一约束
     * {@code uk_payment_txn (account_ref, transaction_id)}。</p>
     *
     * <p><b>异常语义</b>：无法完成拉取时抛 {@link BankStatementException}，
     * 不要返回空结果——"今天没有新流水"和"拉取失败"是完全不同的两件事，
     * 混成空结果会让失败静默，连续失败三天也无人发现。</p>
     *
     * @param accountRef 账户引用码
     * @param from       窗口起（含）
     * @param to         窗口止（含）
     */
    BankStatementResult fetch(String accountRef, LocalDate from, LocalDate to);
}
