package com.github.highcumontoa.audit.service;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.ComplianceRule;
import com.github.highcumontoa.audit.model.EvaluationDecision;
import com.github.highcumontoa.audit.model.EvaluationRecord;
import com.github.highcumontoa.audit.model.Severity;
import com.github.highcumontoa.audit.store.AuditEventStore;
import com.github.highcumontoa.audit.store.EvaluationRecordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 合规评估与历史重放。
 *
 * 一致性：每次评估（含整批重放）开始时取一次规则快照，
 * 重放期间规则被并发更新也不会读到半更新状态，整批结果使用同一组规则版本，可复现。
 *
 * 可追溯：每条判定记录保留 ruleId/ruleVersion 与判定依据 basis。
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** 首次接入评估的批次号；历史重放批次号从 1 开始单调递增 */
    public static final long INITIAL_RUN_ID = 0L;

    private final ComplianceRuleService ruleService;
    private final AuditEventStore eventStore;
    private final EvaluationRecordStore recordStore;
    private final Clock clock;
    private final AtomicLong nextReplayRunId = new AtomicLong(1L);

    public EvaluationService(ComplianceRuleService ruleService,
                             AuditEventStore eventStore,
                             EvaluationRecordStore recordStore,
                             Clock clock) {
        this.ruleService = ruleService;
        this.eventStore = eventStore;
        this.recordStore = recordStore;
        this.clock = clock;
    }

    /** 对单个事件按当前规则快照做首次评估（批次号 0） */
    public EvaluationRecord evaluate(AuditEvent event) {
        List<ComplianceRule> snapshot = ruleService.snapshot();
        EvaluationRecord record = doEvaluate(event, snapshot, INITIAL_RUN_ID);
        recordStore.append(record);
        return record;
    }

    /**
     * 历史重放：用当前规则版本重新评估 [from, to] 窗口内的全部事件。
     *
     * @return 本次重放批次号；同一规则版本+同一事件集，重放结果确定可复现
     */
    public long replay(Instant from, Instant to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("invalid replay window: [" + from + ", " + to + "]");
        }
        long runId = nextReplayRunId.getAndIncrement();
        // 整批重放共用一个规则快照：并发更新规则不会影响本批次
        List<ComplianceRule> snapshot = ruleService.snapshot();
        List<AuditEvent> window = eventStore.findInWindow(from, to);
        log.info("replay runId={} window=[{},{}] events={} rules={} ruleVersions={}",
                runId, from, to, window.size(), snapshot.size(), summarizeVersions(snapshot));
        for (AuditEvent event : window) {
            recordStore.append(doEvaluate(event, snapshot, runId));
        }
        return runId;
    }

    public List<EvaluationRecord> recordsOf(String eventId) {
        return recordStore.findByEventId(eventId);
    }

    private EvaluationRecord doEvaluate(AuditEvent event, List<ComplianceRule> snapshot, long runId) {
        for (ComplianceRule rule : snapshot) { // 快照按 ruleId 有序，首命中确定
            String reason = mismatchReason(rule, event);
            if (reason == null) {
                String basis = "matched rule " + rule.ruleId()
                        + " v" + rule.version()
                        + " [subjectPattern=" + rule.subjectPattern()
                        + ", action=" + rule.action()
                        + ", minSeverity=" + rule.minSeverity()
                        + ", keyword=" + rule.keyword() + "]";
                log.info("evaluate input={} ruleId={} ruleVersion={} decision={} basis={}",
                        event, rule.ruleId(), rule.version(), EvaluationDecision.VIOLATION, basis);
                return new EvaluationRecord(event.eventId(), rule.ruleId(), rule.version(),
                        EvaluationDecision.VIOLATION, basis, clock.instant(), runId);
            }
        }
        String basis = "no rule matched among " + snapshot.size() + " rules; versions=" + summarizeVersions(snapshot);
        log.info("evaluate input={} ruleId=<none> ruleVersion=0 decision={} basis={}",
                event, EvaluationDecision.PASS, basis);
        return new EvaluationRecord(event.eventId(), null, 0L,
                EvaluationDecision.PASS, basis, clock.instant(), runId);
    }

    /** @return 不匹配原因；null 表示匹配 */
    private String mismatchReason(ComplianceRule rule, AuditEvent event) {
        if (rule.subjectPattern() != null && !rule.subjectPattern().isBlank()
                && (event.subject() == null || !event.subject().contains(rule.subjectPattern()))) {
            return "subject does not contain " + rule.subjectPattern();
        }
        if (rule.action() != null && !rule.action().isBlank()
                && !rule.action().equals(event.action())) {
            return "action != " + rule.action();
        }
        if (rule.minSeverity() != null) {
            Severity actual;
            try {
                actual = Severity.valueOf(event.severity());
            } catch (IllegalArgumentException ex) {
                return "invalid severity";
            }
            if (actual.ordinal() < rule.minSeverity().ordinal()) {
                return "severity " + actual + " below min " + rule.minSeverity();
            }
        }
        if (rule.keyword() != null && !rule.keyword().isBlank()) {
            String kw = rule.keyword().toLowerCase();
            Map<String, String> attrs = event.attributes();
            boolean found = attrs != null && attrs.values().stream()
                    .anyMatch(v -> v != null && v.toLowerCase().contains(kw));
            if (!found) {
                return "keyword not found in attributes: " + rule.keyword();
            }
        }
        return null;
    }

    private static String summarizeVersions(List<ComplianceRule> snapshot) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ComplianceRule r = snapshot.get(i);
            sb.append(r.ruleId()).append('=').append(r.version());
        }
        return sb.append('}').toString();
    }
}
