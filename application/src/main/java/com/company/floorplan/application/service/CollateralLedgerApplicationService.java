package com.company.floorplan.application.service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.BankAccount;
import com.company.floorplan.domain.model.CancellationStatus;
import com.company.floorplan.domain.model.CollateralEvidenceLink;
import com.company.floorplan.domain.model.CollateralLedger;
import com.company.floorplan.domain.model.CollateralRegistrationBatch;
import com.company.floorplan.domain.model.EvidenceType;
import com.company.floorplan.domain.model.PerfectionMethod;
import com.company.floorplan.domain.model.SecurityStatus;
import com.company.floorplan.domain.model.SecurityType;
import com.company.floorplan.domain.repository.BankAccountRepository;
import com.company.floorplan.domain.repository.CollateralEvidenceRepository;
import com.company.floorplan.domain.repository.CollateralLedgerRepository;
import com.company.floorplan.domain.repository.CollateralRegistrationBatchRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 担保物台账应用服务（台账的编排核心）。
 *
 * <p><b>这个服务解决什么</b>：担保在系统里原先只是车辆台账上的两个字段，
 * 没有独立台账，导致三个真实缺口——</p>
 * <ol>
 *   <li>无法回答"这台车的担保登在哪里、编号多少、证明书在哪、抹了没有"；</li>
 *   <li>车卖出去后担保物清单不会自动扣减，库存口径与担保物口径混在一起；</li>
 *   <li>结清只翻状态位，不产出抹消登记待办——
 *       而日本让渡担保的抹消登记需双方共同申请，漏掉就等于担保一直挂着。</li>
 * </ol>
 *
 * <p><b>台账与业务动作的挂钩点</b>（本服务是唯一写台账的地方）：</p>
 * <pre>
 *   放款确认（银行回调 PAID） → markFinanced()      已放款 + 证据链(ADVANCE)
 *   标记售出                → markSold()          已售出 + 扣减担保物清单 + 触发回款义务
 *   还款核销                → markRepaid()        已回款 + 证据链(REPAYMENT)
 *   结清 / 处置完成          → markReleased()      已解除担保 + 抹消待办(REQUESTED)
 *   实地盘点                → recordAudit()       盘点痕迹 + 证据链(INVENTORY_AUDIT)
 *   设定担保登记            → registerCollateral() 登记档案 + 证据链(REGISTRATION_CERT)
 *   抹消登记完成            → completeCancellation() 抹消凭证 + 证据链(CANCELLATION_CERT)
 * </pre>
 *
 * <p><b>法律要点（决定了这里的校验逻辑，勿随意放宽）</b>：</p>
 * <ul>
 *   <li>让渡担保登记有 <b>5 年期限且不可续展</b>——到期须重新设定，
 *       故登记时自动推算失效日，并产出到期提醒；</li>
 *   <li>抹消登记<b>需双方共同申请</b>——故抹消是"待办 → 已提交 → 完成"三段，
 *       不是一步到位；</li>
 *   <li>2025 年新法（2025-05-30 成立、2025-06-06 公布，施行日最晚 2027-12-06）
 *       明确<b>占有改定劣后于登记</b>（同法第 36 条）——
 *       故登记档案记对抗要件方式，可算出顺位风险。</li>
 * </ul>
 */
@Service
public class CollateralLedgerApplicationService {

    private final CollateralLedgerRepository ledgerRepository;
    private final CollateralRegistrationBatchRepository batchRepository;
    private final CollateralEvidenceRepository evidenceRepository;
    private final BankAccountRepository bankAccountRepository;
    private final SupportRepository supportRepository;

    /** 让渡担保登记有效期（年）：法定 5 年且不可续展。 */
    @Value("${floorplan.collateral.registration-validity-years:5}")
    private int registrationValidityYears;

    /** 登记到期预警提前天数。 */
    @Value("${floorplan.collateral.registration-warn-days:90}")
    private int registrationWarnDays;

    /** 回款账户核验要求的默认容差：账上未指定时，是否要求必须核验。 */
    @Value("${floorplan.collateral.require-account-check:true}")
    private boolean requireAccountCheck;

    public CollateralLedgerApplicationService(CollateralLedgerRepository ledgerRepository,
                                               CollateralRegistrationBatchRepository batchRepository,
                                               CollateralEvidenceRepository evidenceRepository,
                                               BankAccountRepository bankAccountRepository,
                                               SupportRepository supportRepository) {
        this.ledgerRepository = ledgerRepository;
        this.batchRepository = batchRepository;
        this.evidenceRepository = evidenceRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.supportRepository = supportRepository;
    }

