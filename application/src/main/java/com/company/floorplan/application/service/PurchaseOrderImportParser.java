package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.SupplierType;
import com.company.floorplan.domain.model.VehicleStatusMapping;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 采购清单解析器（ADR-007 决策四 / 决策八）。
 *
 * <p><b>支持两种文件形态</b>：Excel（.xlsx/.xls，POI 解析）与 CSV（UTF-8）。
 * 两者产出<b>同一份结构</b>，下游无感知。</p>
 *
 * <p><b>三条不可让步的规则</b>：</p>
 * <ol>
 *   <li><b>模板固定、列头不符直接拒绝整个文件</b>——不逐行猜测列的含义。
 *       猜错一列的代价是"金额"被当成"年份"入库；</li>
 *   <li><b>逐行校验、逐行报错</b>（行号 + 字段 + 原因），部分成功语义；</li>
 *   <li><b>账户只接受引用码</b>（{@code payeeAccountRef}），
 *       <b>不接受文件里直接写明文账号</b>——否则导入通道就成了塞入攻击者账户的后门。</li>
 * </ol>
 */
@Component
public class PurchaseOrderImportParser {

    /**
     * 模板列头（固定顺序）。
     *
     * <p>与业务确认的清单关键字段一一对应：VIN/车型年款颜色/供应商类型/
     * 采购金额/收款账户/采购订单号/车辆状态/契约附件。</p>
     */
    public static final List<String> TEMPLATE_COLUMNS = List.of(
            "orderNo", "vin", "model", "modelYear", "color",
            "supplierType", "invoiceAmount", "payeeAccountRef",
            "vehicleStatus", "evidenceNo", "attachmentId");

