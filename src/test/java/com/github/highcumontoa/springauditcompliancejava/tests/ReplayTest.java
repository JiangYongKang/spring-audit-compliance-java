package com.github.highcumontoa.springauditcompliancejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.transaction.annotation.Transactional;

import com.github.highcumontoa.springauditcompliancejava.domain.Severity;
import com.github.highcumontoa.springauditcompliancejava.dto.ReplayRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.ResultView;
import com.github.highcumontoa.springauditcompliancejava.dto.RunView;
import com.github.highcumontoa.springauditcompliancejava.repo.EvaluationResultRepository;
import com.github.highcumontoa.springauditcompliancejava.service.EvaluationService;
import com.github.highcumontoa.springauditcompliancejava.service.RuleService;
import com.github.highcumontoa.springauditcompliancejava.service.QueryExportService;
import com.github.highcumontoa.springauditcompliancejava.tests.support.TestData;

/**
 * 历史重放：同一窗口用不同规则版本得到不同但可复现的结果；每条结果带规则版本与依据；
 * 批次快照记录所用版本；同 runId 重放幂等。
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@Transactional
class ReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayTest.class);

    @Autowired
    private EvaluationService evaluations;
    @Autowired
    private RuleService ruleService;
    @Autowired
    private QueryExportService query;
    @Autowired
    private EvaluationResultRepository resultRepo;
    @Autowired
    private TestData testData;

    @Test
    void replayIsReproducibleAndRecordsRuleVersion() {
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-01T11:00:00Z");
        testData.insert("ev-r1", "alice", "delete", "/db", Severity.HIGH, t1);
        testData.insert("ev-r2", "bob", "read", "/file", Severity.LOW, t2);

        // v1: 仅 alice 的任意操作 + HIGH 及以上 => 非合规
        ruleService.create(new com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest(
                "rule-replay", "alice高危", null, "alice", "*", "*", "HIGH"));

        ReplayRequest req = new ReplayRequest(
                "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", null, "run-replay-1");
        log.info("用例[历史重放v1] 输入窗口=[{},{}), 当前规则版本=1", req.windowStart(), req.windowEnd());
        RunView run1 = evaluations.evaluateWindow(req);

        List<ResultView> results1 = query.queryResults(null, null, "run-replay-1",
                null, null, null, 50).items();
        long nc1 = results1.stream().filter(r -> r.decision().equals("NON_COMPLIANT")).count();
        log.info("用例[历史重放v1] 规则版本快照={}, 非合规数={}, 结果详情={}",
                run1.ruleSnapshotJson(), nc1,
                results1.stream().map(r -> r.eventId() + ":v" + r.ruleVersion()
                        + ":" + r.decision()).toList());
        assertEquals("COMPLETED", run1.status());
        assertEquals("{\"rule-replay\":1}", run1.ruleSnapshotJson());
        assertEquals(2, results1.size());
        assertEquals(1, nc1);
        ResultView aliceResult = results1.stream()
                .filter(r -> r.eventId().equals("ev-r1")).findFirst().orElseThrow();
        assertEquals(1, aliceResult.ruleVersion());
        assertTrue(aliceResult.rationale().contains("=> NON_COMPLIANT"),
                "依据必须说明判定理由");

        // 更新规则为 v2：alice + delete + MEDIUM 以上（阈值放宽）
        ruleService.update("rule-replay",
                new com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest(
                        "rule-replay", "alice删除", null, "alice", "delete", "*", "MEDIUM"));

        RunView run2 = evaluations.evaluateWindow(new ReplayRequest(
                "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", null, "run-replay-2"));
        List<ResultView> results2 = query.queryResults(null, null, "run-replay-2",
                null, null, null, 50).items();
        long nc2 = results2.stream().filter(r -> r.decision().equals("NON_COMPLIANT")).count();
        log.info("用例[历史重放v2] 规则版本快照={}, 非合规数={}", run2.ruleSnapshotJson(), nc2);
        assertEquals("{\"rule-replay\":2}", run2.ruleSnapshotJson());
        // v2 阈值 MEDIUM + 仍要求 alice+delete；ev-r1 为 HIGH 命中，ev-r2 主体不匹配
        assertEquals(1, nc2);
        ResultView aliceV2 = results2.stream()
                .filter(r -> r.eventId().equals("ev-r1")).findFirst().orElseThrow();
        assertEquals(2, aliceV2.ruleVersion());
        assertNotEquals(aliceResult.rationale(), aliceV2.rationale());

        // 用指定的历史版本 1 再重放同一窗口：必须逐位复现 v1 结论
        RunView rerun = evaluations.evaluateWindow(new ReplayRequest(
                "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z",
                Map.of("rule-replay", 1), "run-replay-3"));
        List<ResultView> results3 = query.queryResults(null, null, "run-replay-3",
                null, null, null, 50).items();
        long nc3 = results3.stream().filter(r -> r.decision().equals("NON_COMPLIANT")).count();
        log.info("用例[按v1重放] 规则版本快照={}, 非合规数={}（应与v1完全一致）",
                rerun.ruleSnapshotJson(), nc3);
        assertEquals("{\"rule-replay\":1}", rerun.ruleSnapshotJson());
        assertEquals(nc1, nc3);
        assertEquals(
                results1.stream().map(r -> r.eventId() + r.decision() + r.rationale()).sorted().toList(),
                results3.stream().map(r -> r.eventId() + r.decision() + r.rationale()).sorted().toList(),
                "同窗口同版本重放结果必须逐位可复现");
    }

    @Test
    void duplicateRunIdIsIdempotent() {
        Instant t = Instant.parse("2026-09-10T10:00:00Z");
        testData.insert("ev-rid", "alice", "login", null, Severity.LOW, t);
        ruleService.create(new com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest(
                "rule-rid", "任意", null, "*", "*", "*", "CRITICAL"));

        ReplayRequest req = new ReplayRequest(
                "2026-09-10T00:00:00Z", "2026-09-11T00:00:00Z", null, "run-idempotent");
        RunView first = evaluations.evaluateWindow(req);
        List<ResultView> afterFirst = resultRepo.findByRunId("run-idempotent").stream()
                .map(ResultView::of).toList();
        RunView second = evaluations.evaluateWindow(req);
        List<ResultView> afterSecond = resultRepo.findByRunId("run-idempotent").stream()
                .map(ResultView::of).toList();
        log.info("用例[批次幂等] runId=run-idempotent, 两次批次号一致={}, 结果数={}（重放前后不变）",
                first.runId().equals(second.runId()), afterSecond.size());
        assertEquals(first.runId(), second.runId());
        assertEquals(afterFirst.size(), afterSecond.size(),
                "重复提交同一 runId 不产生额外判定行");
        assertEquals(1, first.eventCount());
        assertEquals(first.ruleCount(), second.ruleCount());
    }
}
