package com.github.highcumontoa.springauditcompliancejava.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 合规规则的一个不可变版本。
 *
 * <p>规则更新不是原地修改，而是插入一条新版本行：{@code (rule_id, version)} 唯一。
 * 任何评估批次都在自身事务中读取该规则当时的唯一生效版本并把版本号写入每条
 * 判定结果，因此：
 * <ul>
 *   <li>并发更新与评估时，评估只会看到完整的旧版本或完整的新版本，不会读到半更新状态；</li>
 *   <li>按时间窗口重放时，可指定使用当时的规则版本，判定可复现、可追溯。</li>
 * </ul>
 *
 * <p>判定语义：当事件的 subject / action / resource 与对应模式（相等，{@code *} 表示匹配任意）
 * 全部匹配，且严重级别 {@code >= minSeverity} 时，判定为 {@link Decision#NON_COMPLIANT}。
 */
@Entity
@Table(name = "compliance_rule",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rule_id_version", columnNames = {"rule_id", "version"}),
        indexes = {
                @Index(name = "idx_rule_rule_id", columnList = "rule_id"),
                @Index(name = "idx_rule_active", columnList = "active")
        })
public class ComplianceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则的稳定业务标识（跨版本相同）。 */
    @Column(name = "rule_id", nullable = false, updatable = false, length = 100)
    private String ruleId;

    /** 版本号，同一 ruleId 下从 1 开始单调递增。 */
    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    /** 规则名称。 */
    @Column(name = "rule_name", nullable = false, updatable = false, length = 200)
    private String name;

    @Column(name = "description", updatable = false, length = 1000)
    private String description;

    /** 主体匹配模式：精确值或 *（任意）。 */
    @Column(name = "match_subject", nullable = false, updatable = false, length = 200)
    private String matchSubject;

    /** 动作匹配模式：精确值或 *（任意）。 */
    @Column(name = "match_action", nullable = false, updatable = false, length = 100)
    private String matchAction;

    /** 资源匹配模式：精确值或 *（任意）。 */
    @Column(name = "match_resource", nullable = false, updatable = false, length = 500)
    private String matchResource;

    /** 触发不合规的最低严重级别（含）。 */
    @Column(name = "min_severity", nullable = false, updatable = false, length = 20)
    private String minSeverity;

    /**
     * 该版本是否为当前生效版本。并发更新时先在事务中锁住规则的版本行集合再翻转，
     * 任意时刻同一 ruleId 至多一个 active=true；历史版本 active=false，仍保留用于重放。
     */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ComplianceRule() {
    }

    public ComplianceRule(String ruleId, Integer version, String name, String description,
                          String matchSubject, String matchAction, String matchResource,
                          String minSeverity, boolean active, Instant createdAt) {
        this.ruleId = ruleId;
        this.version = version;
        this.name = name;
        this.description = description;
        this.matchSubject = matchSubject;
        this.matchAction = matchAction;
        this.matchResource = matchResource;
        this.minSeverity = minSeverity;
        this.active = active;
        this.createdAt = createdAt;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public Integer getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getMatchSubject() {
        return matchSubject;
    }

    public String getMatchAction() {
        return matchAction;
    }

    public String getMatchResource() {
        return matchResource;
    }

    public String getMinSeverity() {
        return minSeverity;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
