package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.DisposalCase;
import com.company.floorplan.domain.model.DisposalStatus;

import java.util.List;
import java.util.Optional;

/**
 * 违约处置工单仓储接口（领域层声明，基础设施层用 JdbcTemplate 实现）。
 */
public interface DisposalCaseRepository {

    /** 保存（新增或更新，按 case_no 幂等）。 */
    DisposalCase save(DisposalCase disposalCase);

    /** 按单号查询。 */
    Optional<DisposalCase> findByCaseNo(String caseNo);

    /** 按车架号查询最新工单。 */
    Optional<DisposalCase> findLatestByVin(String vin);

    /** 按状态列出（作业扫描用）。 */
    List<DisposalCase> findByStatus(DisposalStatus status);
}
