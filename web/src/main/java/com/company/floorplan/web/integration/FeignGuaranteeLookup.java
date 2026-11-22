package com.company.floorplan.web.integration;

import com.company.floorplan.common.contract.CollateralClient;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import org.springframework.stereotype.Component;

/**
 * T1-5（B1）担保门禁的 Feign 适配器。
 *
 * <p>把 {@link CollateralClient}（common 契约，签名冻结不改）适配成领域层
 * {@link RoleSeparationPolicy.GuaranteeLookup} 端口，让 application 层无需依赖 common。
 * 门禁开关默认关闭，未起 collateral-reg-service 时本适配器不会被调用；
 * 一旦调用失败返回 null，判定策略按 fail-closed 阻断放款。</p>
 *
 * <p><b>ADR-Q27 已裁决</b>：契约返回的 status/regNo/ownerName 由 collateral-reg-service
 * 直读 V10 登记批次表（唯一权威源）后生成，与 core 口径一致，不再是「另一套权属表」。
 * 本适配器只做字段搬运，不重复判定。</p>
 */
@Component
public class FeignGuaranteeLookup implements RoleSeparationPolicy.GuaranteeLookup {

    private final CollateralClient collateralClient;

    // 不指定 @Qualifier：@FeignClient 契约未声明 qualifiers，Feign 代理的 bean 名是接口全限定名、
    // alias 是 "collateral-reg-serviceFeignClient"，都不是服务名 "collateral-reg-service"。
    // 原写法 @Qualifier("collateral-reg-service") 匹配不上任何 bean，导致 web 启动即失败。
    // 这里按类型注入即可：@FeignClient.primary() 默认 true，代理会被优先选中，
    // 与 GuaranteeFeignConfig 注册的 fallback bean 不冲突。
    public FeignGuaranteeLookup(CollateralClient collateralClient) {
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
