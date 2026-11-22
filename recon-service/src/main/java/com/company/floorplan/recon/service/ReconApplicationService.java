package com.company.floorplan.recon.service;

import com.company.floorplan.recon.model.Comparison;
import com.company.floorplan.recon.model.ReconDiff;
import com.company.floorplan.recon.model.ReconRun;
import com.company.floorplan.recon.repository.JdbcReconRepository;
import com.company.floorplan.recon.rule.ReconRule;
import com.company.floorplan.recon.rule.ReconRuleCode;
import com.company.floorplan.recon.rule.ReconRuleSet;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对拍批次编排：开跑 → 逐规则对拍 → 差异落库 → 收尾。
 *
 * <p><b>事务边界刻意不放在整个批次上</b>：一次对拍要跑 8 条规则、扫全库账本，
 * 包在一个事务里意味着几分钟的长事务占着连接池，且任何一条规则的偶发失败
 * 都会把前面所有差异回滚——而那些差异是真实发现的事实，回滚掉等于白跑。
 * 这里采用与 BANK_FETCH 相同的口径：<b>批次级状态机保证可观测，
 * 规则级判定各自独立，差异落库是一次批量写</b>。</p>
 *
 * <p><b>失败也留痕</b>：异常路径把 RUNNING 改成 FAILED 并记下已执行规则数，
 * 值班同学能立刻知道"跑到第几条炸的"，而不是面对一条永远 RUNNING 的僵尸批次。</p>
 *
 * <p><b>只读账本</b>：本服务不持有任何账务仓储，账本访问全部经由
 * {@link JdbcReconRepository#loadComparisons(String)} 的 SELECT。</p>
 */
@Service
public class ReconApplicationService {

    private static final DateTimeFormatter DATE_TAG = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcReconRepository repository;
    private final ReconRuleSet ruleSet;

    public ReconApplicationService(JdbcReconRepository repository, ReconRuleSet ruleSet) {
        this.repository = repository;
        this.ruleSet = ruleSet;
    }

    /**
     * 触发一次对拍。
     *
     * @param bizDate 对拍业务日；为空取今天
     * @param actor   触发人（仅用于扩展留痕，当前不写审计表——见 HANDOFF TODO）
     * @return 批次号、状态、执行规则数、差异条数
     */
    public Map<String, Object> run(LocalDate bizDate, String actor) {
        LocalDate date = bizDate == null ? LocalDate.now() : bizDate;
        String batchNo = "RC-" + date.format(DATE_TAG) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime startedAt = LocalDateTime.now();

        ReconRun run = ReconRun.start(batchNo, date, startedAt);
        // 先落 RUNNING：进程在判定途中被杀，库里仍有"跑过但没跑完"的证据
        repository.insertRun(run);

        List<ReconDiff> diffs = new ArrayList<>();
        int executed = 0;
        try {
            for (ReconRule rule : ruleSet.rules()) {
                // 8 条规则全部已实现（见《业务口径总定源》ADR）。此处不再有
                // "implemented=false 就 continue" 的短路：那条分支曾让 R5-R8 只列名不取数，
                // 报告的 ruleCount=4 却被读成"8 条都跑了"。现在每条都真实取数、真实判定。
                ReconRuleCode.valueOf(rule.code()); // 校验编号仍在目录中，新增规则漏配即快速失败

                // 取数与判定分离：仓储读事实，规则做纯函数判定
                List<Comparison> facts = repository.loadComparisons(rule.code());
                List<ReconDiff> found = rule.evaluate(facts);
                LocalDateTime detectedAt = LocalDateTime.now();
                for (ReconDiff d : found) {
                    d.setBatchNo(batchNo);
                    d.setDetectedAt(detectedAt);
                }
                diffs.addAll(found);
                executed++;
            }

            int inserted = repository.insertDiffs(diffs);
            run.succeed(executed, inserted, LocalDateTime.now());
            repository.updateRun(run);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchNo", batchNo);
            result.put("bizDate", date.toString());
            result.put("status", run.getStatus());
            result.put("ruleCount", executed);
            result.put("diffCount", inserted);
            result.put("startedAt", startedAt.toString());
            result.put("finishedAt", run.getFinishedAt().toString());
            return result;
        } catch (RuntimeException e) {
            run.fail(executed, diffs.size(), LocalDateTime.now(), e.getMessage());
            try {
                repository.updateRun(run);
            } catch (RuntimeException ignored) {
                // 数据库也不可用时，原始异常更重要，保留它往上抛
            }
            throw e;
        }
    }

    /** 查询差异明细；batchNo / status 均为可选过滤条件。 */
    public List<ReconDiff> findDiffs(String batchNo, String status) {
        return repository.findDiffs(batchNo, status);
    }
}
