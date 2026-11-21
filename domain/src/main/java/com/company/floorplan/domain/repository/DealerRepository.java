package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.Dealer;

import java.util.List;
import java.util.Optional;

/**
 * 经销商仓储接口。
 *
 * <p>提供按 ID、按经销商编号（业务唯一键）的查询，
 * 后者用于创建时查重以及外部系统对接时的定位。</p>
 */
public interface DealerRepository {

    Optional<Dealer> findById(Long id);

    Optional<Dealer> findByCode(String dealerCode);

    List<Dealer> findAll();

    Dealer save(Dealer dealer);

    /**
     * 物理删除经销商。
     *
     * <p><b>调用方责任</b>：删除前必须确认该经销商名下没有任何车辆与授信额度——
     * 这不是技术限制，而是业务约束：经销商是授信台账的主体，
     * 主体一旦消失，历史台账就失去了归属对象，审计链会断。
     * 对已有业务的经销商，正确做法是置为"终止合作"状态（{@link com.company.floorplan.domain.model.DealerStatus#TERMINATED}），
     * 而不是抹掉记录。</p>
     *
     * @param id 经销商 ID
     * @return 实际删除的行数
     */
    int deleteById(Long id);
}
