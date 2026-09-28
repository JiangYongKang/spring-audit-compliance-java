package com.github.highcumontoa.audit;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.IngestResult;
import com.github.highcumontoa.audit.model.RejectionReason;
import com.github.highcumontoa.audit.service.AuditEventService;
import com.github.highcumontoa.audit.service.AuditEventValidator;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 覆盖：重复提交幂等、缺字段/时间异常/非法严重级别的可区分拒绝。
 * 日志打印输入、规则版本与判定依据（判定由 EvaluationService 在评估时打印）。
 */
class AuditEventIngestTest {

    private static final Logger log = LoggerFactory.getLogger(AuditEventIngestTest.class);

    @TempDir
    Path tmp;

    private AuditEventService service;
    private InMemoryAuditEventStore store;
    private Clock clock;
    private final Instant now = Instant.parse("2026-09-28T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        this.store = new InMemoryAuditEventStore(tmp.toString());
        var evalStore = new EvaluationRecordStore(tmp.toString());
        var ruleService = new ComplianceRuleService();
        var evaluationService = new EvaluationService(ruleService, store, evalStore, clock);
        var validator = new AuditEventValidator(clock, 5, 365);
        service = new AuditEventService(validator, store, evaluationService);
    }

    private AuditEvent event(String id, String subject, String action, Instant at, String severity) {
        return new AuditEvent(id, subject, action, at, severity, Map.of("detail", "login attempt"));
    }

    @Test
    void duplicate_submit_does_not_create_duplicate_record() {
        AuditEvent e = event("evt-1", "alice", "login", now.minus(Duration.ofMinutes(1)), "HIGH");
        log.info("test input: {}", e);

        IngestResult first = service.ingest(e);
        IngestResult second = service.ingest(e);

        log.info("first={} second={}", first, second);
        assertEquals(IngestResult.Status.ACCEPTED, first.status());
        assertEquals(IngestResult.Status.DUPLICATE, second.status());
        assertEquals(1, store.findAllSorted().size());
        assertEquals("evt-1", store.findById("evt-1").orElseThrow().eventId());
    }

    @Test
    void missing_fields_are_rejected_with_distinct_reason() {
        AuditEvent noSubject = event("evt-2", null, "login", now, "LOW");
        log.info("test input: {}", noSubject);
        IngestResult r = service.ingest(noSubject);
        log.info("result status={} reason={} detail={}", r.status(), r.rejectionReason(), r.detail());
        assertEquals(IngestResult.Status.REJECTED, r.status());
        assertEquals(RejectionReason.MISSING_FIELD, r.rejectionReason());
        assertNotNull(r.detail());
    }

    @Test
    void future_time_is_time_anomaly() {
        AuditEvent future = event("evt-3", "alice", "login",
                now.plus(Duration.ofHours(1)), "LOW");
        log.info("test input: {}", future);
        IngestResult r = service.ingest(future);
        log.info("result status={} reason={} detail={}", r.status(), r.rejectionReason(), r.detail());
        assertEquals(IngestResult.Status.REJECTED, r.status());
        assertEquals(RejectionReason.TIME_ANOMALY, r.rejectionReason());
    }

    @Test
    void invalid_severity_is_rejected() {
        AuditEvent bad = event("evt-4", "alice", "login", now, "FATAL");
        log.info("test input: {}", bad);
        IngestResult r = service.ingest(bad);
        log.info("result status={} reason={} detail={}", r.status(), r.rejectionReason(), r.detail());
        assertEquals(IngestResult.Status.REJECTED, r.status());
        assertEquals(RejectionReason.INVALID_SEVERITY, r.rejectionReason());
    }
}
