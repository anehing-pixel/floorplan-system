package com.company.floorplan.common.contract;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 担保权属查询契约（Wave 0 冻结，实现方不许改签名）。
 *
 * <p>由 core 在放款前同步调用，做 B1 担保门禁：拿不到 REGISTERED 结果就 fail-closed 拒付。
 * 实现方是 collateral-reg-service；本地未起该服务时 core 侧走 fallback 拒绝放款。</p>
 */
@FeignClient(name = "collateral-reg-service", path = "/api/collateral-reg",
        fallback = CollateralClient.Fallback.class)
public interface CollateralClient {

    /** 查某 VIN 当前是否有已登记（REGISTERED）的担保批次 */
    @GetMapping("/registration")
    RegistrationVO getRegistration(@RequestParam("vin") String vin);

    /** 契约的 VO：只暴露放款门禁需要的字段，不暴露权属表全貌 */
    class RegistrationVO {
        private String status;        // REGISTERED / PENDING / CANCELLED / NONE
        private String regNo;         // 登记编号
        private String ownerName;     // 权利人
        private String registeredAt;  // 登记时间（ISO）

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getRegNo() { return regNo; }
        public void setRegNo(String regNo) { this.regNo = regNo; }
        public String getOwnerName() { return ownerName; }
        public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
        public String getRegisteredAt() { return registeredAt; }
        public void setRegisteredAt(String registeredAt) { this.registeredAt = registeredAt; }
    }

    /** 降级：拿不到权属服务就返回 NONE，core 据此拒付，绝不放行 */
    class Fallback implements CollateralClient {
        @Override
        public RegistrationVO getRegistration(String vin) {
            RegistrationVO vo = new RegistrationVO();
            vo.setStatus("NONE");
            return vo;
        }
    }
}