    /** 必填列。 */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            "orderNo", "vin", "model", "modelYear", "color",
            "supplierType", "invoiceAmount", "payeeAccountRef", "vehicleStatus");

    /** 供应商类型允许值（与领域枚举一致）。 */
    private static final Map<String, SupplierType> SUPPLIER_TYPE_MAP = new HashMap<>();

    static {
        for (SupplierType t : SupplierType.values()) {
            SUPPLIER_TYPE_MAP.put(t.name(), t);
            SUPPLIER_TYPE_MAP.put(t.name().toLowerCase(Locale.ROOT), t);
        }
    }

    /**
     * 解析采购清单文件。
     *
     * @param input        文件流
     * @param filename     原始文件名（决定走 Excel 还是 CSV 分支）
     * @param validator    行级业务校验回调（返回错误原因，null 表示通过）
     * @return 解析结果（含通过行与行级错误）
     */
    public ImportResult parse(InputStream input, String filename, RowValidator validator) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "读取上传文件失败：" + e.getMessage());
        }
        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.VALID_001, "上传文件为空");
        }
        String fileHash = sha256(bytes);

        List<List<String>> rows = lower.endsWith(".csv") ? parseCsv(bytes) : parseExcel(bytes);

        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001, "文件内容为空");
        }

        // ---- 列头校验：不符直接拒绝整个文件 ----
        List<String> header = rows.get(0);
        List<String> trimmedHeader = new ArrayList<>();
        for (String h : header) {
            trimmedHeader.add(h == null ? "" : h.trim());
        }
        if (!trimmedHeader.equals(TEMPLATE_COLUMNS)) {
            List<String> missing = new ArrayList<>();
            List<String> unexpected = new ArrayList<>();
            for (String c : TEMPLATE_COLUMNS) {
                if (!trimmedHeader.contains(c)) {
                    missing.add(c);
                }
            }
            for (String h : trimmedHeader) {
                if (!TEMPLATE_COLUMNS.contains(h)) {
                    unexpected.add(h);
                }
            }
            throw new BusinessException(ErrorCode.VALID_001,
                    "列头与模板不符，已拒绝整个文件。缺少列：" + String.join(",", missing)
                            + "；多余列：" + String.join(",", unexpected)
                            + "。标准列序：" + String.join(",", TEMPLATE_COLUMNS));
        }

        // ---- 逐行校验 ----
        List<ImportResult.ParsedRow> accepted = new ArrayList<>();
        List<ImportResult.RowError> errors = new ArrayList<>();
        long totalAmount = 0L;
        int rowNo = 0;

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            rowNo = i + 1;   // 行号按 Excel 实际行号（含表头），便于用户定位

            // 跳过完全空行
            boolean blank = true;
            for (String c : row) {
                if (c != null && !c.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }

            List<ImportResult.RowError> rowErrors = new ArrayList<>();

            String orderNo = cell(row, 0);
            String vin = cell(row, 1);
            String model = cell(row, 2);
            String modelYearRaw = cell(row, 3);
            String color = cell(row, 4);
            String supplierTypeRaw = cell(row, 5);
            String amountRaw = cell(row, 6);
            String accountRef = cell(row, 7);
            String vehicleStatusRaw = cell(row, 8);
            String evidenceNo = cell(row, 9);
            String attachmentId = cell(row, 10);

            // 必填校验
            require(rowErrors, "orderNo", orderNo, rowNo);
            require(rowErrors, "vin", vin, rowNo);
            require(rowErrors, "model", model, rowNo);
            require(rowErrors, "modelYear", modelYearRaw, rowNo);
            require(rowErrors, "color", color, rowNo);
            require(rowErrors, "supplierType", supplierTypeRaw, rowNo);
            require(rowErrors, "invoiceAmount", amountRaw, rowNo);
            require(rowErrors, "payeeAccountRef", accountRef, rowNo);
            require(rowErrors, "vehicleStatus", vehicleStatusRaw, rowNo);

            // 年款：必须是 4 位年份
            Integer modelYear = null;
            if (!modelYearRaw.isEmpty()) {
                try {
                    modelYear = Integer.valueOf(modelYearRaw.trim());
                    if (modelYear < 1900 || modelYear > 2100) {
                        rowErrors.add(new ImportResult.RowError(rowNo, "modelYear",
                                "年款超出合理范围（1900-2100）", modelYearRaw));
                        modelYear = null;
                    }
                } catch (NumberFormatException e) {
                    rowErrors.add(new ImportResult.RowError(rowNo, "modelYear", "年款不是整数", modelYearRaw));
                }
            }

            // 供应商类型
            SupplierType supplierType = null;
            if (!supplierTypeRaw.isEmpty()) {
                supplierType = SUPPLIER_TYPE_MAP.get(supplierTypeRaw.trim().toUpperCase(Locale.ROOT));
                if (supplierType == null) {
                    rowErrors.add(new ImportResult.RowError(rowNo, "supplierType",
                            "供应商类型无效，允许值：" + allowedSupplierTypes(), supplierTypeRaw));
                }
            }

            // 采购金额：整数日元，不允许小数与负数
            long invoiceAmount = 0L;
            if (!amountRaw.isEmpty()) {
                try {
                    BigDecimal bd = new BigDecimal(amountRaw.trim());
                    if (bd.scale() > 0 && bd.stripTrailingZeros().scale() > 0) {
                        rowErrors.add(new ImportResult.RowError(rowNo, "invoiceAmount",
                                "采购金额不允许小数（金额单位为日元整数）", amountRaw));
                    } else if (bd.signum() < 0) {
                        rowErrors.add(new ImportResult.RowError(rowNo, "invoiceAmount", "采购金额不能为负", amountRaw));
                    } else {
                        invoiceAmount = bd.longValueExact();
                    }
                } catch (ArithmeticException | NumberFormatException e) {
                    rowErrors.add(new ImportResult.RowError(rowNo, "invoiceAmount", "采购金额不是合法数字", amountRaw));
                }
            }

            // 车辆状态：业务侧口径 → 领域枚举
            if (!vehicleStatusRaw.isEmpty() && VehicleStatusMapping.map(vehicleStatusRaw) == null) {
                rowErrors.add(new ImportResult.RowError(rowNo, "vehicleStatus",
                        "车辆状态无效，允许值：" + String.join("/", VehicleStatusMapping.allowedValues()),
                        vehicleStatusRaw));
            }

            // 账户引用码：格式校验（只接受引用码，不接受明文账号）
            if (!accountRef.isEmpty() && !accountRef.trim().matches("^[A-Za-z0-9_-]{2,32}$")) {
                rowErrors.add(new ImportResult.RowError(rowNo, "payeeAccountRef",
                        "收款账户必须填业务引用码（字母数字下划线，2-32 位），不接受明文账号", accountRef));
            }

            // 业务层校验（VIN 幂等、账户存在性等）由调用方注入。
            // 必须在格式校验之后、判定通过之前执行——即使本行已有格式错误，
            // 也要把业务错误一并报出来，否则用户改完格式再导入，又会冒出新的业务错误。
            if (validator != null) {
                String bizError = validator.validate(rowNo, orderNo, vin, accountRef);
                if (bizError != null) {
                    rowErrors.add(new ImportResult.RowError(rowNo, "vin", bizError, vin));
                }
            }

            if (!rowErrors.isEmpty()) {
                errors.addAll(rowErrors);
                continue;
            }

            accepted.add(new ImportResult.ParsedRow(rowNo, orderNo, vin, model, modelYear, color,
                    supplierType.name(), invoiceAmount, accountRef, vehicleStatusRaw, evidenceNo, attachmentId));
            totalAmount += invoiceAmount;
        }

        return new ImportResult(accepted.size() + errors.size(), accepted, errors, totalAmount, fileHash);
    }

    /** 行级业务校验回调。 */
    public interface RowValidator {
        /**
         * 校验一行。
         *
         * @param rowNo      行号
         * @param orderNo    采购订单号
         * @param vin        VIN
         * @param accountRef 账户引用码
         * @return 错误原因；null 表示通过
         */
        String validate(int rowNo, String orderNo, String vin, String accountRef);
    }

    /* ---------------- 解析实现 ---------------- */

    private List<List<String>> parseExcel(byte[] bytes) {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < TEMPLATE_COLUMNS.size(); c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                }
                rows.add(cells);
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "Excel 解析失败：" + e.getMessage());
        }
        return rows;
    }

    private List<List<String>> parseCsv(byte[] bytes) {
        List<List<String>> rows = new ArrayList<>();
        // 去 BOM：Excel 导出的 CSV 常带 UTF-8 BOM，不去会导致第一列名变成 "﻿orderNo" 而校验失败。
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cells = parseCsvLine(line);
                // 补齐到模板列数，避免短行越界
                while (cells.size() < TEMPLATE_COLUMNS.size()) {
                    cells.add("");
                }
                rows.add(cells);
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALID_001, "CSV 解析失败：" + e.getMessage());
        }
        return rows;
    }

    /** 简易 CSV 行解析（支持双引号包裹与转义双引号）。 */
    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /* ---------------- 工具 ---------------- */

    private static void require(List<ImportResult.RowError> errors, String field,
                                String value, int rowNo) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(new ImportResult.RowError(rowNo, field, "必填项不能为空", value));
        }
    }

    private static String cell(List<String> row, int idx) {
        if (idx >= row.size()) {
            return "";
        }
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    private static String allowedSupplierTypes() {
        return String.join("/", Arrays.stream(SupplierType.values()).map(Enum::name).toList());
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
