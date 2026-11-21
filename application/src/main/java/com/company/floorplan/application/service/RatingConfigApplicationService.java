package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.RatingConfig;
import com.company.floorplan.domain.repository.RatingConfigRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 授信评级分级口径用例（后台动态配置）。
 *
 * <p><b>业务定位</b>：分级口径此前标注为"⚠️【预设】待客户确认"，
 * 本用例把它从"代码里的常量"变成"数据库里的配置"——
 * 客户确认后，运营通过管理接口直接改口径，<b>即时生效、无需发版</b>。</p>
 *
 * <p><b>口径如何生效</b>：</p>
 * <ul>
 *   <li><b>利率加点</b>：垫款建账时按经销商当前评级解析加点，
 *       执行利率 = 基准年利率 + 加点（见 {@link AdvanceApplicationService}）；</li>
 *   <li><b>准入开关</b>：等级停用后，该等级经销商不再受理新增垫款建账
 *       （存量车辆的还款/结清不受影响，避免误伤在途业务）。</li>
 * </ul>
 *
 * <p><b>内控</b>：口径变更属于授信敏感操作，每次保存都写审计事件
 * （{@code RATING_CONFIG_UPSERT}），记录操作人与变更内容。</p>
 */
@Service
public class RatingConfigApplicationService {

    private final RatingConfigRepository ratingConfigRepository;
    private final SupportRepository supportRepository;

    /**
     * 利率加点上限（预设 5%，需客户确认）。
     * 防止误操作把加点配成离谱值（如 0.5 = 50%）。
     */
    @Value("${floorplan.rating.max-rate-surcharge:0.05}")
    private BigDecimal maxRateSurcharge;

    public RatingConfigApplicationService(RatingConfigRepository ratingConfigRepository,
                                          SupportRepository supportRepository) {
        this.ratingConfigRepository = ratingConfigRepository;
        this.supportRepository = supportRepository;
    }

    /** 查询全部口径（按展示顺序），供管理端列表页使用。 */
    @Transactional(readOnly = true)
    public List<RatingConfig> list() {
        return ratingConfigRepository.findAll();
    }

    /**
     * 新增/修改分级口径（动态配置的核心入口）。
     *
     * @param grade         等级代码（1~8 位大写字母或数字，如 A、B1）
     * @param gradeName     等级名称（展示用，可空）
     * @param rateSurcharge 年化加点（0 ≤ 值 ≤ {@code floorplan.rating.max-rate-surcharge}）
     * @param allowed       是否准入新业务
     * @param remark        备注
     * @param operator      操作人（审计留痕）
     * @return 保存后的口径
     */
    @Transactional
    public RatingConfig upsert(String grade, String gradeName, BigDecimal rateSurcharge,
                               boolean allowed, String remark, String operator) {
        // ---- 参数校验：等级代码格式 ----
        if (grade == null || !grade.matches("[A-Z0-9]{1,8}")) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "评级代码非法（须为 1~8 位大写字母或数字，如 A / B1）：" + grade);
        }
        // ---- 参数校验：加点范围（0 ~ 上限）----
        if (rateSurcharge == null || rateSurcharge.signum() < 0
                || rateSurcharge.compareTo(maxRateSurcharge) > 0) {
            throw new BusinessException(ErrorCode.VALID_002, java.util.Map.of(
                    "rateSurcharge", String.valueOf(rateSurcharge),
                    "max", String.valueOf(maxRateSurcharge)));
        }

        RatingConfig config = new RatingConfig();
        config.setGrade(grade);
        config.setGradeName(gradeName);
        config.setRateSurcharge(rateSurcharge);
        config.setAllowed(allowed);
        // 新增时缺省排到最后；更新时若不传排序则沿用旧值
        Optional<RatingConfig> old = ratingConfigRepository.findByGrade(grade);
        config.setSortOrder(old.map(RatingConfig::getSortOrder).orElse(ratingConfigRepository.findAll().size() + 1));
        config.setRemark(remark);
        config.setUpdatedBy(operator);

        RatingConfig saved = ratingConfigRepository.save(config);
        supportRepository.writeAudit(operator, "RATING_CONFIG_UPSERT", "RATING_CONFIG", grade,
                old.map(Object::toString).orElse(null), saved.toString(), null);
        return saved;
    }

    /**
     * 解析某评级的年化加点（垫款建账时调用）。
     *
     * <p>口径不存在时返回 0（不加点）——对未配置等级保持宽容，
     * 避免历史经销商数据因口径表后建而被卡住；口径存在但已停用时
     * 由调用方决定是否拦截（准入检查见 {@link #requireAllowed}）。</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveSurcharge(String grade) {
        if (grade == null || grade.isBlank()) {
            return BigDecimal.ZERO;
        }
        return ratingConfigRepository.findByGrade(grade)
                .map(RatingConfig::getRateSurcharge)
                .filter(v -> v != null && v.signum() >= 0)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * 准入检查：评级已配置且被停用时抛 {@code RATING_001}。
     * 未配置的等级不拦截（保持对存量数据的宽容）。
     */
    @Transactional(readOnly = true)
    public void requireAllowed(String grade) {
        if (grade == null || grade.isBlank()) {
            return;
        }
        ratingConfigRepository.findByGrade(grade)
                .filter(c -> !c.isAllowed())
                .ifPresent(c -> {
                    throw new BusinessException(ErrorCode.RATING_001, java.util.Map.of(
                            "grade", grade, "gradeName", String.valueOf(c.getGradeName())));
                });
    }
}
