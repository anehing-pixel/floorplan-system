package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.model.Dealer;
import com.company.floorplan.domain.model.DealerStatus;
import com.company.floorplan.domain.model.RatingConfig;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.DealerRepository;
import com.company.floorplan.domain.repository.RatingConfigRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 经销商（借款人）用例（应用层）。
 *
 * <p><b>应用层职责</b>：编排领域对象与仓储、划定事务边界、写入审计留痕；
 * 业务规则本身在 {@link com.company.floorplan.domain.model.Dealer} 与额度聚合根中，
 * 本层不重复实现。</p>
 *
 * <p><b>业务定位</b>：新车渠道的借款人是品牌授权经销商（法人），
 * 不是个人，因此不适用个人借款的总量规制约束。</p>
 *
 * <p><b>后台管理动作</b>（新增 / 编辑 / 启停 / 删除）都归到这里，
 * 共同点是：都要留痕、都要校验引用完整性。</p>
 */
@Service
public class DealerApplicationService {

    private final DealerRepository dealerRepository;
    private final SupportRepository supportRepository;
    /** 授信评级分级口径（后台动态配置）：创建/调级时按口径校验等级合法性。 */
    private final RatingConfigRepository ratingConfigRepository;
    /** 车辆台账仓储：删除护栏与名下车辆清单都要用。 */
    private final VehicleUnitRepository vehicleUnitRepository;
    /** 额度仓储：删除护栏用。 */
    private final CreditLineRepository creditLineRepository;

    public DealerApplicationService(DealerRepository dealerRepository, SupportRepository supportRepository,
                                    RatingConfigRepository ratingConfigRepository,
                                    VehicleUnitRepository vehicleUnitRepository,
                                    CreditLineRepository creditLineRepository) {
        this.dealerRepository = dealerRepository;
        this.supportRepository = supportRepository;
        this.ratingConfigRepository = ratingConfigRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.creditLineRepository = creditLineRepository;
    }

    @Transactional
    public Dealer createDealer(String dealerCode, String name, String riskGrade, Integer businessYears, String actor) {
        return createDealer(dealerCode, name, riskGrade, businessYears, null, null, null, null, actor);
    }

