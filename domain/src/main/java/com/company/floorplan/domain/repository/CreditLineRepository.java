package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.CreditLine;

import java.util.List;
import java.util.Optional;

/**
 * 额度仓储接口（定义在领域层，实现在基础设施层）。
 *
 * <p><b>核心能力是行锁</b>：{@link #findByIdForUpdate(Long)} 用于
 * 占用 / 释放 / 冻结额度。额度是本系统并发风险最高的资源，
 * 必须通过行锁把并发请求串行化，否则会出现"额度超额"——
 * 这在金融账务系统里是不可接受的错误。</p>
 */
public interface CreditLineRepository {

    /**
     * 按 ID 查询（不加锁，用于只读展示）。
     *
     * @param id 额度 ID
     * @return 额度
     */
    Optional<CreditLine> findById(Long id);

    /**
     * 以行锁（SELECT ... FOR UPDATE）读取额度，用于占用/释放/冻结。
     * 禁止无锁的读-改-写。
     */
    Optional<CreditLine> findByIdForUpdate(Long id);

    List<CreditLine> findByDealerId(Long dealerId);

    /**
     * 统计某经销商名下的额度条数。
     *
     * <p>用于删除前的护栏检查：有额度的经销商只能"终止合作"，不能物理删除。</p>
     */
    int countByDealerId(Long dealerId);

    /** 列出所有可用（ACTIVE）额度——供年审扫描等批处理枚举。 */
    List<CreditLine> findActive();

    CreditLine save(CreditLine creditLine);
}
