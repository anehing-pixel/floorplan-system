package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.BankAccount;
import com.company.floorplan.domain.model.BankAccountStatus;
import com.company.floorplan.domain.model.GuaranteeAbility;
import com.company.floorplan.domain.model.Supplier;
import com.company.floorplan.domain.model.SupplierType;
import com.company.floorplan.domain.repository.BankAccountRepository;
import com.company.floorplan.domain.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 上游与收款账户用例（应用层，ADR-007 决策三 / 决策七）。
 *
 * <p><b>敏感数据纪律</b>：账号明文只在录入瞬间存在，随后一律是密文。
 * 对外只返掩码；日志与审计不得触碰明文。</p>
 *
 * <p><b>账户引用码</b>：{@code accountRef} 是业务引用码，
 * Excel 导入只填它——<b>不接受文件里直接写明文账号</b>，
 * 否则导入通道就成了塞入攻击者账户的后门。</p>
 */
@Service
public class SupplierApplicationService {

    private final SupplierRepository supplierRepository;
    private final BankAccountRepository bankAccountRepository;
    private final AccountCryptoService crypto;

    public SupplierApplicationService(SupplierRepository supplierRepository,
                                      BankAccountRepository bankAccountRepository,
                                      AccountCryptoService crypto) {
        this.supplierRepository = supplierRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.crypto = crypto;
    }

    /* ---------------- 上游主数据 ---------------- */

    /** 列出全部上游。 */
    public List<Supplier> listSuppliers() {
        return supplierRepository.findAll();
    }

    /** 按类型列出上游。 */
    public List<Supplier> listSuppliersByType(String supplierType) {
        return supplierRepository.findByType(supplierType);
    }