    /**
     * 新增经销商（含完整档案）。
     *
     * @param dealerCode    经销商编号（唯一）
     * @param name          法人全称
     * @param riskGrade     授信评级（可空，空表示暂不评级）
     * @param businessYears 经营年限
     * @param contactPerson 联系人
     * @param contactPhone  联系电话
     * @param address       营业地址
     * @param remark        备注
     * @param actor         操作人（审计留痕）
     */
    @Transactional
    public Dealer createDealer(String dealerCode, String name, String riskGrade, Integer businessYears,
                               String contactPerson, String contactPhone, String address, String remark,
                               String actor) {
        if (dealerCode == null || dealerCode.isBlank() || name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "经销商编号与名称不能为空");
        }
        // 防御"占位垃圾值"：前端若把 undefined/null 直接序列化成字符串传进来，
        // 它非空、能通过上面的 blank 校验，但会把垃圾写进主数据表。
        // 这里统一拒绝这类字面量，避免污染授信台账。
        rejectPlaceholderValue(dealerCode, "经销商编号");
        rejectPlaceholderValue(name, "经销商名称");
        if (dealerRepository.findByCode(dealerCode).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT_001, "经销商编号已存在：" + dealerCode);
        }
        // 评级口径动态校验：填了等级就必须在口径表中存在且未停用
        // （口径本身由 /api/rating-configs 后台维护，这里只做引用校验）
        if (riskGrade != null && !riskGrade.isBlank()) {
            requireGradeConfigured(riskGrade, "创建经销商");
        }
        Dealer dealer = new Dealer();
        dealer.setDealerCode(dealerCode);
        dealer.setName(name);
        dealer.setStatus(DealerStatus.ACTIVE);
        dealer.setRiskGrade(riskGrade);
        dealer.setBusinessYears(businessYears);
        dealer.setContactPerson(contactPerson);
        dealer.setContactPhone(contactPhone);
        dealer.setAddress(address);
        dealer.setRemark(remark);
        Dealer saved = dealerRepository.save(dealer);

        supportRepository.writeAudit(actor, "CREATE_DEALER", "DEALER", String.valueOf(saved.getId()),
                null, dealerCode, null);
        return saved;
    }

    /**
     * 编辑经销商档案（后台 CRUD 的 U）。
     *
     * <p><b>能改什么</b>：编号、名称、联系人、电话、地址、备注、评级、经营年限、状态。
     * <b>不能改什么</b>：ID（主键不可变）、以及任何账务字段
     * （额度占用、垫款、利息都不属于经销商档案，它们各自有专门的流转接口）。</p>
     *
     * <p><b>编号唯一性</b>：改编号时要查重，且要排除自己——
     * 不排除的话，改个无关字段也会被自己绊倒。</p>
     *
     * @param dealerId 经销商 ID
     * @param actor    操作人（审计留痕）
     */
    @Transactional
    public Dealer updateDealer(Long dealerId, String dealerCode, String name, String riskGrade,
                               Integer businessYears, String status, String contactPerson,
                               String contactPhone, String address, String remark, String actor) {
        Dealer dealer = getDealer(dealerId);
        String before = snapshot(dealer);

        if (dealerCode != null && !dealerCode.isBlank()) {
            rejectPlaceholderValue(dealerCode, "经销商编号");
            Optional<Dealer> same = dealerRepository.findByCode(dealerCode.trim());
            if (same.isPresent() && !same.get().getId().equals(dealerId)) {
                throw new BusinessException(ErrorCode.CONFLICT_001, "经销商编号已存在：" + dealerCode);
            }
        }
        if (name != null && !name.isBlank()) {
            rejectPlaceholderValue(name, "经销商名称");
        }
        if (riskGrade != null && !riskGrade.isBlank()) {
            requireGradeConfigured(riskGrade, "编辑经销商");
        }

        // 档案字段：null 表示"不修改"（PATCH 语义），空白字符串表示"清空该字段"
        String newCode = dealerCode == null || dealerCode.isBlank() ? dealer.getDealerCode() : dealerCode.trim();
        String newName = name == null || name.isBlank() ? dealer.getName() : name.trim();
        dealer.updateProfile(newCode, newName,
                contactPerson != null ? contactPerson : dealer.getContactPerson(),
                contactPhone != null ? contactPhone : dealer.getContactPhone(),
                address != null ? address : dealer.getAddress(),
                remark != null ? remark : dealer.getRemark());

        if (riskGrade != null) {
            dealer.setRiskGrade(riskGrade.isBlank() ? null : riskGrade);
        }
        if (businessYears != null) {
            dealer.setBusinessYears(businessYears);
        }
        if (status != null && !status.isBlank()) {
            DealerStatus target = parseStatus(status);
            dealer.changeStatus(target);
        }

        Dealer saved = dealerRepository.save(dealer);
        supportRepository.writeAudit(actor, "UPDATE_DEALER", "DEALER", String.valueOf(dealerId),
                before, snapshot(saved), null);
        return saved;
    }

    /**
     * 调整经销商授信评级（配套"评级口径后台动态配置"）。
     *
     * <p><b>为什么调级也要校验口径</b>：等级代码必须在口径表中存在，
     * 否则垫款建账时无法解析加点；已停用的等级不准入新业务，自然也不允许
     * 把经销商调成该等级。</p>
     *
     * @param dealerId 经销商 ID
     * @param newGrade 新评级代码
     * @param actor    操作人（审计留痕）
     * @return 更新后的经销商
     */
    @Transactional
    public Dealer updateRiskGrade(Long dealerId, String newGrade, String actor) {
        requireGradeConfigured(newGrade, "调整评级");
        Dealer dealer = getDealer(dealerId);
        String oldGrade = dealer.getRiskGrade();
        dealer.setRiskGrade(newGrade);
        Dealer saved = dealerRepository.save(dealer);
        supportRepository.writeAudit(actor, "UPDATE_RISK_GRADE", "DEALER", String.valueOf(dealerId),
                "riskGrade=" + oldGrade, "riskGrade=" + newGrade, null);
        return saved;
    }

    /**
     * 删除经销商（后台 CRUD 的 D）。
     *
     * <p><b>护栏</b>：名下还有车辆或授信额度的经销商一律不删。
     * 因为车辆台账与额度表都靠 dealer_id 指向它，主体被抹掉，
     * 这些历史账就会变成没有归属的孤儿数据，审计链断掉。
     * 对这类经销商，正确做法是置为"终止合作"（{@link DealerStatus#TERMINATED}），
     * 存量业务照常推进，但不再承接新业务。</p>
     *
     * @param dealerId 经销商 ID
     * @param actor    操作人（审计留痕）
     */
    @Transactional
    public void deleteDealer(Long dealerId, String actor) {
        Dealer dealer = getDealer(dealerId);
        int vehicleCount = vehicleUnitRepository.countByDealerId(dealerId);
        int lineCount = creditLineRepository.countByDealerId(dealerId);
        if (vehicleCount > 0 || lineCount > 0) {
            throw new BusinessException(ErrorCode.DEALER_001, Map.of(
                    "dealerId", dealerId,
                    "vehicleCount", vehicleCount,
                    "creditLineCount", lineCount,
                    "advice", "该经销商名下存在车辆或授信额度，请改为将状态置为「终止合作」而非删除"));
        }
        supportRepository.writeAudit(actor, "DELETE_DEALER", "DEALER", String.valueOf(dealerId),
                snapshot(dealer), null, null);
        dealerRepository.deleteById(dealerId);
    }

    /**
     * 经销商名下车辆清单（聚合视图）。
     *
     * <p>一次调用返回：档案 + 车辆列表 + 汇总统计。
     * 前端"经销商 → 名下车辆"抽屉直接用它，避免先拉经销商再逐台拉详情
     * 的 N+1 请求。</p>
     *
     * @param dealerId 经销商 ID
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dealerVehicleOverview(Long dealerId) {
        Dealer dealer = getDealer(dealerId);
        List<VehicleUnit> vehicles = vehicleUnitRepository.findByDealerId(dealerId);
        List<CreditLine> lines = creditLineRepository.findByDealerId(dealerId);

        long totalInvoice = 0L;
        long totalAdvance = 0L;
        long totalPrincipal = 0L;
        long totalPaid = 0L;
        int inStock = 0;
        int soldPending = 0;
        int settled = 0;
        int overdue = 0;
        int oot = 0;
        int agingSum = 0;
        int maxAging = 0;

        for (VehicleUnit v : vehicles) {
            totalInvoice += v.getInvoiceAmount();
            totalAdvance += v.getAdvanceAmount();
            totalPrincipal += v.getPrincipalBalance();
            totalPaid += v.getPaidPrincipal();
            if (v.getStatus() != null) {
                switch (v.getStatus()) {
                    case IN_STOCK -> inStock++;
                    case SOLD_PENDING -> soldPending++;
                    // REDEEMED（已赎回）与 SETTLED（已结清）资金侧等价：
                    // 本金归零、担保解除、额度已回补，对经销商视角都是"已了结"。
                    // 差别只在车辆去向（车还在库 vs 车已售出），不影响资金汇总。
                    case SETTLED, REDEEMED -> settled++;
                    default -> { }
                }
            }
            if (v.getOverdueDays() != null && v.getOverdueDays() > 0) {
                overdue++;
            }
            if (v.isOotSuspected()) {
                oot++;
            }
            int aging = v.getAgingDays() == null ? 0 : v.getAgingDays();
            agingSum += aging;
            maxAging = Math.max(maxAging, aging);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("vehicleCount", vehicles.size());
        summary.put("inStockCount", inStock);
        summary.put("soldPendingCount", soldPending);
        summary.put("settledCount", settled);
        summary.put("overdueCount", overdue);
        summary.put("ootCount", oot);
        summary.put("totalInvoiceAmount", totalInvoice);
        summary.put("totalAdvanceAmount", totalAdvance);
        summary.put("totalPrincipalBalance", totalPrincipal);
        summary.put("totalPaidPrincipal", totalPaid);
        summary.put("avgAgingDays", vehicles.isEmpty() ? 0 : Math.round(agingSum / (float) vehicles.size()));
        summary.put("maxAgingDays", maxAging);
        summary.put("creditLineCount", lines.size());
        summary.put("approvedLimit", lines.stream().mapToLong(CreditLine::getApprovedLimit).sum());
        summary.put("usedAmount", lines.stream().mapToLong(CreditLine::getUsedAmount).sum());
        summary.put("frozenAmount", lines.stream().mapToLong(CreditLine::getFrozenAmount).sum());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dealer", dealer);
        result.put("summary", summary);
        result.put("vehicles", vehicles);
        return result;
    }

    /**
     * 拒绝占位垃圾值（"undefined" / "null" / "NaN" 这类字面量字符串）。
     *
     * <p><b>为什么需要这层防线</b>：前端把 {@code undefined} 强转成字符串再提交时，
     * 得到的 "undefined" 非空、能通过 blank 校验，于是被当成合法编号写入主数据表——
     * 这种脏数据一旦入库，会污染授信台账、审计日志和报表，且极难追溯。
     * 在写入前拦住，是最后一道可靠防线。</p>
     */
    private void rejectPlaceholderValue(String value, String fieldLabel) {
        String v = value.trim().toLowerCase();
        if ("undefined".equals(v) || "null".equals(v) || "nan".equals(v)) {
            throw new BusinessException(ErrorCode.VALID_001,
                    fieldLabel + "无效（收到占位值 " + value + "），请检查前端传参");
        }
    }

    /** 等级引用校验：口径必须存在且未停用，否则抛对应业务异常。 */
    private void requireGradeConfigured(String grade, String action) {
        Optional<RatingConfig> config = ratingConfigRepository.findByGrade(grade);
        if (config.isEmpty()) {
            throw new BusinessException(ErrorCode.RATING_001, Map.of(
                    "grade", grade, "reason", "评级口径未配置，请先在 /api/rating-configs 维护"));
        }
        if (!config.get().isAllowed()) {
            throw new BusinessException(ErrorCode.RATING_001, Map.of(
                    "grade", grade, "reason", action + "：该评级已停用，不准入"));
        }
    }

    /** 状态字符串 → 枚举；非法值给明确报错，而不是抛 IllegalArgumentException 变成 500。 */
    private DealerStatus parseStatus(String status) {
        try {
            return DealerStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "经销商状态非法：" + status + "（合法值：ACTIVE / SUSPENDED / TERMINATED）");
        }
    }

    /** 审计快照：只取主数据字段，避免把账务明细塞进审计日志。 */
    private String snapshot(Dealer d) {
        return "dealerCode=" + d.getDealerCode()
                + ",name=" + d.getName()
                + ",status=" + (d.getStatus() == null ? null : d.getStatus().name())
                + ",riskGrade=" + d.getRiskGrade()
                + ",businessYears=" + d.getBusinessYears()
                + ",contactPerson=" + d.getContactPerson()
                + ",contactPhone=" + d.getContactPhone()
                + ",address=" + d.getAddress()
                + ",remark=" + d.getRemark();
    }

    @Transactional(readOnly = true)
    public Dealer getDealer(Long id) {
        return dealerRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "经销商不存在：" + id));
    }

    @Transactional(readOnly = true)
    public List<Dealer> listDealers() {
        return dealerRepository.findAll();
    }
}