    // =====================================================================
    // 业务挂钩点
    // =====================================================================

    /**
     * 放款确认：建立担保物台账行 + 写放款证据。
     *
     * <p>由垫款确认（银行 webhook → PAID）调用。这是台账的起点：
     * 从此刻起这台车进入担保物清单，其后的库存、登记、盘点、处置
     * 全部围绕这个 VIN 展开。</p>
     *
     * @param vin        车架号
     * @param dealerId   归属经销商
     * @param advanceNo  垫款单号（证据链锚点）
     * @param amount     放款金额
     * @param actor      操作人
     */
    @Transactional
    public void markFinanced(String vin, Long dealerId, String advanceNo, long amount, String actor) {
        CollateralLedger ledger = loadOrCreate(vin, dealerId);
        ledger.setFinanced(true);
        ledger.setFinancedAt(LocalDateTime.now());
        ledger.setAdvanceNo(advanceNo);
        ledger.setSecurityStatus(SecurityStatus.PENDING.name());
        ledgerRepository.save(ledger);

        addEvidence(ledger, EvidenceType.ADVANCE, "INTERNAL", advanceNo, null,
                LocalDateTime.now(), "放款确认，金额=" + amount, actor);
    }

    /**
     * 标记售出：扣减担保物清单 + 触发回款义务。
     *
     * <p><b>库存释放规则</b>：经销商卖出一台车后，系统自动扣减担保物清单
     * （{@code sold=true}），并要求对应回款进入指定账户或用于还款。
     * 本方法负责扣减与"应回款"义务的建立，具体回款核验在 {@link #markRepaid}。</p>
     *
     * <p><b>为什么扣减的是"担保物清单"而不是直接解担保</b>：
     * 车卖出后担保<b>仍然存在</b>（直到债务还清并办完抹消登记），
     * 只是它不再构成"在库担保物"。把这两件事混为一谈会导致
     * "卖掉即解担保"——那等于车一卖出去我们的担保就没了，
     * 而钱还没回来，正是风险最大的时候。</p>
     *
     * @param vin      车架号
     * @param soldAt   售出时间
     * @param actor    操作人
     */
    @Transactional
    public void markSold(String vin, LocalDateTime soldAt, String actor) {
        CollateralLedger ledger = requireLedger(vin);
        ledger.setSold(true);
        ledger.setSoldAt(soldAt == null ? LocalDateTime.now() : soldAt);
        ledger.setSoldFlaggedBy(actor);
        // 扣减担保物清单 = 该车不再是"在库担保物"，但担保权益仍在
        // （未回款、未抹消前不得视为已解除）
        ledgerRepository.save(ledger);

        addEvidence(ledger, EvidenceType.CONTRACT, "INTERNAL", "SOLD:" + vin, null,
                ledger.getSoldAt(), "售出，担保物清单已扣减；回款须进入指定账户或用于还款", actor);
    }

    /**
     * 回款核销：标记已回款 + 写回款证据。
     *
     * <p><b>库存释放规则的核验点</b>：回款须来自指定账户
     * （{@code requiredAccountRef}）或被标记为"专用于本台还款"。
     * 两者都不满足时<b>不阻断核销</b>（钱已经到账了，阻断会让账实不符），
     * 但会生成待办并标记台账异常，由贷后管理岗追查。</p>
     *
     * @param vin             车架号
     * @param amount          回款金额
     * @param accountRef      实际回款账户引用码（可空）
     * @param designatedUse   是否被指定专用于偿还本台垫款
     * @param actor           操作人
     */
    @Transactional
    public void markRepaid(String vin, long amount, String accountRef, boolean designatedUse, String actor) {
        CollateralLedger ledger = requireLedger(vin);
        ledger.setRepaid(true);
        ledger.setRepaidAt(LocalDateTime.now());
        long prev = ledger.getRepaidAmount() == null ? 0L : ledger.getRepaidAmount();
        ledger.setRepaidAmount(prev + amount);

        // ---- 库存释放规则核验 ----
        boolean accountOk = true;
        String required = ledger.getRequiredAccountRef();
        if (required != null && !required.isBlank()) {
            accountOk = required.equals(accountRef);
        } else if (requireAccountCheck && !designatedUse) {
            // 未指定回款账户，且该笔回款未标记为专用于还款 → 无法确认落点
            accountOk = false;
        }
        if (!accountOk) {
            ledger.setRegistrationDue(true);
            ledger.setRegistrationNote("回款落点未核验：要求账户=" + required
                    + " 实际=" + accountRef + " 专用于还款=" + designatedUse);
        }
        ledgerRepository.save(ledger);

        addEvidence(ledger, EvidenceType.REPAYMENT, "INTERNAL", "REPAID:" + vin, null,
                ledger.getRepaidAt(),
                "回款 " + amount + " 日元，账户=" + (accountRef == null ? "未指定" : accountRef)
                        + "，专用于还款=" + designatedUse + (accountOk ? "" : "（落点待核验）"),
                actor);
    }

