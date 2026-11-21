package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.BankAccount;
import com.company.floorplan.domain.model.BankAccountStatus;
import com.company.floorplan.domain.model.Dealer;
import com.company.floorplan.domain.model.IngestChannel;
import com.company.floorplan.domain.model.PurchaseOrder;
import com.company.floorplan.domain.model.PurchaseOrderStatus;
import com.company.floorplan.domain.model.Supplier;
import com.company.floorplan.domain.model.SupplierType;
import com.company.floorplan.domain.model.VehicleStatusMapping;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.BankAccountRepository;
import com.company.floorplan.domain.repository.DealerRepository;
import com.company.floorplan.domain.repository.ImportBatchRepository;
import com.company.floorplan.domain.repository.PurchaseOrderRepository;
import com.company.floorplan.domain.repository.SupplierRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 采购订单用例（应用层，ADR-007 决策一 / 决策四）。
 *
 * <p><b>核心定位</b>：采购订单是<b>融资放款的前置主单</b>，不是"一个待解析的文本文件"。
 * 两条导入通道（API 直连 / Excel 批量）产出<b>同一份主单</b>，下游（放款校验、台账）无感知。</p>
 *
 * <p><b>幂等双锚点</b>：</p>
 * <ul>
 *   <li>{@code orderNo} 唯一 —— 防"同一张单重复导入"；</li>
 *   <li>VIN 幂等 —— 防"两个不同订单号报同一台车"。后者更隐蔽，
 *       因为单看每张单都合法，但同一台车被融资两次是真金白银的损失。</li>
 * </ul>
 *
 * <p><b>两段式导入</b>：{@code preview} 只校验不落库，返回行级错误明细；
 * {@code commit} 才真正建单。Excel 通道<b>必须</b>走这两段——
 * 解析错一个账户号＝资金打错地方，代价不可逆。</p>
 */
