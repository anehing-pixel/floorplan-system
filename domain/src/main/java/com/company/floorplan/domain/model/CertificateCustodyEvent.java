package com.company.floorplan.domain.model;

import java.time.Instant;

/**
 * 証書 custody 事件实体（R2.5 / R5.3；表 {@code certificate_custody_events}，V52 / D04 §3.10）。
 *
 * <p><b>append-only 台账</b>：每次收证、设质、换证、核查都插一行，
 * 不改历史行；証書当前状态在 {@code inspection_certificates.status}，
 * 本表回答「它是怎么走到那里的」。这也是 D03 §3.2 里
 * {@code AMENDED_IN_PROGRESS} 不必成为第七个状态值的原因——
 * 在途这件事本来就是事件，不是状态。</p>
 *
 * <p><b>{@code fromStatus} / {@code toStatus} 允许为空</b>：
 * {@code ENCUMBRANCE_FOUND}、{@code CHECK_DONE} 这类核查事件不改状态（D04 §3.10 的语义），
 * 两列同空正是"本事件不迁移状态"的表达；反过来成对出现时必须与
 * {@code CertificateStateMachine#canTransit} 一致，该一致性由写入方（应用层）保证。</p>
 *
 * <p>{@code actorType}/{@code actor} 用于区分令牌自然人 {@code USER} 与外部 {@code PARTNER}（R12.2）。
 * {@code traceId} 与响应链路对齐，便于把一次 HTTP 请求与它留下的事件串起来。</p>
 */
public class CertificateCustodyEvent {

    /** 主键。 */
    private Long id;
    /** 所属証書（FK → {@code inspection_certificates.id}）。 */
    private Long certificateId;
    /** 事件类型（九值，见 {@link CustodyEventType}）。 */
    private CustodyEventType eventType;
    /** 迁移前状态（不改状态的事件为空）。 */
    private CertificateStatus fromStatus;
    /** 迁移后状态（不改状态的事件为空）。 */
    private CertificateStatus toStatus;
    /** 本次核查发现的权利瑕疵类型（无则空）。 */
    private EncumbranceType encumbranceType;
    /** 核查实施路径（{@code MANUAL}／代办／API）。 */
    private RegistryCheckSource checkSource;
    /** 主体类型。 */
    private CustodyActorType actorType;
    /** 主体标识：自然人 or {@code partner_code}。 */
    private String actor;
    /** 发生时间。 */
    private Instant occurredAt;
    /** 証拠路径。 */
    private String evidencePath;
    /** 事由（换证/灭失等必填说明，V52 列注释要求人工审批留痕）。 */
    private String reason;
    /** 链路追踪 ID。 */
    private String traceId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCertificateId() { return certificateId; }
    public void setCertificateId(Long certificateId) { this.certificateId = certificateId; }

    public CustodyEventType getEventType() { return eventType; }
    public void setEventType(CustodyEventType eventType) { this.eventType = eventType; }

    public CertificateStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(CertificateStatus fromStatus) { this.fromStatus = fromStatus; }

    public CertificateStatus getToStatus() { return toStatus; }
    public void setToStatus(CertificateStatus toStatus) { this.toStatus = toStatus; }

    public EncumbranceType getEncumbranceType() { return encumbranceType; }
    public void setEncumbranceType(EncumbranceType encumbranceType) { this.encumbranceType = encumbranceType; }

    public RegistryCheckSource getCheckSource() { return checkSource; }
    public void setCheckSource(RegistryCheckSource checkSource) { this.checkSource = checkSource; }

    public CustodyActorType getActorType() { return actorType; }
    public void setActorType(CustodyActorType actorType) { this.actorType = actorType; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getEvidencePath() { return evidencePath; }
    public void setEvidencePath(String evidencePath) { this.evidencePath = evidencePath; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
