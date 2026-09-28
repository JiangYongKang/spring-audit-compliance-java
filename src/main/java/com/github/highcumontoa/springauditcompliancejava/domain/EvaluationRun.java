package com.github.highcumontoa.springauditcompliancejava.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 一次合规评估批次（在线评估或历史重放）。
 *
 * <p>批次创建时在同一事务内冻结时间窗口内事件集合与每个规则的具体版本
 * （{@code rule_snapshot_json}），并把版本号冗余到每条 {@link EvaluationResult}，
 * 从而保证：
 * <ul>
 *   <li>同一窗口用同一批规则版本重放，结果逐位可复现；</li>
 *   <li>每条判定都能回答"由哪个规则的哪个版本"做出；</li>
 *   <li>并发更新规则时，评估持有的是不可变版本行快照，不会读到半更新状态。</li>
 * </ul>
 */
@Entity
@Table(name = "evaluation_run",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_run_run_id", columnNames = "run_id"))
public class EvaluationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批次业务标识。 */
    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    /** 评估模式：LIVE（接入后实时评估）或 REPLAY（历史窗口重放）。 */
    @Column(name = "mode", nullable = false, updatable = false, length = 16)
    private String mode;

    /** 时间窗口起（含，按事件 eventTime）。 */
    @Column(name = "window_start", updatable = false)
    private Instant windowStart;

    /** 时间窗口止（不含，按事件 eventTime）。 */
    @Column(name = "window_end", updatable = false)
    private Instant windowEnd;

    /**
     * 规则版本快照 JSON：形如 {"ruleA":2,"ruleB":1}，
     * 即本批次每个 ruleId 使用的确切版本。
     */
    @Lob
    @Column(name = "rule_snapshot_json", updatable = false)
    private String ruleSnapshotJson;

    @Column(name = "rule_count", nullable = false, updatable = false)
    private int ruleCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RunStatus status;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "non_compliant_count", nullable = false)
    private int nonCompliantCount;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "message", length = 1000)
    private String message;

    protected EvaluationRun() {
    }

    public EvaluationRun(String runId, String mode, Instant windowStart, Instant windowEnd,
                         String ruleSnapshotJson, int ruleCount, RunStatus status,
                         Instant startedAt) {
        this.runId = runId;
        this.mode = mode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.ruleSnapshotJson = ruleSnapshotJson;
        this.ruleCount = ruleCount;
        this.status = status;
        this.startedAt = startedAt;
        this.eventCount = 0;
        this.nonCompliantCount = 0;
    }

    public void complete(int eventCount, int nonCompliantCount, Instant completedAt) {
        this.status = RunStatus.COMPLETED;
        this.eventCount = eventCount;
        this.nonCompliantCount = nonCompliantCount;
        this.completedAt = completedAt;
    }

    public void fail(String message, Instant completedAt) {
        this.status = RunStatus.FAILED;
        this.message = message;
        this.completedAt = completedAt;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getMode() {
        return mode;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public String getRuleSnapshotJson() {
        return ruleSnapshotJson;
    }

    public int getRuleCount() {
        return ruleCount;
    }

    public RunStatus getStatus() {
        return status;
    }

    public int getEventCount() {
        return eventCount;
    }

    public int getNonCompliantCount() {
        return nonCompliantCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getMessage() {
        return message;
    }
}
