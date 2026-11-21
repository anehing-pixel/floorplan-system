package com.company.floorplan.application.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 采购清单导入的解析结果与行级错误。
 *
 * <p><b>为什么要"行级"错误</b>：一份采购清单动辄几十上百台车，
 * 整批拒绝会让用户反复试错；而"静默跳过错误行"更糟——用户以为导入了成功，
 * 实际少了几台车。故采用<b>部分成功语义</b>：能校验的行全部校验完，
 * 错误精确到"第几行、哪个字段、为什么"，可下载修正后重传。</p>
 */
public final class ImportResult {

    /** 单行错误。 */
    public record RowError(int rowNo, String fieldName, String reason, String rawValue) {
        /** 转为可序列化的行数组。 */
        public Object[] toArray() {
            return new Object[]{rowNo, fieldName, reason, rawValue};
        }
    }

    /** 解析出的一行（已通过格式与业务校验）。 */
    public record ParsedRow(
            int rowNo,
            String orderNo,
            String vin,
            String model,
            Integer modelYear,
            String color,
            String supplierType,
            long invoiceAmount,
            String payeeAccountRef,
            String vehicleStatus,
            String evidenceNo,
            String attachmentId) {
    }

    private final int totalRows;
    private final List<ParsedRow> accepted;
    private final List<RowError> errors;
    private final long totalAmount;
    private final String fileHash;

    public ImportResult(int totalRows, List<ParsedRow> accepted, List<RowError> errors,
                        long totalAmount, String fileHash) {
        this.totalRows = totalRows;
        this.accepted = accepted == null ? new ArrayList<>() : accepted;
        this.errors = errors == null ? new ArrayList<>() : errors;
        this.totalAmount = totalAmount;
        this.fileHash = fileHash;
    }

    public int getTotalRows() { return totalRows; }

    public List<ParsedRow> getAccepted() { return accepted; }

    public List<RowError> getErrors() { return errors; }

    public long getTotalAmount() { return totalAmount; }

    public String getFileHash() { return fileHash; }

    /** 被拒绝的行数。 */
    public int getRejectedRows() {
        return errors.size();
    }

    /** 是否全部通过（无一行错误）。 */
    public boolean isAllAccepted() {
        return errors.isEmpty();
    }
}
