package com.company.floorplan.release.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令释放项类型：一张指令可组合"放车 + 放证 + 放钥匙"。
 *
 * <p><b>为什么"放车没放证"必须能单独表达</b>（设计稿 §1.2⑦）：
 * 车与权属凭证可能分处两地、由不同机构交接，只放了车没放证是<b>真实的法律状态差异</b>——
 * 车已交付但物权凭证未转移，不是数据错误。把它压成一个"已放行"布尔，
 * 就再也无法解释这类账实缺口。</p>
 *
 * <p><b>KEY 只作事实记录</b>：日本渠道不扣押钥匙作为控制手段（业务流程文档第 6 节），
 * 但盘点时记录钥匙是否齐全仍是证据链的一部分。</p>
 *
 * <p><b>接收方按类型分线</b>：{@link #defaultCustodianType()} 给出日本新车渠道下
 * 该项应交给哪一类接收方——放车/交钥匙归经销商库，放证归凭证保管点。
 * 这也是 Q9「不该四选一、应按释放项指定」的落点。</p>
 */
public enum ReleaseItemType {

    VEHICLE,
    CERTIFICATE,
    KEY;

    /**
     * 该项按日本新车渠道口径应交给哪一类接收方。
     *
     * <p>车物理在经销商库（业务流程文档 §6 已确认无第三方实体监管），
     * 权属凭证才由金融公司侧保管点控制，故 CERTIFICATE 单独走放证线。</p>
     */
    public CustodianType defaultCustodianType() {
        return switch (this) {
            case CERTIFICATE -> CustodianType.CERTIFICATE_DEPOSITORY;
            case VEHICLE, KEY -> CustodianType.DEALER_WAREHOUSE;
        };
    }

    /**
     * 解析主表 {@code release_items} 的逗号分隔字符串。
     *
     * @param csv 形如 "VEHICLE,CERTIFICATE"；null/空白返回空列表
     * @return 有序、去重的类型列表
     * @throws IllegalArgumentException 出现未知类型时拒绝建单（不静默丢弃）
     */
    public static List<ReleaseItemType> parseList(String csv) {
        List<ReleaseItemType> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String part : csv.split(",")) {
            String token = part.trim().toUpperCase();
            if (token.isEmpty()) {
                continue;
            }
            ReleaseItemType type = valueOf(token); // 未知类型直接抛，交由上层转 400
            if (!result.contains(type)) {
                result.add(type);
            }
        }
        return result;
    }
}
