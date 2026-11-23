package com.company.floorplan.domain.model;

import java.time.OffsetDateTime;

/**
 * 車検証保管/核查流水（{@code certificate_custody_events}，V52，append-only）。
 *
 * <p>这是「谁在什么时候对这张证做了什么」的账，也是 SOD 与审计的证据面
 * （V52 表注释：与 {@code audit_events} 分表，因为审计记操作、本表记<b>状态历史与核查结论</b>，
 * 同一动作可以写多条迁移）。</p>
 *
 * <p><b>{@code actor} 的二义性是显式建模的</b>：{@code actor_type=USER} 时 {@code actor} 是
 * 令牌里的自然人（{@code AuditActor}，<b>不许从请求体自报</b>，AGENTS.md §10 时间/操作人同款纪律）；
 * {@code actor_type=PARTNER} 时 {@code actor} 是 {@code partner_code}（R12.2，外部方回执场景）。
 * 库内 {@code ck_cce_actor_type} 只允许这两个值。</p>
 *
 * @param id              主键
 * @param certificateId   所属证
 * @param eventType       九值之一
 * @param fromStatus      迁移前状态（不改状态的事件可为 null）
 * @param toStatus        迁移后状态（同上）
 * @param encumbranceType 本条发现的瑕疵类型
 * @param checkSource     登记核查来源：MANUAL / REGISTRY_AGENT / REGISTRY_API
 *                        （USED 域<b>禁用</b> LEGACY 的 OEM_PORTAL，R5.3：厂家门户查不到二手车权属）
 * @param actorType       USER / PARTNER
 * @param actor           见类注释
 * @param occurredAt      发生时间
 * @param evidencePath    证据文件
 * @param reason          事由（灭失/换发的说明）
 * @param traceId         链路号，与 {@code audit_events.trace_id} 对齐（同一动作两处可勾稽）
 */
public record CertificateCustodyEvent(
        Long id,
        Long certificateId,
        CertificateCustodyEventType eventType,
        CertificateStatus fromStatus,
        CertificateStatus toStatus,
        EncumbranceType encumbranceType,
        String checkSource,
        String actorType,
        String actor,
        OffsetDateTime occurredAt,
        String evidencePath,
        String reason,
        String traceId) {

    /** 登记类事件（状态迁移）才有 from/to，其余只留事实。 */
    public boolean isStatusTransition() {
        return eventType != null && eventType.changesStatus();
    }
}
