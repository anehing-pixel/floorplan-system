package com.company.floorplan.domain.model;

/**
 * 导入行级错误明细（行号 + 字段 + 原因）。
 *
 * <p>用于"预览"阶段把问题精确告诉用户，用户可下载修正后重传。
 * 错误必须精确到行——整批拒绝会让用户反复试错，静默跳过更糟
 * （用户以为导入了成功，实际少了几台车）。</p>
 */
public class ImportRowError {

    private Long id;
    private Long batchId;
    private int rowNo;
    private String fieldName;
    private String reason;
    private String rawValue;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }

    public int getRowNo() { return rowNo; }
    public void setRowNo(int rowNo) { this.rowNo = rowNo; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRawValue() { return rawValue; }
    public void setRawValue(String rawValue) { this.rawValue = rawValue; }
}