    /**
     * 新增上游。
     *
     * @param code              上游编号（唯一）
     * @param name              上游名称
     * @param supplierType      上游类型（决定打款模式）
     * @param riskGrade         上游评级（可空）
     * @param guaranteeAbility  回购/担保能力
     * @param contactPerson     联系人
     * @param contactPhone      联系电话
     * @param remark            备注
     * @return 新建的上游
     */
    @Transactional
    public Supplier createSupplier(String code, String name, String supplierType, String riskGrade,
                                   String guaranteeAbility, String contactPerson, String contactPhone,
                                   String remark) {
        if (isBlank(code) || isBlank(name)) {
            throw new BusinessException(ErrorCode.VALID_001, "上游编号与名称不能为空");
        }
        if (supplierRepository.findByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT_001, "上游编号已存在：" + code);
        }
        SupplierType type = parseSupplierType(supplierType);
        Supplier s = new Supplier();
        s.setSupplierCode(code);
        s.setName(name);
        s.setSupplierType(type);
        s.setStatus(Supplier.Status.ACTIVE);
        s.setRiskGrade(riskGrade);
        s.setGuaranteeAbility(parseGuarantee(guaranteeAbility));
        s.setContactPerson(contactPerson);
        s.setContactPhone(contactPhone);
        s.setRemark(remark);
        return supplierRepository.save(s);
    }

    /**
     * 更新上游（主数据编辑）。
     *
     * <p><b>注意</b>：改 {@code supplierType} 会改变打款模式，
     * 因此必须重跑放款校验链——已有的放款不受影响（已冻结快照），
     * 但后续放款会走新的校验链。此处仅记录变更，校验在放款环节执行。</p>
     */
    @Transactional
    public Supplier updateSupplier(Long id, String name, String supplierType, String riskGrade,
                                   String guaranteeAbility, String contactPerson, String contactPhone,
                                   String remark) {
        Supplier s = supplierRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "上游不存在：" + id));
        if (!isBlank(name)) {
            s.setName(name);
        }
        if (!isBlank(supplierType)) {
            s.setSupplierType(parseSupplierType(supplierType));
        }
        if (riskGrade != null) {
            s.setRiskGrade(riskGrade);
        }
        if (guaranteeAbility != null) {
            s.setGuaranteeAbility(parseGuarantee(guaranteeAbility));
        }
        if (contactPerson != null) {
            s.setContactPerson(contactPerson);
        }
        if (contactPhone != null) {
            s.setContactPhone(contactPhone);
        }
        if (remark != null) {
            s.setRemark(remark);
        }
        return supplierRepository.save(s);
    }

    /**
     * 更新上游状态。
     *
     * @param id     上游 ID
     * @param status 目标状态
     * @return 更新行数
     */
    @Transactional
    public int changeSupplierStatus(Long id, String status) {
        Supplier.Status target;
        try {
            target = Supplier.Status.valueOf(status);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "上游状态非法：" + status);
        }
        if (supplierRepository.findById(id).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001, "上游不存在：" + id);
        }
        return supplierRepository.updateStatus(id, target.name());
    }

    /* ---------------- 收款账户 ---------------- */

    /**
     * 新增上游收款账户。
     *
     * @param supplierId    上游 ID
     * @param bankName      开户行
     * @param accountHolder 户名
     * @param accountNo     <b>明文账号</b>——只在本次调用内存在，落库即加密
     * @param accountRef    业务引用码（导入用，可空则自动生成）
     * @param effectiveFrom 生效日
     * @param reviewer      复核人（双人复核）
     * @return 新建账户（对外只返掩码）
     */
    @Transactional
    public BankAccount createSupplierAccount(Long supplierId, String bankName, String accountHolder,
                                             String accountNo, String accountRef, LocalDate effectiveFrom,
                                             String reviewer) {
        if (supplierRepository.findById(supplierId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001, "上游不存在：" + supplierId);
        }
        if (isBlank(bankName) || isBlank(accountHolder) || isBlank(accountNo)) {
            throw new BusinessException(ErrorCode.VALID_001, "开户行、户名、账号不能为空");
        }
        BankAccount a = new BankAccount();
        a.setOwnerType(BankAccount.OwnerType.SUPPLIER);
        a.setSupplierId(supplierId);
        a.setBankName(bankName);
        a.setAccountHolder(accountHolder);
        a.setAccountNoCipher(crypto.encrypt(accountNo));
        a.setAccountNoMask(AccountCryptoService.mask(accountNo));
        a.setAccountRef(isBlank(accountRef) ? generateRef("SUP") : accountRef);
        a.setStatus(BankAccountStatus.ACTIVE);
        a.setEffectiveFrom(effectiveFrom == null ? LocalDate.now() : effectiveFrom);
        a.setReviewer(reviewer);
        return bankAccountRepository.save(a);
    }

    /**
     * 新增经销商结算账户（场景 C：钱回经销商自身账户）。
     */
    @Transactional
    public BankAccount createDealerAccount(Long dealerId, String bankName, String accountHolder,
                                           String accountNo, String accountRef, LocalDate effectiveFrom,
                                           String reviewer) {
        if (isBlank(bankName) || isBlank(accountHolder) || isBlank(accountNo)) {
            throw new BusinessException(ErrorCode.VALID_001, "开户行、户名、账号不能为空");
        }
        BankAccount a = new BankAccount();
        a.setOwnerType(BankAccount.OwnerType.DEALER);
        a.setDealerId(dealerId);
        a.setBankName(bankName);
        a.setAccountHolder(accountHolder);
        a.setAccountNoCipher(crypto.encrypt(accountNo));
        a.setAccountNoMask(AccountCryptoService.mask(accountNo));
        a.setAccountRef(isBlank(accountRef) ? generateRef("DEA") : accountRef);
        a.setStatus(BankAccountStatus.ACTIVE);
        a.setEffectiveFrom(effectiveFrom == null ? LocalDate.now() : effectiveFrom);
        a.setReviewer(reviewer);
        return bankAccountRepository.save(a);
    }

    /** 列出全部账户（对外只返掩码）。 */
    public List<BankAccount> listAccounts() {
        return bankAccountRepository.findAll();
    }

    /**
     * 更新账户状态（冻结 / 关闭 / 恢复）。
     *
     * @param id     账户 ID
     * @param status 目标状态
     * @return 更新行数
     */
    @Transactional
    public int changeAccountStatus(Long id, String status) {
        BankAccountStatus target;
        try {
            target = BankAccountStatus.valueOf(status);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "账户状态非法：" + status);
        }
        if (bankAccountRepository.findById(id).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001, "账户不存在：" + id);
        }
        return bankAccountRepository.updateStatus(id, target.name());
    }

    /* ---------------- 工具 ---------------- */

    private static SupplierType parseSupplierType(String raw) {
        if (isBlank(raw)) {
            throw new BusinessException(ErrorCode.VALID_001, "供应商类型不能为空");
        }
        try {
            return SupplierType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "供应商类型非法：" + raw);
        }
    }

    private static GuaranteeAbility parseGuarantee(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return GuaranteeAbility.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "担保能力取值非法：" + raw);
        }
    }

    private static String generateRef(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
