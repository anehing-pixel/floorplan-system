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

    /**
     * B-2：统计该额度线下「除本单之外」仍未结案的处置单数。
     * 用于配对解冻时判断本单是否最后一个未结案单——只有最后一个才允许额度线恢复 ACTIVE，
     * 避免多单叠加冻结时提前解冻导致违约经销商恢复放款能力。
     */
    long countOtherOpenByCreditLine(Long creditLineId, String excludeCaseNo);
}
