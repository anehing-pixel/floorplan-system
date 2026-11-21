package com.company.floorplan.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入批次（一次采购清单导入的留痕）。
 *
 * <p><b>为什么要单独建表</b>：既有 {@code batch_runs} 记的是系统作业批处理，
 * 本表记的是外部文件导入——两者生命周期不同：作业批次由调度触发，
 * 导入批次由人工或上游推送触发，且需要记文件名与文件哈希。</p>
 */
public class ImportBatch {

    private Long id;
    private IngestChannel channel;
    private String filename;
    /** 文件哈希：同一份文件重复导入可识别。 */
    private String fileHash;
    private int totalRows;
    private int acceptedRows;
    private int rejectedRows;
    private long totalAmount;
    /** PREVIEWED / COMMITTED / REJECTED。 */
    private String status;
    private String operator;
    private LocalDateTime createdAt;
    private LocalDateTime committedAt;
    /** 行级错误明细。 */
    private List<ImportRowError> rowErrors = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public IngestChannel getChannel() { return channel; }
    public void setChannel(IngestChannel channel) { this.channel = channel; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    public int getAcceptedRows() { return acceptedRows; }
    public void setAcceptedRows(int acceptedRows) { this.acceptedRows = acceptedRows; }

    public int getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(int rejectedRows) { this.rejectedRows = rejectedRows; }

    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCommittedAt() { return committedAt; }
    public void setCommittedAt(LocalDateTime committedAt) { this.committedAt = committedAt; }

    public List<ImportRowError> getRowErrors() { return rowErrors; }
    public void setRowErrors(List<ImportRowError> rowErrors) { this.rowErrors = rowErrors; }
}
