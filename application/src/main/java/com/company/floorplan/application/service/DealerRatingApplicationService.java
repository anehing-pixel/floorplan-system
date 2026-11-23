package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.DealerScores;
import com.company.floorplan.domain.model.UsedConfigKeys;
import com.company.floorplan.domain.repository.DealerQualificationRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车商双维评分用例（USED R1，D06 §1.2 的 {@code DealerRatingApplicationService}）。
 *
 * <p><b>与 D06 签名的偏差（已登记）</b>：文档给的是两步式
 * {@code scoreDimension(...)} ＋ {@code confirmScores(dealerId, candidateId, operatorA, operatorB)}，
 * 前提是有一张「候选分」表。库里没有这张表（{@code dealer_score_reviews} 不存在，
 * V45–V47 只落了许可/会员/合规与 suppliers 扩展），而 D07 §7.1 只给了一个端点
 * {@code POST /api/dealers/{id}/scores}。两步式在这份契约上无处安放候选 id，
 * 因此本轮实现为<b>一次调用内完成二人签认</b>：录入人 = 令牌身份，第二人 = 请求体
 * {@code co_signed_by}，两者不同即可。约束的强度没有降低（少一个条件都没有），
 * 降的只是「候选态可被驳回」这一项额外能力——那项能力要等候选表落地。</p>
 *
 * <p><b>评分不进 {@code admission_state} 推导</b>：G-A4（评级二人确认）是放款闸门上的判定，
 * 不是准入态的构成项（D03 §2.1 的规则序列只有许可→合规→会员→到期）。
 * 所以本类改完分数后<b>不</b>触发准入重算；把两件事混在一起会让「改个分数」
 * 顺带刷一次闸门结论，审计上看不出到底是什么让准入变了。</p>
 */
@Service
public class DealerRatingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DealerRatingApplicationService.class);

    /** 审计动作名。 */
    private static final String ACTION_SCORE_SET = "DEALER_SCORE_SET";

    private final DealerQualificationRepository qualificationRepository;
    private final UsedConfigRepository configRepository;
    private final SupportRepository supportRepository;

    public DealerRatingApplicationService(DealerQualificationRepository qualificationRepository,
                                         UsedConfigRepository configRepository,
                                         SupportRepository supportRepository) {
        this.qualificationRepository = qualificationRepository;
        this.configRepository = configRepository;
        this.supportRepository = supportRepository;
    }

    /**
     * 录入并二人确认双维评分（幂等键 {@code DSCR:{dealerId}:{ts}}）。
     *
     * @param dealerId          车商
     * @param operatingScore    经营资质分 0–100；{@code null} 表示本次不改这一维
     * @param stockQualityScore 在库车辆质量分 0–100；同上
     * @param scoresRunKey      批次幂等键；与库内已存值相同时直接返回现状、不重复写
     * @param coSignedBy        第二签认人，必须不同于录入人
     * @param operator          录入人（{@code AuditActor} 结果）
     */
    @Transactional
    public Map<String, Object> scoreDimension(Long dealerId, Integer operatingScore,
                                              Integer stockQualityScore, String scoresRunKey,
                                              String coSignedBy, String operator) {
        if (dealerId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "dealerId 不能为空");
        }
        qualificationRepository.findDealerId(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "车商不存在: " + dealerId));
        if (operatingScore == null && stockQualityScore == null) {
            throw new BusinessException(ErrorCode.VALID_001, "两维评分至少要给出一个，否则这次调用不改变任何事实");
        }
        checkRange("operating_score", operatingScore);
        checkRange("stock_quality_score", stockQualityScore);

        DealerScores current = qualificationRepository.findScores(dealerId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (scoresRunKey != null && !scoresRunKey.isBlank()
                && scoresRunKey.equals(current.scoresRunKey())) {
            // 同批次重推：不改写、不重复审计。返回现状让调用方确认"这一批已经落了"。
            data.put("idempotent", true);
            data.putAll(view(current));
            return data;
        }

        // 红线：二人签认不可关。D06 §16 把 reporting.dealer-rating-two-person-required 标为
        // 「true（不可配置为 false）」——本行读它的目的只有一个：
        // 如果有人真的把库里的值改成了 false，这里要在日志里留下一条可查的、说"该配置不被尊重"的记录。
        Boolean twoPerson = configRepository.findBoolean(UsedConfigKeys.DEALER_RATING_TWO_PERSON_REQUIRED)
                .orElse(Boolean.TRUE);
        if (Boolean.FALSE.equals(twoPerson)) {
            log.error("红线配置被改为 false，仍按二人签认强制执行 key={} dealerId={}",
                    UsedConfigKeys.DEALER_RATING_TWO_PERSON_REQUIRED, dealerId);
        }
        if (coSignedBy == null || coSignedBy.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_003,
                    "双维评分需第二人签认（G-A4：单人可改 = 闸门可被一人搬动）");
        }
        if (coSignedBy.equals(operator)) {
            throw new BusinessException(ErrorCode.AUTH_003, "同人自证：签认人不能是录入人自己");
        }

        qualificationRepository.updateDualScores(dealerId, operatingScore, stockQualityScore,
                scoresRunKey == null || scoresRunKey.isBlank() ? null : scoresRunKey.trim(),
                coSignedBy, operator);

        DealerScores after = qualificationRepository.findScores(dealerId);
        supportRepository.writeAudit(operator, ACTION_SCORE_SET, "DEALER_QUALIFICATION",
                String.valueOf(dealerId),
                "operating=" + current.operatingScore() + ",stock=" + current.stockQualityScore(),
                "operating=" + after.operatingScore() + ",stock=" + after.stockQualityScore()
                        + ",set_by=" + after.setBy() + ",co_signed_by=" + after.coSignedBy()
                        + ",run_key=" + after.scoresRunKey(), null);

        data.put("idempotent", false);
        data.putAll(view(after));
        return data;
    }

    /** 分数 0–100：越界值会直接进入 LTV 分档的 {@code dealer_grade_in} 判定，必须先拦在入口。 */
    private static void checkRange(String field, Integer value) {
        if (value != null && (value < 0 || value > 100)) {
            throw new BusinessException(ErrorCode.VALID_001, field + " 必须在 0–100 之间，当前 " + value);
        }
    }

    private static Map<String, Object> view(DealerScores s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dealerId", s.dealerId());
        m.put("operatingScore", s.operatingScore());
        m.put("stockQualityScore", s.stockQualityScore());
        m.put("setBy", s.setBy());
        m.put("coSignedBy", s.coSignedBy());
        m.put("confirmedAt", s.confirmedAt() == null ? null : s.confirmedAt().toString());
        m.put("scoresRunKey", s.scoresRunKey());
        m.put("twoPersonConfirmed", s.satisfiesTwoPersonRule());
        return m;
    }
}
