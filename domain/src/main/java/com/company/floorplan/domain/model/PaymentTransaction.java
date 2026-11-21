package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 银行回款流水 —— <b>客观事实</b>，不是"我方认定"。
 *
 * <p><b>与 {@code Repayment} 的本质区别</b>（这是整个回款闭环的起点）：</p>
 * <table border="1">
 *   <tr><th></th><th>银行流水（本类）</th><th>还款核销（Repayment）</th></tr>
 *   <tr><td>来源</td><td>银行</td><td>我方系统操作</td></tr>
 *   <tr><td>时机</td><td>钱到账时</td><td>人工/自动核销时</td></tr>
 *   <tr><td>数量</td><td>全部入账，含与本业务无关的钱</td><td>只有已核销的</td></tr>
 *   <tr><td>作用</td><td><b>事实</b></td><td><b>认定</b></td></tr>
 * </table>
 *
 * <p>混同两者的后果：只能证明"我们认为收到了钱"，无法证明"钱真的到了指定账户"。
 * 前者是自我陈述，后者才是证据链。</p>
 *
 * <p><b>本类不带状态流转方法</b>：采集阶段只负责"记录事实"，
 * 匹配结果（{@code matchStatus}）由后续的 PAYMENT_MATCH 作业写入。
 * 若把匹配逻辑塞进采集，采集就必须理解业务语义，
 * 而银行文件的格式变化会直接污染匹配规则——这是两个不同频率的变化源。</p>
 */
public class PaymentTransaction {

    /** 主键。 */
    private Long id;

    /** 银行流水号 —— 与银行对账的唯一锚点，也是幂等键的一半。 */
    private String transactionId;

    /**
     * 入账账户引用码（不存明文账号）。
     *
     * <p>账户主数据在 {@code dealer_bank_accounts}（回款闭环设计 §1），
     * 此处只存引用码：导入模板与 API 都不接受明文账号，
     * 系统按引用查主数据——文件被转发或截屏时不会泄露账号。</p>
     */
    private String accountRef;

    /**
     * 起息日（钱真正到账那天），<b>不是交易日</b>。
     *
     * <p>日本的銀行休日与营业日不同，跨行转账可能 T+1 到账。
     * 若用交易日计息/计算逾期，会在连休前后差出整整一天。</p>
     */
    private LocalDate valueDate;

    /** 入账金额（整数日元）。正数=入金，负数=冲正（银行冲正必须记录，不能丢弃）。 */
    private long amount;

    /** 付款人名称（匹配线索：终端客户 vs 经销商自己，含义不同）。 */
    private String payerName;

    /** 付款人账户（银行可能脱敏或完全不提供，故可空）。 */
    private String payerAccount;

    /** 摘要/备注 —— <b>VIN 常留在这里</b>，是自动匹配的第一线索。 */
    private String reference;

    /** 匹配状态：UNMATCHED / AUTO_MATCHED / MANUAL_MATCHED / IGNORED / SPLIT。 */
    private String matchStatus;

    /** 匹配到的 VIN（一筆对一台车时）。 */
    private String matchedVin;

    /** 匹配到的垫款主键（比 repayments.id 更贴近"债"本身）。 */
    private Long matchedAdvanceId;

    private LocalDateTime matchedAt;

    /** 匹配操作人（人工绑定时）。 */
    private String matchedBy;

    /** 命中的匹配规则编号，事后可解释"为什么这么匹配"。 */
    private String matchRule;

    /** 匹配置信度（0-100）。 */
    private BigDecimal matchScore;

    /** 来源：BANK_API / BANK_FILE / MANUAL_UPLOAD / DEMO。 */
    private String source;

    /**
     * 采集批次号。
     *
     * <p><b>注意：它不是幂等键。</b>同一账户一天可能拉 96 次，批次号每次不同；
     * 真正的幂等锚点是 {@code (accountRef, transactionId)}，
     * 定义在数据库唯一约束 {@code uk_payment_txn} 上。</p>
     */
    private String batchNo;

    /** 原始报文行：解析失败或金额争议时，靠它证明"银行到底说了什么"。 */
    private String rawReference;

    private LocalDateTime collectedAt;

    /**
     * 构造一条"刚被采集进来"的流水。
     *
     * <p>匹配字段一律留空——采集只负责记录事实，不解释事实。
     * 这是刻意的分工：事实错了要改银行对接，解释错了要改匹配规则，
     * 两者混在一起会导致改规则时不敢动采集。</p>
     */
    public static PaymentTransaction collected(BankStatementLine line, String source, String batchNo) {
        PaymentTransaction tx = new PaymentTransaction();
        tx.transactionId = line.transactionId();
        tx.accountRef = line.accountRef();
        tx.valueDate = line.valueDate();
        tx.amount = line.amount();
        tx.payerName = line.payerName();
        tx.payerAccount = line.payerAccount();
        tx.reference = line.reference();
        tx.rawReference = line.raw();
        tx.source = source;
        tx.batchNo = batchNo;
        tx.matchStatus = "UNMATCHED";
        return tx;
    }

    /** 是否能作为匹配候选：金额必须为正，冲正流水不参与回款匹配。 */
    public boolean isMatchCandidate() {
        return amount > 0 && "UNMATCHED".equals(matchStatus);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public void setAccountRef(String accountRef) {
        this.accountRef = accountRef;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public void setValueDate(LocalDate valueDate) {
        this.valueDate = valueDate;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getPayerName() {
        return payerName;
    }

    public void setPayerName(String payerName) {
        this.payerName = payerName;
    }

    public String getPayerAccount() {
        return payerAccount;
    }

    public void setPayerAccount(String payerAccount) {
        this.payerAccount = payerAccount;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getMatchStatus() {
        return matchStatus;
    }

    public void setMatchStatus(String matchStatus) {
        this.matchStatus = matchStatus;
    }

    public String getMatchedVin() {
        return matchedVin;
    }

    public void setMatchedVin(String matchedVin) {
        this.matchedVin = matchedVin;
    }

    public Long getMatchedAdvanceId() {
        return matchedAdvanceId;
    }

    public void setMatchedAdvanceId(Long matchedAdvanceId) {
        this.matchedAdvanceId = matchedAdvanceId;
    }

    public LocalDateTime getMatchedAt() {
        return matchedAt;
    }

    public void setMatchedAt(LocalDateTime matchedAt) {
        this.matchedAt = matchedAt;
    }

    public String getMatchedBy() {
        return matchedBy;
    }

    public void setMatchedBy(String matchedBy) {
        this.matchedBy = matchedBy;
    }

    public String getMatchRule() {
        return matchRule;
    }

    public void setMatchRule(String matchRule) {
        this.matchRule = matchRule;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getRawReference() {
        return rawReference;
    }

    public void setRawReference(String rawReference) {
        this.rawReference = rawReference;
    }

    public LocalDateTime getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(LocalDateTime collectedAt) {
        this.collectedAt = collectedAt;
    }
}
