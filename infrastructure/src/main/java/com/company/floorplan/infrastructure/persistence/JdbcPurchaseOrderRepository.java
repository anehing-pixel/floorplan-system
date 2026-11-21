package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.IngestChannel;
import com.company.floorplan.domain.model.PurchaseOrder;
import com.company.floorplan.domain.model.PurchaseOrderStatus;
import com.company.floorplan.domain.model.SupplierType;
import com.company.floorplan.domain.repository.PurchaseOrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

/**
 * 采购订单主单仓储实现（基础设施层）。
 */
@Repository
public class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<PurchaseOrder> MAPPER = (rs, i) -> {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(rs.getLong("id"));
        p.setOrderNo(rs.getString("order_no"));
        p.setDealerId(rs.getLong("dealer_id"));
        long creditLineId = rs.getLong("credit_line_id");
        p.setCreditLineId(rs.wasNull() ? null : creditLineId);
        long supplierId = rs.getLong("supplier_id");
        p.setSupplierId(rs.wasNull() ? null : supplierId);
        String type = rs.getString("supplier_type");
        p.setSupplierType(type == null ? null : SupplierType.valueOf(type));
        p.setPayeeAccountId(rs.getLong("payee_account_id"));
        p.setPayeeAccountSnapshot(rs.getString("payee_account_snapshot"));
        p.setTotalAmount(rs.getLong("total_amount"));
        p.setVehicleCount(rs.getInt("vehicle_count"));
        p.setPurchasedAt(rs.getDate("purchased_at") == null ? null : rs.getDate("purchased_at").toLocalDate());
        p.setEvidenceNo(rs.getString("evidence_no"));
        long attachmentId = rs.getLong("evidence_attachment_id");
        p.setEvidenceAttachmentId(rs.wasNull() ? null : attachmentId);
        String status = rs.getString("status");
        p.setStatus(status == null ? PurchaseOrderStatus.CONFIRMED : PurchaseOrderStatus.valueOf(status));
        String channel = rs.getString("ingest_channel");
        p.setIngestChannel(channel == null ? IngestChannel.EXCEL : IngestChannel.valueOf(channel));
        long batchId = rs.getLong("import_batch_id");
        p.setImportBatchId(rs.wasNull() ? null : batchId);
        p.setCreatedAt(rs.getTimestamp("created_at") == null ? null
                : rs.getTimestamp("created_at").toLocalDateTime());
        p.setUpdatedAt(rs.getTimestamp("updated_at") == null ? null
                : rs.getTimestamp("updated_at").toLocalDateTime());
        return p;
    };

    public JdbcPurchaseOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PurchaseOrder> findById(Long id) {
        return jdbc.query("SELECT * FROM purchase_orders WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<PurchaseOrder> findByOrderNo(String orderNo) {
        return jdbc.query("SELECT * FROM purchase_orders WHERE order_no = ?", MAPPER, orderNo)
                .stream().findFirst();
    }

    @Override
    public List<PurchaseOrder> findAll() {
        return jdbc.query("SELECT * FROM purchase_orders ORDER BY id DESC", MAPPER);
    }

    @Override
    public List<PurchaseOrder> findByDealerId(Long dealerId) {
        return jdbc.query("SELECT * FROM purchase_orders WHERE dealer_id = ? ORDER BY id DESC", MAPPER, dealerId);
    }

    @Override
    public List<PurchaseOrder> findByImportBatchId(Long importBatchId) {
        return jdbc.query("SELECT * FROM purchase_orders WHERE import_batch_id = ? ORDER BY id", MAPPER, importBatchId);
    }

    @Override
    public PurchaseOrder save(PurchaseOrder p) {
        if (p.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO purchase_orders (order_no, dealer_id, credit_line_id, supplier_id, supplier_type, "
                            + "payee_account_id, payee_account_snapshot, total_amount, vehicle_count, purchased_at, "
                            + "evidence_no, evidence_attachment_id, status, ingest_channel, import_batch_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    p.getOrderNo(), p.getDealerId(), p.getCreditLineId(), p.getSupplierId(),
                    p.getSupplierType() == null ? null : p.getSupplierType().name(),
                    p.getPayeeAccountId(), p.getPayeeAccountSnapshot(),
                    p.getTotalAmount(), p.getVehicleCount(), toSqlDate(p.getPurchasedAt()),
                    p.getEvidenceNo(), p.getEvidenceAttachmentId(),
                    p.getStatus() == null ? PurchaseOrderStatus.CONFIRMED.name() : p.getStatus().name(),
                    p.getIngestChannel() == null ? IngestChannel.EXCEL.name() : p.getIngestChannel().name(),
                    p.getImportBatchId());
            p.setId(id);
        } else {
            jdbc.update("UPDATE purchase_orders SET order_no=?, dealer_id=?, credit_line_id=?, supplier_id=?, "
                            + "supplier_type=?, payee_account_id=?, payee_account_snapshot=?, total_amount=?, "
                            + "vehicle_count=?, purchased_at=?, evidence_no=?, evidence_attachment_id=?, "
                            + "status=?, ingest_channel=?, updated_at=now() WHERE id=?",
                    p.getOrderNo(), p.getDealerId(), p.getCreditLineId(), p.getSupplierId(),
                    p.getSupplierType() == null ? null : p.getSupplierType().name(),
                    p.getPayeeAccountId(), p.getPayeeAccountSnapshot(),
                    p.getTotalAmount(), p.getVehicleCount(), toSqlDate(p.getPurchasedAt()),
                    p.getEvidenceNo(), p.getEvidenceAttachmentId(),
                    p.getStatus() == null ? PurchaseOrderStatus.CONFIRMED.name() : p.getStatus().name(),
                    p.getIngestChannel() == null ? IngestChannel.EXCEL.name() : p.getIngestChannel().name(),
                    p.getId());
        }
        return p;
    }

    @Override
    public int updateStatus(Long id, String status) {
        return jdbc.update("UPDATE purchase_orders SET status=?, updated_at=now() WHERE id=?", status, id);
    }

    @Override
    public boolean existsActiveVin(String vin, Long excludeOrderId) {
        // 与 uk_po_vin_active 条件唯一索引保持同一口径：
        // 同一 VIN 不得出现在两个"未终结"的采购订单中。
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle_units v "
                        + "JOIN purchase_orders p ON p.id = v.purchase_order_id "
                        + "WHERE v.vin = ? AND p.status IN ('DRAFT','CONFIRMED','DISBURSED') "
                        + "AND (? IS NULL OR p.id <> ?)",
                Integer.class, vin, excludeOrderId, excludeOrderId);
        return count != null && count > 0;
    }

    @Override
    public Optional<PurchaseOrder> findByActiveVin(String vin) {
        return jdbc.query(
                        "SELECT p.* FROM purchase_orders p "
                                + "JOIN vehicle_units v ON v.purchase_order_id = p.id "
                                + "WHERE v.vin = ? AND p.status IN ('DRAFT','CONFIRMED','DISBURSED') "
                                + "ORDER BY p.id LIMIT 1",
                        MAPPER, vin)
                .stream().findFirst();
    }

    private static Date toSqlDate(java.time.LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
