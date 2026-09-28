package com.github.highcumontoa.springauditcompliancejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import com.github.highcumontoa.springauditcompliancejava.domain.Severity;
import com.github.highcumontoa.springauditcompliancejava.dto.ReplayRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RunView;
import com.github.highcumontoa.springauditcompliancejava.repo.EvaluationResultRepository;
import com.github.highcumontoa.springauditcompliancejava.service.EvaluationService;
import com.github.highcumontoa.springauditcompliancejava.service.RuleService;
import com.github.highcumontoa.springauditcompliancejava.tests.support.TestData;

/**
 * 并发更新规则与并发评估：
 * 1) 每个评估批次自洽——同一批次内某规则的版本号恒定（绝不读到半更新状态）；
 * 2) 评估的规则版本只会是某一完整历史版本或当前版本；
 * 3) 判定行 (runId,eventId,ruleId) 无重复、无丢失（事件数×规则数守恒）。
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class ConcurrentUpdateEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentUpdateEvaluationTest.class);

    @Autowired
    private EvaluationService evaluations;
    @Autowired
    private RuleService ruleService;
    @Autowired
    private EvaluationResultRepository resultRepo;
    @Autowired
    private TestData testData;

    @Test
    void concurrentReadsNeverSeeHalfUpdatedRule() throws Exception {
        ruleService.create(new RuleRequest("rule-conc-eval", "初始", null,
                "root", "*", "*", "LOW"));
        // 窗口内固定 10 个事件
        for (int i = 0; i < 10; i++) {
            testData.insert("ev-ce-" + i, "root", "act", null, Severity.LOW,
                    Instant.parse("2026-09-20T10:00:" + String.format("%02d", i) + "Z"));
        }

        int evaluators = 6;
        ExecutorService pool = Executors.newFixedThreadPool(evaluators + 2);

        // 六个评估者：各自独立批次
        List<Future<RunView>> evalFutures = new ArrayList<>();
        for (int e = 0; e < evaluators; e++) {
            Callable<RunView> task = () -> evaluations.evaluateWindow(new ReplayRequest(
                    "2026-09-20T00:00:00Z", "2026-09-21T00:00:00Z",
                    null, "run-ce-" + UUID.randomUUID()));
            evalFutures.add(pool.submit(task));
        }
        // 更新任务也提交
        List<Future<Void>> updFutures = new ArrayList<>();
        for (int u = 0; u < 2; u++) {
            final int uSeq = u;
            updFutures.add(pool.submit(() -> {
                for (int k = 0; k < 5; k++) {
                    try {
                        ruleService.update("rule-conc-eval", new RuleRequest(
                                "rule-conc-eval", "late更新者" + uSeq + "#" + k, null,
                                "root", "*", "*", "LOW"));
                    } catch (com.github.highcumontoa.springauditcompliancejava.error.ApiException ex) {
                        log.info("延迟更新冲突 409，忽略: {}", ex.getErrorCode());
                    }
                }
                return null;
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(90, TimeUnit.SECONDS));

        for (Future<RunView> f : evalFutures) {
            RunView run = f.get();
            assertEquals("COMPLETED", run.status());
            var rows = resultRepo.findByRunId(run.runId());

            // 守恒：10 事件 × 规则数（全量评估时规则数为 1；批次只含当时生效规则）
            assertEquals(10L * run.ruleCount(), rows.size(),
                    "批次 " + run.runId() + " 判定行数量守恒");

            // 自洽：批次内 rule-conc-eval 的版本号必须恒定
            long distinctVersions = rows.stream()
                    .filter(r -> r.getRuleId().equals("rule-conc-eval"))
                    .mapToInt(r -> r.getRuleVersion())
                    .distinct().count();
            assertEquals(1, distinctVersions,
                    "批次 " + run.runId() + " 内规则版本必须唯一（未读到半更新状态）");

            // 快照中的版本与每行冗余版本一致
            int snapshotVersion = objectMapperRead(run.ruleSnapshotJson())
                    .get("rule-conc-eval").asInt();
            assertTrue(rows.stream().allMatch(r ->
                    !r.getRuleId().equals("rule-conc-eval")
                            || r.getRuleVersion() == snapshotVersion),
                    "行内版本与批次快照一致");

            // 无重复 (runId,eventId,ruleId) 由唯一约束保证；这里再做逻辑断言
            long uniquePairs = rows.stream()
                    .map(r -> r.getEventId() + "|" + r.getRuleId())
                    .distinct().count();
            assertEquals(rows.size(), uniquePairs);

            log.info("用例[并发更新+评估] runId={} 使用规则版本=v{}, 判定行={}, 快照={}",
                    run.runId(), snapshotVersion, rows.size(), run.ruleSnapshotJson());
        }
        for (Future<Void> f : updFutures) {
            f.get();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode objectMapperRead(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