@Service
public class PurchaseOrderApplicationService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final BankAccountRepository bankAccountRepository;
    private final DealerRepository dealerRepository;
    private final VehicleUnitRepository vehicleUnitRepository;
    private final PurchaseOrderImportParser parser;
    private final ImportBatchRepository importBatchRepository;

    public PurchaseOrderApplicationService(PurchaseOrderRepository purchaseOrderRepository,
                                           SupplierRepository supplierRepository,
                                           BankAccountRepository bankAccountRepository,
                                           DealerRepository dealerRepository,
                                           VehicleUnitRepository vehicleUnitRepository,
                                           PurchaseOrderImportParser parser,
                                           ImportBatchRepository importBatchRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.dealerRepository = dealerRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.parser = parser;
        this.importBatchRepository = importBatchRepository;
    }

    /* ============================================================
       导入通道（决策四）
       ============================================================ */

    /**
     * Excel/CSV 导入：预校验（不落库）。
     *
     * @param input    文件流
     * @param filename 文件名
     * @param dealerId 归属经销商
     * @return 解析结果（通过行 + 行级错误明细）
     */
    public ImportResult previewImport(java.io.InputStream input, String filename, Long dealerId) {
        return parser.parse(input, filename, buildValidator(dealerId));
    }

    /**
     * Excel/CSV 导入：确认落库。
     *
     * <p>幂等语义：{@code orderNo} 已存在时<b>跳过该单并计入"已存在"</b>，
     * 不重复建账、不重复建车辆。</p>
     *
     * @param input    文件流
     * @param filename 文件名
     * @param dealerId 归属经销商
     * @param actor    操作人
     * @return 落库结果摘要
     */
    @Transactional
    public Map<String, Object> commitImport(java.io.InputStream input, String filename,
                                            Long dealerId, String actor) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "经销商不存在：" + dealerId));

        ImportResult result = parser.parse(input, filename, buildValidator(dealerId));

        Long batchId = createBatch(IngestChannel.EXCEL, filename, result, actor);
        importBatchRepository.markStatus(batchId, "COMMITTED", true);
        saveRowErrors(batchId, result);

        int created = 0;
        int skipped = 0;
        // 按 orderNo 分组：一单多车
        Map<String, List<ImportResult.ParsedRow>> grouped = new LinkedHashMap<>();
        for (ImportResult.ParsedRow row : result.getAccepted()) {
            grouped.computeIfAbsent(row.orderNo(), k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<ImportResult.ParsedRow>> entry : grouped.entrySet()) {
            String orderNo = entry.getKey();
            List<ImportResult.ParsedRow> rows = entry.getValue();

            // 幂等：单号已存在 → 跳过，不重复建账
            if (purchaseOrderRepository.findByOrderNo(orderNo).isPresent()) {
                skipped++;
                continue;
            }

            PurchaseOrder order = new PurchaseOrder();
            order.setOrderNo(orderNo);
            order.setDealerId(dealerId);
            order.setSupplierType(SupplierType.valueOf(rows.get(0).supplierType()));
            order.setStatus(PurchaseOrderStatus.CONFIRMED);
            order.setIngestChannel(IngestChannel.EXCEL);
            order.setImportBatchId(batchId);
            order.setPurchasedAt(LocalDate.now());

            // 上游：DEALER_SELF 无外部上游；其余按类型找一个同类型上游（取第一个）
            Supplier supplier = null;
            if (!order.getSupplierType().isDealerSelf()) {
                List<Supplier> candidates = supplierRepository.findByType(order.getSupplierType().name());
                if (candidates.isEmpty()) {
                    // 没有主数据 → 该单不建，计入失败（不静默跳过）
                    importBatchRepository.saveRowErrors(batchId, List.of(
                            toRowError(batchId, rows.get(0).rowNo(), "supplierType",
                                    "系统中没有登记该类型的上游（" + order.getSupplierType().name() + "），无法确定收款方",
                                    order.getSupplierType().name())));
                    skipped++;
                    continue;
                }
                supplier = candidates.get(0);
                order.setSupplierId(supplier.getId());
            }

            // 收款账户：按引用码查主数据（只接受引用码，不接受明文账号）
            BankAccount account = bankAccountRepository.findByRef(rows.get(0).payeeAccountRef())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALID_001,
                            "收款账户引用码不存在：" + rows.get(0).payeeAccountRef()));
            // 归属侧必须与打款模式匹配：DIRECT_PAY 打给上游账户，SELF_PAY 打给经销商账户
            boolean accountIsSupplier = account.getOwnerType() == BankAccount.OwnerType.SUPPLIER;
            if (order.isDirectPay() != accountIsSupplier) {
                throw new BusinessException(ErrorCode.VALID_001,
                        "收款账户与打款模式不匹配：" + order.getSupplierType().name() + " 要求 "
                                + (order.isDirectPay() ? "上游账户" : "经销商结算账户"));
            }
            order.setPayeeAccountId(account.getId());
            order.setPayeeAccountSnapshot(account.snapshot());

            long total = 0;
            for (ImportResult.ParsedRow row : rows) {
                total += row.invoiceAmount();
            }
            order.setTotalAmount(total);
            order.setVehicleCount(rows.size());
            order.setEvidenceNo(rows.get(0).evidenceNo());

            PurchaseOrder saved = purchaseOrderRepository.save(order);

            // 建车辆台账：挂采购订单（锚点）
            for (ImportResult.ParsedRow row : rows) {
                VehicleStatusMapping.Mapping mapping = VehicleStatusMapping.map(row.vehicleStatus());
                VehicleUnit vehicle = new VehicleUnit();
                vehicle.setVin(row.vin());
                vehicle.setDealerId(dealerId);
                vehicle.setCreditLineId(order.getCreditLineId());
                vehicle.setModel(row.model());
                vehicle.setModelYear(row.modelYear());
                vehicle.setColor(row.color());
                vehicle.setVehicleType("NEW_STOCK");
                vehicle.setInvoiceAmount(row.invoiceAmount());
                // 导入时未给出可识别的车辆状态 → 走建账动作落"已建账"（未申请垫款、未付款）。
                // 不能默认"已放款"，否则状态谎报资金事实。
                // 上游明确给出状态时才按原值落库（历史存量数据导入，属回填而非流转）。
                if (mapping == null) {
                    vehicle.registerLedger();
                } else {
                    vehicle.setStatus(mapping.vehicleStatus());
                }
                vehicle.setPurchaseOrderId(saved.getId());
                vehicle.setSupplierName(supplier == null ? dealer.getName() : supplier.getName());
                vehicle.setPurchaseChannel(purchaseChannelOf(order.getSupplierType()));
                vehicleUnitRepository.save(vehicle);
            }
            created++;
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("batchId", batchId);
        resultMap.put("totalRows", result.getTotalRows());
        resultMap.put("acceptedRows", result.getAccepted().size());
        resultMap.put("createdOrders", created);
        resultMap.put("skippedOrders", skipped);
        resultMap.put("rejectedRows", result.getRejectedRows());
        resultMap.put("totalAmount", result.getTotalAmount());
        resultMap.put("errors", result.getErrors().stream().map(ImportResult.RowError::toArray).toList());
        return resultMap;
    }

    /**
     * API 直连通道（上游系统推送）。
     *
     * <p>与 Excel 通道的差异：数据源可信、有契约保证，<b>可豁免落库前人工确认</b>。</p>
     *
     * @param payload 推送报文
     * @return 受理结果
     */
    @Transactional
    public Map<String, Object> ingestFromApi(ApiPurchaseOrderPayload payload) {
        if (payload == null || payload.orderNo() == null || payload.orderNo().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "采购订单号不能为空");
        }
        // 幂等：重复推送返回首次结果，不重复建账
        Optional<PurchaseOrder> existing = purchaseOrderRepository.findByOrderNo(payload.orderNo());
        if (existing.isPresent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("purchaseOrderId", existing.get().getId());
            map.put("orderNo", existing.get().getOrderNo());
            map.put("status", existing.get().getStatus().name());
            map.put("accepted", existing.get().getVehicleCount());
            map.put("rejected", 0);
            map.put("idempotent", true);
            return map;
        }
        if (payload.dealerId() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "经销商不能为空");
        }
        dealerRepository.findById(payload.dealerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "经销商不存在：" + payload.dealerId()));
        if (payload.vehicles() == null || payload.vehicles().isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001, "车辆清单不能为空");
        }
        SupplierType supplierType = SupplierType.valueOf(payload.supplierType());
        BankAccount account = bankAccountRepository.findByRef(payload.payeeAccountRef())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALID_001,
                        "收款账户引用码不存在：" + payload.payeeAccountRef()));

        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNo(payload.orderNo());
        order.setDealerId(payload.dealerId());
        order.setSupplierType(supplierType);
        order.setPayeeAccountId(account.getId());
        order.setPayeeAccountSnapshot(account.snapshot());
        order.setStatus(PurchaseOrderStatus.CONFIRMED);
        order.setIngestChannel(IngestChannel.API);
        order.setEvidenceNo(payload.evidenceNo());
        order.setPurchasedAt(payload.purchasedAt() == null ? LocalDate.now() : payload.purchasedAt());

        if (!supplierType.isDealerSelf()) {
            List<Supplier> candidates = supplierRepository.findByType(supplierType.name());
            if (!candidates.isEmpty()) {
                order.setSupplierId(candidates.get(0).getId());
            }
        }

        long total = 0;
        int rejected = 0;
        List<ApiVehiclePayload> vehicles = new ArrayList<>();
        for (ApiVehiclePayload v : payload.vehicles()) {
            if (purchaseOrderRepository.existsActiveVin(v.vin(), null)) {
                rejected++;
                continue;
            }
            total += v.invoiceAmount();
            vehicles.add(v);
        }
        order.setTotalAmount(total);
        order.setVehicleCount(vehicles.size());

        PurchaseOrder saved = purchaseOrderRepository.save(order);
        for (ApiVehiclePayload v : vehicles) {
            VehicleStatusMapping.Mapping mapping = VehicleStatusMapping.map(v.vehicleStatus());
            VehicleUnit vehicle = new VehicleUnit();
            vehicle.setVin(v.vin());
            vehicle.setDealerId(payload.dealerId());
            vehicle.setCreditLineId(order.getCreditLineId());
            vehicle.setModel(v.model());
            vehicle.setModelYear(v.modelYear());
            vehicle.setColor(v.color());
            vehicle.setVehicleType("NEW_STOCK");
            vehicle.setInvoiceAmount(v.invoiceAmount());
            // 上游未推送车辆状态时走建账动作落"已建账"。原实现默认"在途"（→FINANCED），
            // 等于把"钱还没付"谎报成"已付款"，会绕过重复融资与额度占用判断。
            if (mapping == null) {
                vehicle.registerLedger();
            } else {
                vehicle.setStatus(mapping.vehicleStatus());
            }
            vehicle.setPurchaseOrderId(saved.getId());
            vehicle.setPurchaseChannel(purchaseChannelOf(supplierType));
            vehicleUnitRepository.save(vehicle);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("purchaseOrderId", saved.getId());
        map.put("orderNo", saved.getOrderNo());
        map.put("status", saved.getStatus().name());
        map.put("accepted", vehicles.size());
        map.put("rejected", rejected);
        map.put("idempotent", false);
        return map;
    }

    /* ============================================================
       查询
       ============================================================ */

    /** 列出全部采购订单。 */
    public List<PurchaseOrder> listAll() {
        return purchaseOrderRepository.findAll();
    }

    /** 按订单号查主单（含车辆清单）。 */
    public PurchaseOrder getByOrderNo(String orderNo) {
        PurchaseOrder order = purchaseOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "采购订单不存在：" + orderNo));
        order.setVehicles(vehicleUnitRepository.findByPurchaseOrderId(order.getId()));
        return order;
    }

    /**
     * 放款前校验链预览（ADR-007 决策五）。
     *
     * <p><b>两套校验链必须分开</b>：{@code DIRECT_PAY} 要三方委托付款协议与授权上限，
     * {@code SELF_PAY} 要库存实物在库与独立抵押率。把协议校验设成全局硬校验，
     * 会把"库存抵押补充周转"这条业务直接堵死。</p>
     *
     * @param orderNo 采购订单号
     * @return 校验项清单
     */
    public Map<String, Object> validationPreview(String orderNo) {
        PurchaseOrder order = getByOrderNo(orderNo);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean pass = true;

        items.add(check("供应商类型", order.getSupplierType() != null,
                "订单必须携带供应商类型（它决定打款模式）", "supplier_type"));
        items.add(check("打款模式派生", order.getSupplierType() != null,
                "打款模式由供应商类型派生，不可人工覆盖", "payee_mode"));

        Optional<BankAccount> accountOpt = bankAccountRepository.findById(order.getPayeeAccountId());
        BankAccount account = accountOpt.orElse(null);
        items.add(check("收款账户存在", account != null, "收款账户不存在", "payee_account"));
        items.add(check("收款账户生效中", account != null && account.getStatus().isUsable(),
                "收款账户状态非 ACTIVE（冻结/待复核/关闭）", "payee_account_status"));
        items.add(check("账户归属侧匹配",
                account != null && (order.isDirectPay()
                        == (account.getOwnerType() == BankAccount.OwnerType.SUPPLIER)),
                "收款账户归属侧与打款模式不匹配", "payee_account_owner"));
        items.add(check("账户冷静期已过",
                account != null && account.isEffectiveOn(LocalDate.now()),
                "账户尚未到生效日或已过有效期", "payee_account_effective"));

        if (order.isDirectPay()) {
            // DIRECT_PAY 专属
            items.add(check("三方委托付款协议生效", false,
                    "需核对经销商与金融公司签署的三方委托付款协议（已生效、未过期、覆盖本单、金额不超授权上限）",
                    "entrustment_agreement"));
            items.add(check("上游主数据已登记", order.getSupplierId() != null,
                    "未找到对应上游主数据", "supplier"));
            items.add(check("上游状态正常",
                    order.getSupplierId() != null
                            && supplierRepository.findById(order.getSupplierId())
                            .map(s -> s.getStatus() == Supplier.Status.ACTIVE).orElse(false),
                    "上游状态非 ACTIVE", "supplier_status"));
            items.add(check("上游担保能力已评估",
                    order.getSupplierId() != null
                            && supplierRepository.findById(order.getSupplierId())
                            .map(s -> s.getGuaranteeAbility() != null).orElse(false),
                    "上游回购/担保能力未评估（拍卖行与中间层级普遍无厂商回购）", "guarantee_ability"));
        } else {
            // SELF_PAY 专属
            List<VehicleUnit> vehicles = order.getVehicles();
            boolean allInStock = !vehicles.isEmpty() && vehicles.stream()
                    .allMatch(v -> v.getStatus() == VehicleStatus.IN_STOCK);
            items.add(check("库存实物盘点在库", allInStock,
                    "营运资金放款要求提款对应库存已盘点在库", "inventory_in_stock"));
            items.add(check("库存估值口径已定", false,
                    "须按库存估值（按采购价，不按零售价）× 独立抵押率上限核定，不能沿用采购价×垫款比例",
                    "valuation_basis"));
            items.add(check("经销商结算账户已登记",
                    account != null && account.getOwnerType() == BankAccount.OwnerType.DEALER,
                    "SELF_PAY 场景须使用经销商结算账户", "dealer_account"));
            items.add(check("账户变更管控更严", false,
                    "SELF_PAY 账户变更须 T+2 冷静期 + 双人双签", "account_change_control"));
            items.add(check("审计标注", true,
                    "须在审计中标注「非代付、属营运资金」", "audit_label"));
        }

        for (Map<String, Object> item : items) {
            if (!Boolean.TRUE.equals(item.get("passed"))) {
                pass = false;
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("payeeMode", order.derivePayeeMode().name());
        result.put("payeeModeLabel", order.derivePayeeMode().getLabel());
        result.put("passed", pass);
        result.put("items", items);
        return result;
    }

    /* ============================================================
       内部工具
       ============================================================ */

    private Map<String, Object> check(String name, boolean passed, String reason, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("passed", passed);
        m.put("reason", passed ? null : reason);
        return m;
    }

    /** 行级业务校验器：VIN 幂等 + 账户存在性。 */
    private PurchaseOrderImportParser.RowValidator buildValidator(Long dealerId) {
        return (rowNo, orderNo, vin, accountRef) -> {
            if (vin != null && !vin.isBlank()) {
                if (purchaseOrderRepository.existsActiveVin(vin, null)) {
                    return "该 VIN 已挂在未终结的采购订单下（VIN 幂等），请核对是否重复报车";
                }
                if (vehicleUnitRepository.existsByVin(vin)) {
                    return "该 VIN 已存在于车辆台账";
                }
            }
            if (orderNo != null && !orderNo.isBlank()) {
                if (purchaseOrderRepository.findByOrderNo(orderNo).isPresent()) {
                    return null;   // 单号已存在属幂等重放，交给 commit 阶段跳过，不算错误
                }
            }
            if (accountRef != null && !accountRef.isBlank()) {
                if (bankAccountRepository.findByRef(accountRef).isEmpty()) {
                    return "收款账户引用码不存在：" + accountRef;
                }
            }
            return null;
        };
    }

    /** 供应商类型 → 采购渠道（导入层拦截，防止 purchase_channel 形同虚设）。 */
    private String purchaseChannelOf(SupplierType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case MAKER_SALES_CO, REGIONAL_AGENCY -> "OEM";
            case WHOLESALER -> "WHOLESALE";
            case AUCTION, USED_WHOLESALER -> "AUCTION";
            case DEALER_SELF -> "TRADE_IN";
        };
    }

    private Long createBatch(IngestChannel channel, String filename, ImportResult result, String actor) {
        com.company.floorplan.domain.model.ImportBatch batch = new com.company.floorplan.domain.model.ImportBatch();
        batch.setChannel(channel);
        batch.setFilename(filename);
        batch.setFileHash(result.getFileHash());
        batch.setTotalRows(result.getTotalRows());
        batch.setAcceptedRows(result.getAccepted().size());
        batch.setRejectedRows(result.getRejectedRows());
        batch.setTotalAmount(result.getTotalAmount());
        batch.setStatus("PREVIEWED");
        batch.setOperator(actor);
        return importBatchRepository.create(batch);
    }

    private void saveRowErrors(Long batchId, ImportResult result) {
        List<com.company.floorplan.domain.model.ImportRowError> errors = new ArrayList<>();
        for (ImportResult.RowError e : result.getErrors()) {
            com.company.floorplan.domain.model.ImportRowError row =
                    new com.company.floorplan.domain.model.ImportRowError();
            row.setBatchId(batchId);
            row.setRowNo(e.rowNo());
            row.setFieldName(e.fieldName());
            row.setReason(e.reason());
            row.setRawValue(e.rawValue());
            errors.add(row);
        }
        importBatchRepository.saveRowErrors(batchId, errors);
    }

    /** 构造单条行级错误。 */
    private com.company.floorplan.domain.model.ImportRowError toRowError(Long batchId, int rowNo,
                                                                         String field, String reason, String raw) {
        com.company.floorplan.domain.model.ImportRowError e =
                new com.company.floorplan.domain.model.ImportRowError();
        e.setBatchId(batchId);
        e.setRowNo(rowNo);
        e.setFieldName(field);
        e.setReason(reason);
        e.setRawValue(raw);
        return e;
    }

    /* ---------------- API 通道报文 ---------------- */

    /** API 通道的采购订单报文。 */
    public record ApiPurchaseOrderPayload(
            String orderNo,
            Long dealerId,
            String creditLineId,
            String supplierType,
            Long supplierId,
            String payeeAccountRef,
            String evidenceNo,
            Long attachmentId,
            LocalDate purchasedAt,
            List<ApiVehiclePayload> vehicles) {
    }

    /** API 通道的单车报文。 */
    public record ApiVehiclePayload(
            String vin,
            String model,
            Integer modelYear,
            String color,
            long invoiceAmount,
            String vehicleStatus) {
    }
}