    /**
     * 解除担保（结清或处置完成后）：标记已解除 + 生成抹消登记待办。
     *
     * <p><b>为什么结清不等于担保已解除</b>：债务还清只是让担保消灭的<b>实体条件</b>成立，
     * 登记簿上要让渡担保还挂着，直到完成<b>抹消登记</b>。
     * 日本实务中抹消需权利人与登记义务人共同申请，不是金融公司单方能办完的。
     * 所以这里把两件事拆开：台账标"已解除担保"（实体权利消灭），
     * 登记档案挂"待抹消"（公示尚未消灭）。</p>
     *
     * @param vin    车架号
     * @param reason 解除原因：SETTLED / DISPOSED / WRITTEN_OFF
     * @param actor  操作人
     */
    @Transactional
    public void markReleased(String vin, String reason, String actor) {
        CollateralLedger ledger = requireLedger(vin);
        ledger.setReleased(true);
        ledger.setReleasedAt(LocalDateTime.now());
        ledger.setReleaseReason(reason);
        // 担保实体权利消灭 → 登记需办抹消 → 产生待办。
        // 抹消是批次级动作（一次登记覆盖一批车），故按批次挂待办。
        List<CollateralRegistrationBatch> batches = batchRepository.findByVin(vin);
        // requiresCancellation() 覆盖全部"将来需要抹消"的情形
        // （NOT_DUE / REQUESTED / APPLIED），排除"永远不需要"的
        // （NOT_REQUIRED / CANCELLED）与已完成的（DONE）。
        boolean needCancellation = batches.stream()
                .anyMatch(b -> CancellationStatus.valueOf(b.getCancellationStatus()).requiresCancellation());
        if (needCancellation) {
            ledger.setRegistrationDue(true);
            ledger.setRegistrationNote("债务已结清（" + reason + "），抹消登记待共同申请");
            for (CollateralRegistrationBatch b : batches) {
                if (!CancellationStatus.valueOf(b.getCancellationStatus()).requiresCancellation()) {
                    continue;
                }
                b.setCancellationStatus(CancellationStatus.REQUESTED.name());
                b.setCancellationRequestedBy(actor);
                b.setCancellationNote("债务结清后办理抹消登记（需权利人与登记义务人共同申请）");
                batchRepository.save(b);
            }
        }
        ledgerRepository.save(ledger);
    }

    /**
     * 实地盘点：记录盘点痕迹 + 写盘点证据。
     *
     * @param vin            车架号
     * @param auditDate      盘点日
     * @param mismatch       是否有账实差异
     * @param actor          操作人
     */
    @Transactional
    public void recordAudit(String vin, java.time.LocalDate auditDate, boolean mismatch, String actor) {
        CollateralLedger ledger = requireLedger(vin);
        ledger.setLastAuditDate(auditDate == null ? java.time.LocalDate.now() : auditDate);
        ledger.setLastAuditBy(actor);
        if (mismatch) {
            ledger.setAuditMismatchCount(ledger.getAuditMismatchCount() + 1);
            ledger.setRegistrationDue(true);
            ledger.setRegistrationNote("盘点发现账实差异（累计 " + ledger.getAuditMismatchCount() + " 次）");
        }
        ledgerRepository.save(ledger);

        addEvidence(ledger, EvidenceType.INVENTORY_AUDIT, "INTERNAL", "AUDIT:" + vin, null,
                LocalDateTime.now(),
                "盘点 " + ledger.getLastAuditDate() + (mismatch ? "，账实差异" : "，账实相符"), actor);
    }

