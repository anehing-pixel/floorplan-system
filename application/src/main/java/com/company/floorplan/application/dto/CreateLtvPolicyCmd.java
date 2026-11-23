package com.company.floorplan.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新建 LTV 政策草稿（USED R3.4，D07 §7.2 的 {@code POST /api/ltv-policies}）。
 *
 * <p><b>不带带集合</b>：D07 把「建版」与「配带」分成两个动作
 * （{@code POST /api/ltv-policies} 与 {@code PUT /api/ltv-policies/{id}/bands}），
 * 本包不在 POST 上偷偷支持一次性提交带集合。分开的实际收益是：改带要走
 * {@code LtvPolicyValidator} 的全套不变式，而建新版不需要——两者混在一个请求里，
 * 「带没通过校验」就会连带把新建的政策头也回滚掉，调用方拿不到「头已建好、带待改」这个中间态。</p>
 *
 * @param policyCode    政策码（必填）；同一码内 {@code version} 递增，同时只有一行 ACTIVE
 * @param policyName    政策名（必填）
 * @param effectiveFrom 生效起日（必填，V57 NOT NULL）
 * @param effectiveTo   失效日；空＝长期有效
 * @param defaultRatio  兜底成数（L2 全覆盖缺口的承接值）；空＝无兜底，零命中一律拒付
 *                      （D08 §4.2：这不是「宽松」，是「缺口即阻断」）
 */
public record CreateLtvPolicyCmd(
        String policyCode,
        String policyName,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal defaultRatio) {
}
