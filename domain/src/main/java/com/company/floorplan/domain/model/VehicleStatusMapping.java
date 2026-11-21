package com.company.floorplan.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 车辆状态的<b>双语义映射</b>（ADR-007 决策九）。
 *
 * <p><b>为什么需要这一层</b>：业务方给的采购清单里，车辆状态用的是<b>库存视角</b>——
 * "待放款 / 在途 / 在库 / 已质押 / 已解押"，回答的是"钱付了没有、车在哪儿、担保解没解"。
 * 而领域模型 {@link VehicleStatus} 是<b>融资视角</b>——
 * {@code PENDING_ADVANCE / FINANCED / IN_STOCK / SOLD_PENDING / SETTLED / OVERDUE / DISPOSING}，
 * 回答的是"这台车在融资流程的哪一步"。</p>
 *
 * <p>两套语义<b>不是一一对应</b>：</p>
 * <ul>
 *   <li>业务侧"待放款" → {@code PENDING_ADVANCE}（台账已建，钱还没付）；</li>
 *   <li>业务侧"在途" → {@code FINANCED}（已付款未到货）；</li>
 *   <li>业务侧"在库" → {@code IN_STOCK}（已到店，开始计息）；</li>
 *   <li>业务侧"已质押" → {@code IN_STOCK} + 担保标记（<b>不是独立状态</b>）；</li>
 *   <li>业务侧"已解押" → {@code SETTLED} 或 {@code SOLD_PENDING}（取决于是否已还款结清）。</li>
 * </ul>
 *
 * <p><b>这条映射放在领域层</b>（与 {@link VehicleStatus#canTransitTo} 同一处），
 * 而不是 web/application 层——因为它是业务规则，不是传输层细节。
 * 放错层的代价是：导入层静默污染台账状态，而且错了不报错。</p>
 */
public final class VehicleStatusMapping {

    /**
     * 业务侧状态 → 领域状态 + 担保标记。
     *
     * @param vehicleStatus 领域状态
     * @param secured       是否已设押（"已质押"为 true）
     */
    public record Mapping(VehicleStatus vehicleStatus, boolean secured) {}

    /** 业务侧口径：导入文件里可能出现的原始字符串 → 映射结果。 */
    private static final Map<String, Mapping> BUSINESS_TO_DOMAIN = new LinkedHashMap<>();

    static {
        // 业务侧原文 → 领域映射。注意"已质押/已解押"落到状态 + 担保标记，
        // 而不是造两个新的领域状态。
        // "已建账 / 待验车"：台账刚建、未申请垫款。V13 引入 CREATED 后补的入向口径。
        BUSINESS_TO_DOMAIN.put("已建账", new Mapping(VehicleStatus.CREATED, false));
        BUSINESS_TO_DOMAIN.put("待验车", new Mapping(VehicleStatus.CREATED, false));
        // "待放款"是 V12 引入 PENDING_ADVANCE 后补的入向口径：已申请、钱未付。
        BUSINESS_TO_DOMAIN.put("待放款", new Mapping(VehicleStatus.PENDING_ADVANCE, false));
        BUSINESS_TO_DOMAIN.put("在途", new Mapping(VehicleStatus.FINANCED, false));
        BUSINESS_TO_DOMAIN.put("已放款", new Mapping(VehicleStatus.FINANCED, false));
        BUSINESS_TO_DOMAIN.put("在库", new Mapping(VehicleStatus.IN_STOCK, false));
        BUSINESS_TO_DOMAIN.put("已到店", new Mapping(VehicleStatus.IN_STOCK, false));
        BUSINESS_TO_DOMAIN.put("已质押", new Mapping(VehicleStatus.IN_STOCK, true));
        BUSINESS_TO_DOMAIN.put("已解押", new Mapping(VehicleStatus.SETTLED, false));
        // "已赎回"：本金已结清、担保已解除，但车还在经销商场地，可再次质押融资。
        // 不能落到 SETTLED——那表示车已售出、永不回来，会被当成终态拒绝再次申请。
        BUSINESS_TO_DOMAIN.put("已赎回", new Mapping(VehicleStatus.REDEEMED, false));
    }

    /** 领域状态 → 业务侧展示口径（回显用）。 */
    private static final Map<VehicleStatus, String> DOMAIN_TO_BUSINESS = Map.of(
            VehicleStatus.CREATED, "已建账",
            VehicleStatus.PENDING_ADVANCE, "待放款",
            VehicleStatus.FINANCED, "在途",
            VehicleStatus.IN_STOCK, "在库",
            VehicleStatus.SOLD_PENDING, "已售待结算",
            VehicleStatus.SETTLED, "已解押",
            VehicleStatus.OVERDUE, "逾期",
            VehicleStatus.DISPOSING, "处置中",
            VehicleStatus.CLOSED, "已关闭",
            // 已赎回与已解押资金侧一致（本金结清、担保解除），
            // 差别只在车还在库、可再次融资，因此单独给一个业务口径便于区分。
            VehicleStatus.REDEEMED, "已赎回可再融资");

    private VehicleStatusMapping() {
    }

    /**
     * 把业务侧状态字符串映射为领域状态 + 担保标记。
     *
     * @param businessValue 业务侧原始值（允许首尾空白与全角空格）
     * @return 映射结果；无法识别时返回 {@code null}（调用方负责给出行级错误）
     */
    public static Mapping map(String businessValue) {
        if (businessValue == null) {
            return null;
        }
        String normalized = businessValue.trim().replace('　', ' ');
        Mapping m = BUSINESS_TO_DOMAIN.get(normalized);
        if (m != null) {
            return m;
        }
        // 兼容全角/半角差异与常见别名
        switch (normalized) {
            case "在庫", "在店":
                return new Mapping(VehicleStatus.IN_STOCK, false);
            case "质押", "已抵押":
                return new Mapping(VehicleStatus.IN_STOCK, true);
            case "解押", "已解除质押":
                return new Mapping(VehicleStatus.SETTLED, false);
            default:
                return null;
        }
    }

    /**
     * 把领域状态回显为业务侧口径。
     *
     * @param status 领域状态
     * @return 业务侧展示文本
     */
    public static String toBusiness(VehicleStatus status) {
        if (status == null) {
            return "-";
        }
        return DOMAIN_TO_BUSINESS.getOrDefault(status, status.name());
    }

    /**
     * 导入时点允许出现的业务侧取值集合（用于模板说明与错误提示）。
     *
     * @return 允许值集合
     */
    public static Set<String> allowedValues() {
        return BUSINESS_TO_DOMAIN.keySet();
    }
}