    /**
     * 处置完成：记录处置痕迹 + 写处置证据。
     *
     * @param vin       车架号
     * @param caseNo    处置工单号
     * @param actor     操作人
     */
    @Transactional
    public void markDisposed(String vin, String caseNo, String actor) {
        CollateralLedger ledger = requireLedger(vin);
        ledger.setDisposalCaseNo(caseNo);
        ledger.setDisposedAt(LocalDateTime.now());
        ledgerRepository.save(ledger);
        addEvidence(ledger, EvidenceType.DISPOSAL, "INTERNAL", caseNo, null,
                LocalDateTime.now(), "违约处置，工单=" + caseNo, actor);
    }

    // =====================================================================
    // 登记档案管理
    // =====================================================================

    /**
     * 登记担保（<b>批次</b>：一次法务局登记，涵盖一批车）。
     *
     * <p><b>业务流程</b>：审核人员与经销商签一笔让渡担保合同 →
     * 向东京法务局动产登记课做一次登记 → 取得登记编号 →
     * 把登记编号与担保合同文件管理到这一批车上。</p>
     *
     * <p><b>关键校验</b>：</p>
     * <ol>
     *   <li>登记编号在库内唯一——法务省登记编号是外部唯一标识，重复即数据错误；</li>
     *   <li>登记日必填——没有登记日就算不出 5 年失效日；</li>
     *   <li>失效日自动按登记日 + 5 年推算（法定 5 年、不可续展）；</li>
     *   <li>车辆清单非空——批次必须至少覆盖一台车，否则登记无从落地。</li>
     * </ol>
     *
     * @param vins                   本批次覆盖的车架号清单
     * @param registrationNo        法务省登记编号（字符串）
     * @param registrationType      担保方式
     * @param perfectionMethod      对抗要件方式
     * @param registryOffice        登记机关（如"東京法務局 動産登記課"）
     * @param registeredAt          登记日
     * @param expiresAt             失效日（可空，自动推算）
     * @param contractAttachmentId 担保合同附件 ID（S3；担保权成立的实体依据）
     * @param contractNo            合同编号
     * @param contractSignedAt      合同签署日
     * @param certificateAttachmentId 登记事项证明书附件 ID（担保权成立的公示依据）
     * @param certificateNo         证明书编号
     * @param actor                 操作人
     */
    @Transactional
    public Map<String, Object> registerCollateralBatch(List<String> vins,
                                                      String registrationNo,
                                                      SecurityType registrationType,
                                                      PerfectionMethod perfectionMethod,
                                                      String registryOffice,
                                                      LocalDateTime registeredAt,
                                                      LocalDateTime expiresAt,
                                                      Long contractAttachmentId,
                                                      String contractNo,
                                                      LocalDateTime contractSignedAt,
                                                      Long certificateAttachmentId,
                                                      String certificateNo,
                                                      String actor) {
        if (registrationNo == null || registrationNo.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "登记编号不能为空");
        }
        if (registeredAt == null) {
            throw new BusinessException(ErrorCode.VALID_001, "登记日不能为空（否则算不出失效日）");
        }
        if (vins == null || vins.isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001, "本批次必须至少覆盖一台车");
        }
        if (batchRepository.findByRegistrationNo(registrationNo).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    Map.of("registrationNo", registrationNo, "reason", "DUPLICATE_REGISTRATION_NO"));
        }
        LocalDateTime expiry = expiresAt != null ? expiresAt
                : registeredAt.plusYears(registrationValidityYears);

        CollateralRegistrationBatch batch = new CollateralRegistrationBatch();
        batch.setBatchNo("CRB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        batch.setRegistrationNo(registrationNo);
        batch.setRegistrationType(registrationType == null ? SecurityType.ASSIGNMENT.name() : registrationType.name());
        batch.setPerfectionMethod(perfectionMethod == null ? PerfectionMethod.REGISTRATION.name() : perfectionMethod.name());
        batch.setRegistryOffice(registryOffice);
        batch.setRegisteredAt(registeredAt);
        batch.setExpiresAt(expiry);
        batch.setContractAttachmentId(contractAttachmentId);
        batch.setContractNo(contractNo);
        batch.setContractSignedAt(contractSignedAt);
        batch.setCertificateAttachmentId(certificateAttachmentId);
        batch.setCertificateNo(certificateNo);
        batch.setCertificateUploadedAt(certificateAttachmentId == null ? null : LocalDateTime.now());
        // 登记刚设立时用 NOT_DUE（未到抹消时点）——不是 NOT_REQUIRED。
        // NOT_REQUIRED 表示"永远不需要抹消"，用它会导致债务结清后不产生抹消待办，
        // 担保在登记簿上一直挂着却无人跟进（这是实际踩过的 bug）。
        batch.setCancellationStatus(CancellationStatus.NOT_DUE.name());
        batch.setCreatedBy(actor);
        batch.setVehicleCount(vins.size());
        batchRepository.save(batch);

        // 逐台挂接台账 + 推进担保状态 + 写证据链
        List<String> skipped = new ArrayList<>();
        for (String vin : vins) {
            CollateralLedger ledger = ledgerRepository.findByVin(vin).orElse(null);
            if (ledger == null) {
                // 该车未放款确认 → 无台账行，不纳入本批次
                skipped.add(vin);
                continue;
            }
            batchRepository.addItem(batch.getId(), ledger.getId(), vin);
            ledger.setSecurityType(batch.getRegistrationType());
            ledger.setSecurityStatus(SecurityStatus.REGISTERED.name());
            ledgerRepository.save(ledger);

            // 证据链：合同与证明书挂在每台车下（共享同一份文件，但每台车都要能查到）
            if (contractAttachmentId != null) {
                addEvidence(ledger, EvidenceType.CONTRACT, "ATTACHMENT",
                        String.valueOf(contractAttachmentId), contractAttachmentId,
                        contractSignedAt == null ? registeredAt : contractSignedAt,
                        "让渡担保合同，登记编号=" + registrationNo + "，批次=" + batch.getBatchNo(), actor);
            }
            if (certificateAttachmentId != null) {
                addEvidence(ledger, EvidenceType.REGISTRATION_CERT, "ATTACHMENT",
                        String.valueOf(certificateAttachmentId), certificateAttachmentId,
                        registeredAt, "登记事项证明书，登记编号=" + registrationNo, actor);
            }
        }
        // 实际挂上车数回写
        batch.setVehicleCount(vins.size() - skipped.size());
        batchRepository.save(batch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batch.getId());
        data.put("batchNo", batch.getBatchNo());
        data.put("registrationNo", registrationNo);
        data.put("perfectionMethod", batch.getPerfectionMethod());
        data.put("registryOffice", registryOffice);
        data.put("registeredAt", registeredAt.toString());
        data.put("expiresAt", expiry.toString());
        data.put("contractArchived", contractAttachmentId != null);
        data.put("certificateArchived", certificateAttachmentId != null);
        data.put("vehicleCount", batch.getVehicleCount());
        data.put("skippedVins", skipped);
        if (!skipped.isEmpty()) {
            data.put("skipReason", "这些车尚未放款确认，无担保物台账行，未纳入本批次");
        }
        return data;
    }

    /**
     * 发起抹消登记待办（双方共同申请）。
     *
     * @param registrationId 登记档案 ID
     * @param note           申请说明
     * @param actor          操作人
     */
    @Transactional
    public Map<String, Object> requestCancellation(Long batchId, String note, String actor) {
        CollateralRegistrationBatch batch = findBatch(batchId);
        if (CancellationStatus.DONE.name().equals(batch.getCancellationStatus())) {
            throw new BusinessException(ErrorCode.VALID_001, "该登记已抹消完成");
        }
        batch.setCancellationStatus(CancellationStatus.REQUESTED.name());
        batch.setCancellationRequestedBy(actor);
        batch.setCancellationNote(note);
        batchRepository.save(batch);
        syncLedgerDue(batch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("batchNo", batch.getBatchNo());
        data.put("registrationNo", batch.getRegistrationNo());
        data.put("cancellationStatus", batch.getCancellationStatus());
        data.put("note", "抹消登记需权利人与登记义务人共同申请");
        return data;
    }

    /**
     * 提交抹消登记申请（材料已递法务局）。
     *
     * @param registrationId 登记档案 ID
     * @param actor          操作人
     */
    @Transactional
    public Map<String, Object> applyCancellation(Long batchId, String actor) {
        CollateralRegistrationBatch batch = findBatch(batchId);
        if (!CancellationStatus.REQUESTED.name().equals(batch.getCancellationStatus())) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "仅待申请状态可提交，当前=" + batch.getCancellationStatus());
        }
        batch.setCancellationStatus(CancellationStatus.APPLIED.name());
        batch.setCancellationAppliedAt(LocalDateTime.now());
        batchRepository.save(batch);
        syncLedgerDue(batch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("batchNo", batch.getBatchNo());
        data.put("cancellationStatus", batch.getCancellationStatus());
        return data;
    }

    /**
     * 抹消登记完成：登记抹消编号 + 归档抹消凭证。
     *
     * @param registrationId                登记档案 ID
     * @param cancellationNo                抹消登记编号
     * @param certificateAttachmentId       抹消凭证附件 ID（强烈建议提供）
     * @param actor                         操作人
     */
    @Transactional
    public Map<String, Object> completeCancellation(Long batchId,
                                                    String cancellationNo,
                                                    Long certificateAttachmentId,
                                                    String actor) {
        CollateralRegistrationBatch batch = findBatch(batchId);
        if (!CancellationStatus.APPLIED.name().equals(batch.getCancellationStatus())
                && !CancellationStatus.REQUESTED.name().equals(batch.getCancellationStatus())) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "仅待申请/已提交状态可完成抹消，当前=" + batch.getCancellationStatus());
        }
        batch.setCancellationStatus(CancellationStatus.DONE.name());
        batch.setCancellationNo(cancellationNo);
        batch.setCancellationCompletedAt(LocalDateTime.now());
        batch.setCancellationCertificateAttachmentId(certificateAttachmentId);
        batchRepository.save(batch);
        syncLedgerDue(batch);

        // 抹消凭证挂到本批次覆盖的每台车（一次抹消、一批车共享同一份凭证）
        if (certificateAttachmentId != null) {
            for (String vin : batchRepository.findVinsByBatchId(batchId)) {
                CollateralLedger ledger = ledgerRepository.findByVin(vin).orElse(null);
                if (ledger != null) {
                    addEvidence(ledger, EvidenceType.CANCELLATION_CERT, "ATTACHMENT",
                            String.valueOf(certificateAttachmentId), certificateAttachmentId,
                            LocalDateTime.now(), "抹消凭证，抹消登记编号=" + cancellationNo, actor);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("batchNo", batch.getBatchNo());
        data.put("cancellationStatus", batch.getCancellationStatus());
        data.put("cancellationNo", cancellationNo);
        data.put("certificateArchived", certificateAttachmentId != null);
        return data;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /** 台账列表（含关联登记与健康度）。 */
    public List<Map<String, Object>> listLedger(Long dealerId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollateralLedger l : ledgerRepository.findAll(dealerId)) {
            rows.add(toRow(l));
        }
        return rows;
    }

    /** 单台台账详情：台账 + 登记清单 + 证据链。 */
    public Map<String, Object> detail(String vin) {
        CollateralLedger l = requireLedger(vin);
        Map<String, Object> data = toRow(l);
        List<Map<String, Object>> regs = new ArrayList<>();
        for (CollateralRegistrationBatch b : batchRepository.findByVin(vin)) {
            Map<String, Object> m = toBatchRow(b);
            m.put("coveredVins", batchRepository.findVinsByBatchId(b.getId()));
            regs.add(m);
        }
        data.put("registrations", regs);
        List<Map<String, Object>> evs = new ArrayList<>();
        for (CollateralEvidenceLink e : evidenceRepository.findByLedgerId(l.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("evidenceType", e.getEvidenceType());
            m.put("refType", e.getRefType());
            m.put("refId", e.getRefId());
            m.put("attachmentId", e.getAttachmentId());
            m.put("eventAt", e.getEventAt() == null ? null : e.getEventAt().toString());
            m.put("note", e.getNote());
            m.put("recordedBy", e.getRecordedBy());
            evs.add(m);
        }
        data.put("evidenceChain", evs);
        return data;
    }

    /** 已售未回款的敞口清单（库存释放规则重点监控）。 */
    public List<Map<String, Object>> listSoldUnpaid() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollateralLedger l : ledgerRepository.findSoldUnpaid()) {
            Map<String, Object> m = toRow(l);
            m.put("riskNote", "已售未回款：回款须进入指定账户或用于还款");
            rows.add(m);
        }
        return rows;
    }

    /** 登记到期清单（5 年期限不可续展）。 */
    public List<Map<String, Object>> listExpiringRegistrations() {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime deadline = LocalDateTime.now().plusDays(registrationWarnDays);
        for (CollateralRegistrationBatch b : batchRepository.findExpiring(deadline)) {
            Map<String, Object> m = toBatchRow(b);
            m.put("coveredVins", batchRepository.findVinsByBatchId(b.getId()));
            m.put("daysLeft", b.getExpiresAt() == null ? null
                    : java.time.Duration.between(LocalDateTime.now(), b.getExpiresAt()).toDays());
            m.put("note", "让渡担保登记 5 年期限不可续展，到期须重新设定");
            rows.add(m);
        }
        return rows;
    }

    /** 抹消待办清单。 */
    public List<Map<String, Object>> listCancellationPending() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollateralRegistrationBatch b : batchRepository.findCancellationPending()) {
            Map<String, Object> m = toBatchRow(b);
            m.put("coveredVins", batchRepository.findVinsByBatchId(b.getId()));
            m.put("note", "抹消登记需权利人与登记义务人共同申请");
            rows.add(m);
        }
        return rows;
    }

    /** 证据链缺口检查：返回缺少必要证据的台账。 */
    public List<Map<String, Object>> listEvidenceGaps() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollateralLedger l : ledgerRepository.findAll(null)) {
            List<CollateralEvidenceLink> chain = evidenceRepository.findByLedgerId(l.getId());
            List<String> missing = new ArrayList<>();
            if (l.isFinanced() && !hasType(chain, EvidenceType.ADVANCE)) {
                missing.add(EvidenceType.ADVANCE.getLabel());
            }
            if (l.isSold() && !l.isRepaid() && l.getRequiredAccountRef() != null
                    && !hasType(chain, EvidenceType.REPAYMENT)) {
                missing.add(EvidenceType.REPAYMENT.getLabel() + "（已售未回款）");
            }
            for (CollateralRegistrationBatch b : batchRepository.findByVin(l.getVin())) {
                String gap = b.evidenceGap();
                if (gap != null) {
                    missing.add("批次 " + b.getBatchNo() + "（登记号 " + b.getRegistrationNo() + "）：" + gap);
                }
            }
            if (!missing.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("vin", l.getVin());
                m.put("dealerId", l.getDealerId());
                m.put("missing", missing);
                rows.add(m);
            }
        }
        return rows;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private boolean hasType(List<CollateralEvidenceLink> chain, EvidenceType type) {
        return chain.stream().anyMatch(e -> type.name().equals(e.getEvidenceType()));
    }

    private Map<String, Object> toRow(CollateralLedger l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vin", l.getVin());
        m.put("dealerId", l.getDealerId());
        m.put("financed", l.isFinanced());
        m.put("financedAt", l.getFinancedAt() == null ? null : l.getFinancedAt().toString());
        m.put("advanceNo", l.getAdvanceNo());
        m.put("sold", l.isSold());
        m.put("soldAt", l.getSoldAt() == null ? null : l.getSoldAt().toString());
        m.put("repaid", l.isRepaid());
        m.put("repaidAt", l.getRepaidAt() == null ? null : l.getRepaidAt().toString());
        m.put("repaidAmount", l.getRepaidAmount());
        m.put("released", l.isReleased());
        m.put("releasedAt", l.getReleasedAt() == null ? null : l.getReleasedAt().toString());
        m.put("releaseReason", l.getReleaseReason());
        m.put("securityType", l.getSecurityType());
        m.put("securityStatus", l.getSecurityStatus());
        m.put("requiredAccountRef", l.getRequiredAccountRef());
        m.put("accountChecked", l.isAccountChecked());
        m.put("lastAuditDate", l.getLastAuditDate() == null ? null : l.getLastAuditDate().toString());
        m.put("auditMismatchCount", l.getAuditMismatchCount());
        m.put("disposalCaseNo", l.getDisposalCaseNo());
        m.put("registrationDue", l.isRegistrationDue());
        m.put("registrationNote", l.getRegistrationNote());
        m.put("soldUnpaid", l.isSoldUnpaid());
        m.put("healthNote", l.healthNote());
        return m;
    }

    /** 批次 → 前端行结构。 */
    private Map<String, Object> toBatchRow(CollateralRegistrationBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", b.getId());
        m.put("batchNo", b.getBatchNo());
        m.put("registrationNo", b.getRegistrationNo());
        m.put("registrationType", b.getRegistrationType());
        m.put("perfectionMethod", b.getPerfectionMethod());
        m.put("registryOffice", b.getRegistryOffice());
        m.put("registeredAt", b.getRegisteredAt() == null ? null : b.getRegisteredAt().toString());
        m.put("expiresAt", b.getExpiresAt() == null ? null : b.getExpiresAt().toString());
        m.put("contractAttachmentId", b.getContractAttachmentId());
        m.put("contractNo", b.getContractNo());
        m.put("contractArchived", b.hasContract());
        m.put("certificateAttachmentId", b.getCertificateAttachmentId());
        m.put("certificateNo", b.getCertificateNo());
        m.put("certificateArchived", b.hasCertificate());
        m.put("cancellationStatus", b.getCancellationStatus());
        m.put("cancellationNo", b.getCancellationNo());
        m.put("cancellationCertificateAttachmentId", b.getCancellationCertificateAttachmentId());
        m.put("cancellationCompletedWithProof", b.isCancellationCompletedWithProof());
        m.put("expiringSoon", b.isExpiringSoon(LocalDateTime.now(), registrationWarnDays));
        m.put("vehicleCount", b.getVehicleCount());
        m.put("evidenceGap", b.evidenceGap());
        m.put("createdBy", b.getCreatedBy());
        return m;
    }

    /** 登记批次列表。 */
    public List<Map<String, Object>> listBatches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollateralRegistrationBatch b : batchRepository.findAll()) {
            Map<String, Object> m = toBatchRow(b);
            m.put("coveredVins", batchRepository.findVinsByBatchId(b.getId()));
            rows.add(m);
        }
        return rows;
    }

    private CollateralLedger loadOrCreate(String vin, Long dealerId) {
        return ledgerRepository.findByVin(vin).orElseGet(() -> {
            CollateralLedger l = new CollateralLedger();
            l.setVin(vin);
            l.setDealerId(dealerId);
            l.setSecurityStatus(SecurityStatus.NONE.name());
            return l;
        });
    }

    private CollateralLedger requireLedger(String vin) {
        return ledgerRepository.findByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "担保物台账不存在：" + vin + "（该车尚未放款确认，请先完成放款）"));
    }

    private CollateralRegistrationBatch findBatch(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLATERAL_002, "登记批次不存在：" + id));
    }

    /** 登记状态变化后同步台账待办标记。 */
    /** 批次抹消状态变化后，同步本批次所有车辆的台账待办标记。 */
    private void syncLedgerDue(CollateralRegistrationBatch batch) {
        boolean pending = CancellationStatus.valueOf(batch.getCancellationStatus()).isPending();
        for (String vin : batchRepository.findVinsByBatchId(batch.getId())) {
            ledgerRepository.findByVin(vin).ifPresent(l -> {
                l.setRegistrationDue(pending);
                if (!pending) {
                    l.setRegistrationNote(null);
                }
                ledgerRepository.save(l);
            });
        }
    }

    private void addEvidence(CollateralLedger ledger, EvidenceType type, String refType, String refId,
                             Long attachmentId, LocalDateTime eventAt, String note, String actor) {
        CollateralEvidenceLink e = new CollateralEvidenceLink();
        e.setLedgerId(ledger.getId());
        e.setVin(ledger.getVin());
        e.setEvidenceType(type.name());
        e.setRefType(refType);
        e.setRefId(refId);
        e.setAttachmentId(attachmentId);
        e.setEventAt(eventAt == null ? LocalDateTime.now() : eventAt);
        e.setNote(note);
        e.setRecordedBy(actor);
        evidenceRepository.add(e);
    }

    /**
     * 指定回款账户（库存释放规则的落点）。
     *
     * @param vin         车架号
     * @param accountRef  经销商结算账户引用码
     * @param actor       操作人
     */
    @Transactional
    public Map<String, Object> requireAccount(String vin, String accountRef, String actor) {
        CollateralLedger ledger = requireLedger(vin);
        BankAccount account = bankAccountRepository.findByRef(accountRef)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_001,
                        Map.of("accountRef", accountRef, "reason", "ACCOUNT_REF_NOT_FOUND")));
        ledger.setRequiredAccountRef(accountRef);
        ledger.setAccountChecked(true);
        ledger.setAccountCheckedAt(LocalDateTime.now());
        ledger.setAccountCheckedBy(actor);
        ledgerRepository.save(ledger);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("requiredAccountRef", accountRef);
        data.put("accountMask", account.getAccountNoMask());
        return data;
    }
}
