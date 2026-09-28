package com.github.highcumontoa.springauditcompliancejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 单条事件在某次评估批次中、由某个规则的某个版本做出的判定结果。
 *
 * <p>行内冗余 subject、eventTime、severity，使按主体/时间/结论的查询与导出
 * 无需回连事件表；自增 {@code id} 是稳定排序游标（eventTime 相同时用 id 破平），
 * 并发写入下键集分页不漏读、不重复。
 *
 * <p>{@code (run_id, event_id, rule_id)} 唯一约束使整窗重放天然幂等：同一批次重复
 * 执行不会产生重复判定行。
 */
@Entity
@Table(name = "evaluation_result",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_result_run_event_rule",
                columnNames = {"run_id", "event_id", "rule_id"}),
        indexes = {
                @Index(name = "idx_result_event", columnList = "event_id"),
                @Index(name = "idx_result_query", columnList = "subject,event_time,id"),
                @Index(name = "idx_result_decision", columnList = "decision,event_time,id")
        })
public class EvaluationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "rule_id", nullable = false, updatable = false, length = 100)
    private String ruleId;

    /** 判定时实际使用的规则版本，追溯依据。 */
    @Column(name = "rule_version", nullable = false, updatable = false)
    private int ruleVersion;

    @Column(name = "rule_name", nullable = false, updatable = false, length = 200)
    private String ruleName;

    @Column(name = "subject", nullable = false, updatable = false, length = 200)
    private String subject;

    @Column(name = "event_time", nullable = false, updatable = false)
    private java.time.Instant eventTime;

    @Column(name = "severity", nullable = false, updatable = false, length = 20)
    private String severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 20)
    private Decision decision;

    /** 判定依据（人类可读），例如各匹配维度是否命中、严重级别比较结果。 */
    @Column(name = "rationale", nullable = false, updatable = false, length = 1000)
    private String rationale;

    protected EvaluationResult() {
    }

    public EvaluationResult(String runId, String eventId, String ruleId, int ruleVersion,
                            String ruleName, String subject, java.time.Instant eventTime,
                            String severity, Decision decision, String rationale) {
        this.runId = runId;
        this.eventId = eventId;
        this.ruleId = ruleId;
        this.ruleVersion = ruleVersion;
        this.ruleName = ruleName;
        this.subject = subject;
        this.eventTime = eventTime;
        this.severity = severity;
        this.decision = decision;
        this.rationale = rationale;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public int getRuleVersion() {
        return ruleVersion;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getSubject() {
        return subject;
    }

    public java.time.Instant getEventTime() {
        return eventTime;
    }

    public String getSeverity() {
        return severity;
    }

    public Decision getDecision() {
        return decision;
    }

    public String getRationale() {
        return rationale;
    }
}
