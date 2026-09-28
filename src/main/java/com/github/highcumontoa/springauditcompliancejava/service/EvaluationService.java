package com.github.highcumontoa.springauditcompliancejava.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;
import com.github.highcumontoa.springauditcompliancejava.domain.ComplianceRule;
import com.github.highcumontoa.springauditcompliancejava.domain.Decision;
import com.github.highcumontoa.springauditcompliancejava.domain.EvaluationResult;
import com.github.highcumontoa.springauditcompliancejava.domain.EvaluationRun;
import com.github.highcumontoa.springauditcompliancejava.domain.RunStatus;
import com.github.highcumontoa.springauditcompliancejava.dto.ReplayRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RunView;
import com.github.highcumontoa.springauditcompliancejava.error.ApiException;
import com.github.highcumontoa.springauditcompliancejava.error.ErrorCode;
import com.github.highcumontoa.springauditcompliancejava.repo.AuditEventRepository;
import com.github.highcumontoa.springauditcompliancejava.repo.ComplianceRuleRepository;
import com.github.highcumontoa.springauditcompliancejava.repo.EvaluationResultRepository;
import com.github.highcumontoa.springauditcompliancejava.repo.EvaluationRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final AuditEventRepository events;
    private final ComplianceRuleRepository rules;
    private final EvaluationRunRepository runs;
    private final EvaluationResultRepository results;
    private final ObjectMapper objectMapper;

    public EvaluationService(AuditEventRepository events, ComplianceRuleRepository rules,
                             EvaluationRunRepository runs,
                             EvaluationResultRepository results, ObjectMapper objectMapper) {
        this.events = events;
        this.rules = rules;
        this.runs = runs;
        this.results = results;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RunView getRun(String runId) {
        return runs.findByRunId(runId).map(RunView::of)
                .orElseThrow(() -> new ApiException(ErrorCode.RUN_NOT_FOUND,
                        "评估批次不存在: " + runId));
    }

    /**
     * 对指定时间窗口执行（重）评估。
     *
     * <p>整批在单个事务内完成：规则版本快照与结果写入要么全部可见、要么全部不可见，
     * 外部永远观察不到半更新批次。ruleVersions 非空时按指定历史版本评估（可复现重放），
     * 为空时使用当前生效版本。
     */
    public RunView evaluateWindow(ReplayRequest req) {
        try {
            return doEvaluate(req, "REPLAY");
        } catch (org.springframework.dao.DataIntegrityViolationException dupRun) {
            // 并发同 runId 提交：内部事务已回滚，回查既有批次，幂等返回。
            String runId = (req.runId() == null || req.runId().isBlank())
                    ? null : req.runId().trim();
            if (runId == null) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "并发批次冲突");
            }
            return getRun(runId);
        }
    }

    /**
     * 接入路径的实时评估（预留）。
     */
    public RunView evaluateLive(ReplayRequest req) {
        return doEvaluate(req, "LIVE");
    }

    private Instant parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD,
                    "缺少关键字段: " + field);
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_BAD_TIME_RANGE,
                    "时间字段格式非法（需 ISO-8601）: " + field + "=" + raw);
        }
    }

    // 规则版本选择与逐事件判定。
    @Transactional
    public RunView doEvaluate(ReplayRequest req, String mode) {
        Instant start = parseTime(req.windowStart(), "windowStart");
        Instant end = parseTime(req.windowEnd(), "windowEnd");
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCode.VALIDATION_BAD_TIME_RANGE,
                    "windowStart 必须早于 windowEnd");
        }

        // 幂等批次：调用方可通过 runId 保证重复提交只执行一次。
        String runId = (req.runId() == null || req.runId().isBlank())
                ? "run-" + UUID.randomUUID()
                : req.runId().trim();
        var existing = runs.findByRunId(runId);
        if (existing.isPresent()) {
            log.info("评估批次幂等命中: runId={}, status={}", runId, existing.get().getStatus());
            return RunView.of(existing.get());
        }

        Instant now = Instant.now();
        try {
            // 1) 在本事务内冻结规则版本集合（不可变版本行；并发更新者在写新行，不影响读取）
            Map<String, ComplianceRule> chosen = resolveRules(req.ruleVersions());
            String snapshot = objectMapper.writeValueAsString(chosen.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().getVersion(),
                            (a, b) -> a,
                            java.util.TreeMap::new)));

            EvaluationRun run = new EvaluationRun(runId, mode, start, end, snapshot,
                    chosen.size(), RunStatus.RUNNING, now);

            // 2) 冻结窗口内事件（(eventTime,id) 全序，处理顺序确定 → 结果可复现）
            List<AuditEvent> windowEvents = events.findWindow(start, end);

            // 3) 逐事件 × 逐规则判定；事件按 (eventTime,id)、规则按 ruleId 排序，
            //    保存顺序确定。结论与依据都是纯函数 RuleEngine 的产物。
            int nonCompliant = 0;
            int pairCount = 0;
            List<RuleVersionEntry> ordered = chosen.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new RuleVersionEntry(e.getKey(), e.getValue()))
                    .toList();
            for (AuditEvent ev : windowEvents) {
                for (RuleVersionEntry entry : ordered) {
                    RuleEngine.Evaluation judged = RuleEngine.evaluate(entry.rule(), ev);
                    if (judged.decision() == Decision.NON_COMPLIANT) {
                        nonCompliant++;
                    }
                    results.save(new EvaluationResult(
                            runId, ev.getEventId(), entry.ruleId(),
                            entry.rule().getVersion(), entry.rule().getName(),
                            ev.getSubject(), ev.getEventTime(), ev.getSeverity().name(),
                            judged.decision(), judged.rationale()));
                    pairCount++;
                }
            }
            results.flush();

            run.complete(windowEvents.size(), nonCompliant, Instant.now());
            runs.save(run);
            runs.flush();

            log.info("评估完成: runId={}, mode={}, window=[{},{}), rules={}, events={}, pairs={}, nonCompliant={}, snapshot={}",
                    runId, mode, start, end, chosen.size(), windowEvents.size(),
                    pairCount, nonCompliant, snapshot);
            return RunView.of(run);
        } catch (ApiException ex) {
            throw ex;
        } catch (org.springframework.dao.DataIntegrityViolationException dupRun) {
            // 并发同 runId：唯一约束兜底，回查既有批次幂等返回。
            EvaluationRun raced = runs.findByRunId(runId).orElseThrow(
                    () -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "并发批次冲突且无法回查: " + runId));
            log.info("评估批次并发幂等命中: runId={}", runId);
            return RunView.of(raced);
        } catch (Exception ex) {
            log.error("评估失败: runId={}", runId, ex);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "评估失败: " + ex.getMessage());
        }
    }

    /**
     * 选择本批次使用的规则版本。
     * 指定版本映射时读取该历史版本（缺失即拒绝）；否则读取全部当前生效版本。
     */
    private Map<String, ComplianceRule> resolveRules(Map<String, Integer> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return rules.findByActiveTrueOrderByRuleIdAsc().stream()
                    .collect(Collectors.toMap(ComplianceRule::getRuleId, r -> r,
                            (a, b) -> a, java.util.TreeMap::new));
        }
        Map<String, ComplianceRule> chosen = new java.util.TreeMap<>();
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            ComplianceRule version = rules.findVersion(e.getKey(), e.getValue())
                    .orElseThrow(() -> new ApiException(ErrorCode.RULE_NOT_FOUND,
                            "规则或版本不存在: " + e.getKey() + " v" + e.getValue()));
            chosen.put(e.getKey(), version);
        }
        return chosen;
    }

    private record RuleVersionEntry(String ruleId, ComplianceRule rule) {
    }
}
