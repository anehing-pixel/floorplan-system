package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.UsedReleaseItemType;

/**
 * 按项接收方解析（R7.3，D09 §4.1 的四条优先级）。
 *
 * <p>这是 {@code custodians} 主数据在本仓第一次被真实读用。
 * 第 4 条「解析失败 = 拒发指令（{@code RELS_006}），<b>不允许默认发给某个 custodian</b>」
 * 是本类存在的理由：只要留一个兜底默认值，前三条优先级就永远不会有人去核对，
 * 而放车对象错了以后，回执仍然"看起来是权威回执"（partner 是真的，只是不该收到这条）。</p>
 */
public final class ReceiverResolutionPolicy {

    private ReceiverResolutionPolicy() {
    }

    /** D09 §4.1 的 ReceiverSpec 四字段，原样保留。 */
    public static final class ReceiverSpec {
        /** 接收监管方编码（对应 V68 的 receiver_custodian_code）。 */
        public final String receiverCustodianCode;
        /** 接收 partner 编码（对应 V68 的 receiver_partner_code）。 */
        public final String receiverPartnerCode;
        /** 下发通道（对应 release_orders.delivery_channel；D09 §4.2 表里按接收方类型定）。 */
        public final String deliveryChannel;
        /** 下达给对方的指令文本（人工/门户通道要靠它把"放什么"说清）。 */
        public final String instructionText;

        public ReceiverSpec(String receiverCustodianCode, String receiverPartnerCode,
                            String deliveryChannel, String instructionText) {
            this.receiverCustodianCode = receiverCustodianCode;
            this.receiverPartnerCode = receiverPartnerCode;
            this.deliveryChannel = deliveryChannel;
            this.instructionText = instructionText;
        }
    }

    /** 输入三源：项显式指定、車検証持有方、车辆实控仓（D09 §4.1 优先级 1/2/3）。 */
    public static ReceiverSpec resolve(UsedReleaseItemType itemType,
                                       String itemCustodianCode, String itemPartnerCode,
                                       String certificateHolderCustodianCode,
                                       String vehicleWarehouseCustodianCode,
                                       String instructionText) {
        // 优先级 1：指令项显式指定（人工创建时可指定）
        if (itemCustodianCode != null && !itemCustodianCode.isBlank()) {
            return new ReceiverSpec(itemCustodianCode, itemPartnerCode, null, instructionText);
        }
        // 优先级 2：車検証持有方——只对放证线有效，对实车项不能拿保管証書的仓当放车方
        if (itemType != null && itemType.isCertificateLine()
                && certificateHolderCustodianCode != null && !certificateHolderCustodianCode.isBlank()) {
            return new ReceiverSpec(certificateHolderCustodianCode, itemPartnerCode, null, instructionText);
        }
        // 优先级 3：车辆实控仓——只对实车项有效
        // 【依赖】vehicle_units.warehouse_custodian_code 不在本分支可见（V1–V26 无此列，属其它包的 V27–V64 段），
        // 该列没建出来时这里恒取不到值 → 落 RELS_006 拒发，不会静默放行。
        if (itemType == UsedReleaseItemType.VEHICLE
                && vehicleWarehouseCustodianCode != null && !vehicleWarehouseCustodianCode.isBlank()) {
            return new ReceiverSpec(vehicleWarehouseCustodianCode, itemPartnerCode, null, instructionText);
        }
        // 优先级 4：拒发。deliveryChannel 不在这里猜——通道由 ReleaseChannelSelector 按主数据能力定
        throw new BusinessException(ErrorCode.RELS_006,
                "接收方解析失败（项类型 " + itemType + " 的三键均无值），拒发指令，不做默认投递");
    }
}
