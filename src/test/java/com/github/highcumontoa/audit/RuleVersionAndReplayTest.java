package com.github.highcumontoa.audit;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.ComplianceRule;
import com.github.highcumontoa.audit.model.EvaluationDecision;
import com.github.highcumontoa.audit.model.EvaluationRecord;
import com.github.highcumontoa.audit.model.Severity;
import com.github.highcumontoa.audit.service.ComplianceRuleService;
import com.github.highcumontoa.audit.service.EvaluationService;
import com.github.highcumontoa.audit.store.EvaluationRecordStore;
import com.github.highcumontoa.audit.store.InMemoryAuditEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：规则新增/更新产生单调版本；规则变化后历史重放结果可复现；
 * 每次判定记录可看出使用的规则版本与判定依据。
 */
class RuleVersionAndReplayTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionAndReplayTest.class);

    @TempDir
    Path tmp;

    private static final Instant T = Instant.parse("2026-09-20T10:00:00Z");

    private ComplianceRuleService ruleService;
    private EvaluationService evaluationService;
    private InMemoryAuditEventStore eventStore;
    private EvaluationRecordStore evalStore;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), java.time.ZoneOffset.UTC);
        eventStore = new InMemoryAuditEventStore(tmp.toString());
        evalStore = new EvaluationRecordStore(tmp.toString());
        ruleService = new ComplianceRuleService();
        evaluationService = new EvaluationService(ruleService, eventStore, evalStore, clock);
    }

    private EvaluationRecord lastRecordOf(String eventId) {
        List<EvaluationRecord> rs = evaluationService.recordsOf(eventId);
        return rs.get(rs.size() - 1);
    }

    private AuditEvent event(String id, String subject, String action, String severity) {
        return new AuditEvent(id, subject, action, T, severity, Map.of("note", "export customer data"));
    }

    @Test
    void rule_version_increments_and_replay_is_reproducible() {
        eventStore.saveIfAbsent(event("e1", "svc-admin", "export", "HIGH"));
        eventStore.saveIfAbsent(event("e2", "bob", "login", "LOW"));

        // v1: 仅匹配 admin 主体的 export
        ComplianceRule v1 = new ComplianceRule("R1", 0, "admin", "export",
                Severity.HIGH, null, "admin export rule");
        assertEquals(1, ruleService.create(v1).version());

        long run1 = evaluationService.replay(T.minusSeconds(1), T.plusSeconds(1));
        assertEquals(1L, run1);
        EvaluationRecord r1e1 = lastRecordOf("e1");
        EvaluationRecord r1e2 = lastRecordOf("e2");
        log.info("replay#{} e1: ruleId={} ruleVersion={} decision={} basis={}",
                run1, r1e1.ruleId(), r1e1.ruleVersion(), r1e1.decision(), r1e1.basis());
        log.info("replay#{} e2: ruleId={} ruleVersion={} decision={} basis={}",
                run1, r1e2.ruleId(), r1e2.ruleVersion(), r1e2.decision(), r1e2.basis());
        assertEquals(EvaluationDecision.VIOLATION, r1e1.decision());
        assertEquals(1L, r1e1.ruleVersion());
        assertEquals(EvaluationDecision.PASS, r1e2.decision());

        // 更新规则到 v2：去掉主体限制
        ComplianceRule v2 = new ComplianceRule("R1", 0, null, "export",
                Severity.LOW, null, "any export rule");
        assertEquals(2L, ruleService.update(v2).version());

        long run2 = evaluationService.replay(T.minusSeconds(1), T.plusSeconds(1));
        assertEquals(2L, run2);
        EvaluationRecord r2e1 = lastRecordOf("e1");
        EvaluationRecord r2e2 = lastRecordOf("e2");
        log.info("replay#{} e1: ruleId={} ruleVersion={} decision={} basis={}",
                run2, r2e1.ruleId(), r2e1.ruleVersion(), r2e1.decision(), r2e1.basis());
        log.info("replay#{} e2: ruleId={} ruleVersion={} decision={} basis={}",
                run2, r2e2.ruleId(), r2e2.ruleVersion(), r2e2.decision(), r2e2.basis());
        assertEquals(EvaluationDecision.VIOLATION, r2e1.decision());
        assertEquals(2L, r2e1.ruleVersion());
        assertEquals(EvaluationDecision.PASS, r2e2.decision());

        // 再用 v2 规则重放一次：同一规则版本 + 同一事件集 => 结果完全一致（可复现）
        long run3 = evaluationService.replay(T.minusSeconds(1), T.plusSeconds(1));
        assertEquals(3L, run3);
        EvaluationRecord r3e1 = lastRecordOf("e1");
        assertEquals(r3e1.decision(), r2e1.decision());
        assertEquals(r3e1.ruleVersion(), r2e1.ruleVersion());
        assertEquals(r3e1.basis(), r2e1.basis());
        log.info("replay reproducible: run#{} == run#{} for e1 (ruleVersion={})",
                run3, run2, r3e1.ruleVersion());

        // 历史：e1 应保留 run 0、1、2 三个批次的判定轨迹，规则版本可追溯
        List<EvaluationRecord> history = evaluationService.recordsOf("e1");
        assertEquals(3, history.size());
        assertTrue(history.stream().anyMatch(r -> r.replayRunId() == 1 && r.ruleVersion() == 1));
        assertTrue(history.stream().anyMatch(r -> r.replayRunId() == 2 && r.ruleVersion() == 2));
        assertTrue(history.stream().anyMatch(r -> r.replayRunId() == 3 && r.ruleVersion() == 2));
        assertNotEquals(history.get(0).basis(), "");
    }
}
