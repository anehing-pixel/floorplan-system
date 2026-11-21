package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.RatingConfig;

import java.util.List;
import java.util.Optional;

/**
 * 授信评级分级口径仓储接口。
 *
 * <p>口径存于 {@code rating_configs} 表，支持运行时动态变更
 * （管理接口调用 {@link #save} 即完成新增/修改，无需发版）。</p>
 */
public interface RatingConfigRepository {

    /** 查询全部口径，按 sort_order、grade 排序（供管理端展示）。 */
    List<RatingConfig> findAll();

    /** 按等级代码查询口径（垫款建账时解析加点用）。 */
    Optional<RatingConfig> findByGrade(String grade);

    /** 新增或更新口径（按 grade 主键 upsert）。 */
    RatingConfig save(RatingConfig config);
}
