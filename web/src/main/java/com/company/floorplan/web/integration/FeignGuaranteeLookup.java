package com.company.floorplan.web.integration;

import com.company.floorplan.common.contract.CollateralClient;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * T1-5（B1）担保门禁的 Feign 适配器。
 *
 * <p>把 {@link CollateralClient}（common 契约，签名冻结不改）适配成领域层
 * {@link RoleSeparationPolicy.GuaranteeLookup} 端口，让 application 层无需依赖 common。
 * 门禁开关默认关闭，未起 collateral-reg-service 时本适配器不会被调用；
 * 一旦调用失败返回 null，判定策略按 fail-closed 阻断放款。</p>
 *
 * <p><b>TODO(Q27)</b>：契约返回的 status/regNo/ownerName 来自 collateral-reg-service 权属表，
 * 与 core 的 V10 批次表互不一致；判定源需业务书面确认后方可开启门禁。
 * 本适配器只做字段搬运，不判断哪张表权威。</p>
 */
@Component
public class FeignGuaranteeLookup implements RoleSeparationPolicy.GuaranteeLookup {

    private final CollateralClient collateralClient;

    public FeignGuaranteeLookup(@Qualifier("collateral-reg-service") CollateralClient collateralClient) {
        this.collateralClient = collateralClient;
    }

    @Override
    public GuaranteeView findByVin(String vin) {
        try {
            CollateralClient.RegistrationVO vo = collateralClient.getRegistration(vin);
            if (vo == null) {
                return null;
            }
            return new GuaranteeView(vo.getStatus(), vo.getRegNo(), vo.getOwnerName(), vo.getRegisteredAt());
        } catch (RuntimeException ex) {
            // 服务不可达/降级失败 → null；判定策略按 fail-closed 处理（仅门禁开启时才会走到）
            return null;
        }
    }
}
