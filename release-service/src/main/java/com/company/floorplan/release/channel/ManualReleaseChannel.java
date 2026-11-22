package com.company.floorplan.release.channel;

import com.company.floorplan.release.model.CustodianResolver;
import com.company.floorplan.release.model.CustodianType;
import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 起步下发通道：MANUAL 人工转交（Q10 已由《业务口径总定源》ADR §2 裁决定稿的正式实现）。
 *
 * <p><b>为什么这是正式实现，不再是占位日志</b>：按《业务流程文档》§6 已确认的日本
 * 新车渠道，没有第三方实体监管、也没有外部 WMS 可直连；设计稿 §7 #6 与 ADR-009
 * 「人工路径一等公民」都明确起步以人工转交为主。所以 Q10 的答案收敛为：
 * <b>起步无外部系统对接</b>，由系统生成结构化交接单，人工交给对应接收方，
 * 回执由我方担保管理岗见证代录（见 {@code ReleaseApplicationService#confirm}）。</p>
 *
 * <p><b>逐项交接</b>：一张指令可能同时放车与放证，两条线接收方不同。
 * 因此按释放项打印交接单，并给出该项按渠道口径应交给的接收方类型，
 * 让人工转交时不会把证错交给经销商库。</p>
 *
 * <p><b>仍是接缝</b>：本实现不连任何外部系统、不产生外部副作用。
 * 将来真有 partner 系统（监管门户 / API / MQ）时，新增一个 {@link ReleaseChannel}
 * 实现并替换 {@code @Import}，应用服务一行不改；届时签名走 ADR-009 DMZ 入口，
 * 失败要重试 + 降级人工 + 告警，回执 {@code source} 相应改为 PORTAL/API/MQ。</p>
 */
public class ManualReleaseChannel implements ReleaseChannel {

    private static final Logger log = LoggerFactory.getLogger(ManualReleaseChannel.class);

    @Override
    public void dispatch(ReleaseOrder order, List<ReleaseOrderItem> items) {
        log.info("[RELEASE_CHANNEL][MANUAL] 人工转交交接单 orderNo={} vin={} validUntil={} "
                        + "primaryCustodianId={} certificateNo={} itemCount={}",
                order.getOrderNo(), order.getVin(), order.getValidUntil(),
                order.getCustodianId(), order.getCertificateNo(), items.size());
        for (ReleaseOrderItem item : items) {
            CustodianType expected = CustodianResolver.expectedType(item.getItemType());
            log.info("[RELEASE_CHANNEL][MANUAL][ITEM] orderNo={} item={} custodianId={} "
                            + "expectedCustodianType={} itemNo={} action=请人工转交并回收回执",
                    order.getOrderNo(), item.getItemType(), item.getCustodianId(),
                    expected, item.getItemNo());
        }
    }
}
