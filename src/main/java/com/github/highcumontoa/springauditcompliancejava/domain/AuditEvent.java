package com.github.highcumontoa.springauditcompliancejava.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 审计事件（接入校验后本地持久化）。
 *
 * <p>{@code eventId} 为调用方提供的幂等键：重复提交同一事件只会有一条记录。
 * 自增 {@code id} 单调递增，与 {@code eventTime} 一起构成稳定的键集分页游标，
 * 保证并发写入下分页不漏读、不重复。
 */
@Entity
@Table(name = "audit_event",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_audit_event_event_id", columnNames = "event_id"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 调用方提供的事件唯一标识（幂等键）。 */
    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    /** 事件主体（如操作者/服务账号）。 */
    @Column(name = "subject", nullable = false, updatable = false, length = 200)
    private String subject;

    /** 操作动作。 */
    @Column(name = "action", nullable = false, updatable = false, length = 100)
    private String action;

    /** 被操作资源，可空。 */
    @Column(name = "resource", updatable = false, length = 500)
    private String resource;

    /** 严重级别，仅允许 Severity 枚举中的合法值。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 20)
    private Severity severity;

    /** 事件发生时间（调用方提供，校验时间异常后落库）。 */
    @Column(name = "event_time", nullable = false, updatable = false)
    private Instant eventTime;

    /** 服务端接收时间，用于保留期与审计。 */
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /** 事件明细，原始 JSON 字符串，可空。 */
    @Column(name = "detail_json", updatable = false, length = 4000)
    private String detailJson;

    protected AuditEvent() {
    }

    public AuditEvent(String eventId, String subject, String action, String resource,
                      Severity severity, Instant eventTime, Instant receivedAt,
                      String detailJson) {
        this.eventId = eventId;
        this.subject = subject;
        this.action = action;
        this.resource = resource;
        this.severity = severity;
        this.eventTime = eventTime;
        this.receivedAt = receivedAt;
        this.detailJson = detailJson;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getSubject() {
        return subject;
    }

    public String getAction() {
        return action;
    }

    public String getResource() {
        return resource;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getDetailJson() {
        return detailJson;
    }
}
