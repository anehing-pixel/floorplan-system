package com.company.floorplan.application.dto;

import java.time.LocalDate;

/**
 * 車検証登记/换发命令（USED R2，UC-R2-5）。
 *
 * <p>同一份命令服务两个用例：{@code attachCertificate}（首次登记，VIN 下必须没有在场证）与
 * {@code rebindCertificate}（换发，旧证置 REISSUED 并以 {@code predecessor_id} 成链）。
 * 分成两个命令只会让「換発時にどの項目が上書きされるのか」在两处各写一遍——
 * 而这正是最容易写歪的地方（旧证的保管信息漏带就会变成「新证不知道在谁手上」）。</p>
 *
 * @param certificateNo      証書番号（必填；{@code CERT_003} 的核验对象）
 * @param vin                車台番号（必填；路径上也带 vin 时以两者一致为准，不一致即 CERT_003）
 * @param registrationPlate  登録番号
 * @param ownerName          所有者
 * @param ownerAddress       所有者住址
 * @param userName           使用者
 * @param userAddress        使用者住址
 * @param firstRegisteredOn  初度登録年月
 * @param inspectionExpiryDate 車検満了日（库内 NOT NULL）
 * @param issueDate          発行年月日（库内 NOT NULL）
 * @param securityType       担保形态（V52 八值之一，可空）
 * @param mortgageFlag       抵当権有无（票面事实）
 * @param encumbranceStatus  瑕疵聚合结论（V52 六值之一，可空＝未核查）
 * @param holderCustodianCode 保管者代码
 * @param holderType         保管者类型
 * @param custodyLocation    保管位置
 * @param evidencePath       扫描件
 * @param evidenceDigest     扫描件摘要
 * @param checkSource        本次登记的核查来源（MANUAL / REGISTRY_AGENT / REGISTRY_API），写进 custody 流水
 * @param reason             事由（换发必填：无原因的换证在审计上等同改数）
 */
public record CertificateDraft(
        String certificateNo,
        String vin,
        String registrationPlate,
        String ownerName,
        String ownerAddress,
        String userName,
        String userAddress,
        LocalDate firstRegisteredOn,
        LocalDate inspectionExpiryDate,
        LocalDate issueDate,
        String securityType,
        Boolean mortgageFlag,
        String encumbranceStatus,
        String holderCustodianCode,
        String holderType,
        String custodyLocation,
        String evidencePath,
        String evidenceDigest,
        String checkSource,
        String reason) {
}
