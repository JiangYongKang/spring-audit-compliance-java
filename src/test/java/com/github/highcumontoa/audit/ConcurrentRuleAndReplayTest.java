package com.github.highcumontoa.audit;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.ComplianceRule;
import com.github.highcumontoa.audit.model.EvaluationRecord;
import com.github.highcumontoa.audit.model.Severity;
import com.github.highcumontoa.audit.service.ComplianceRuleService;
import com.github.highcumontoa.audit.service.EvaluationService;
import com.github.highcumontoa.audit.store.EvaluationRecordStore;
import com.github.highcumontoa.audit.store.InMemoryAuditEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 覆盖：规则更新与历史重放并发执行时，每个重放批次使用单一一致的规则快照，
 * 不会出现同一批次内部分记录读到旧版本、部分读到新版本（半更新）。
 */
class ConcurrentRuleAndReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRuleAndReplayTest.class);

    @TempDir
    Path tmp;

    private static final int EVENT_COUNT = 500;
    private static final int UPDATER_COUNT = 4;
    private static final int REPLAY_COUNT = 20;

    @Test
    void concurrent_updates_and_replays_never_observe_half_updated_state() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), java.time.ZoneOffset.UTC);
        InMemoryAuditEventStore eventStore = new InMemoryAuditEventStore(tmp.toString());
        EvaluationRecordStore evalStore = new EvaluationRecordStore(tmp.toString());
        ComplianceRuleService ruleService = new ComplianceRuleService();
        EvaluationService evaluationService = new EvaluationService(ruleService, eventStore, evalStore, clock);

        Instant base = Instant.parse("2026-09-20T10:00:00Z");
        for (int i = 0; i < EVENT_COUNT; i++) {
            eventStore.saveIfAbsent(new AuditEvent(
                    "e" + i, "subject-" + (i % 5), "action-" + (i % 3),
                    base.plusSeconds(i), i % 2 == 0 ? "HIGH" : "LOW",
                    Map.of("k", "v")));
        }
        ruleService.create(new ComplianceRule("R1", 0, "subject-1", null,
                Severity.HIGH, null, "initial"));

        ExecutorService pool = Executors.newFixedThreadPool(UPDATER_COUNT + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong updateSeq = new AtomicLong();
        List<Long> replayRunIds = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int u = 0; u < UPDATER_COUNT; u++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        long seq = updateSeq.incrementAndGet();
                        if (seq > 60) {
                            break;
                        }
                        String pattern = "subject-" + (seq % 5);
                        ruleService.update(new ComplianceRule("R1", 0, pattern, null,
                                Severity.HIGH, null, "update #" + seq));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int r = 0; r < REPLAY_COUNT; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long runId = evaluationService.replay(
                            base.minusSeconds(1), base.plusSeconds(EVENT_COUNT));
                    replayRunIds.add(runId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "work did not finish in time");

        // 每个重放批次：所有命中记录的 ruleVersion 必须相同（单一规则快照）
        Map<Long, List<EvaluationRecord>> byRun = evalStore.all().stream()
                .filter(rec -> replayRunIds.contains(rec.replayRunId()))
                .collect(Collectors.groupingBy(EvaluationRecord::replayRunId));

        Set<Long> seenVersions = new HashSet<>();
        for (Long runId : replayRunIds) {
            List<EvaluationRecord> recs = byRun.get(runId);
            if (recs == null || recs.size() != EVENT_COUNT) {
                fail("run " + runId + " produced " + (recs == null ? 0 : recs.size())
                        + " records, expected " + EVENT_COUNT);
            }
            Set<Long> violationVersions = recs.stream()
                    .filter(rec -> rec.ruleId() != null)
                    .map(EvaluationRecord::ruleVersion)
                    .collect(Collectors.toSet());
            log.info("runId={} records={} distinctViolationRuleVersions={}",
                    runId, recs.size(), violationVersions);
            assertTrue(violationVersions.size() <= 1,
                    "run " + runId + " saw mixed rule versions: " + violationVersions);
            violationVersions.forEach(seenVersions::add);
        }
        // 并发期间多个规则版本都应被观察到，证明更新确实在发生
        log.info("rule versions observed across replays: {}", seenVersions);
        assertTrue(seenVersions.size() > 1,
                "expected replays to observe several rule versions, got " + seenVersions);
    }
}
