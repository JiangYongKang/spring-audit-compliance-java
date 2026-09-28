package com.github.highcumontoa.springauditcompliancejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RuleView;
import com.github.highcumontoa.springauditcompliancejava.repo.ComplianceRuleRepository;
import com.github.highcumontoa.springauditcompliancejava.service.RuleService;

/**
 * 规则版本与并发：更新串行化，版本号不重复、无两个 active 版本。
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class RuleConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RuleConcurrencyTest.class);

    @Autowired
    private RuleService ruleService;
    @Autowired
    private ComplianceRuleRepository ruleRepo;

    @Test
    void updateCreatesMonotonicVersionsAndKeepsHistory() {
        RuleRequest v1 = new RuleRequest("rule-hist", "高危操作", null,
                "alice", "*", "*", "HIGH");
        log.info("用例[版本递增] 输入规则={}", v1);
        RuleView created = ruleService.create(v1);
        assertEquals(1, created.version());
        assertTrue(created.active());

        RuleRequest v2 = new RuleRequest("rule-hist", "高危操作v2", null,
                "alice", "delete", "*", "MEDIUM");
        RuleView updated = ruleService.update("rule-hist", v2);
        assertEquals(2, updated.version());
        assertTrue(updated.active());

        List<RuleView> history = ruleService.history("rule-hist");
        assertEquals(2, history.size());
        assertFalse(history.get(0).active());
        assertTrue(history.get(1).active());
        assertEquals(List.of(1, 2), history.stream().map(RuleView::version).toList());
        log.info("用例[版本递增] 判定依据=旧版本停用、新版本号=max(version)+1，版本链={}",
                history.stream().map(r -> "v" + r.version() + ":" + r.active())
                        .collect(Collectors.joining(",")));
    }

    @Test
    void concurrentUpdatesNeverProduceDuplicateVersionOrTwoActive() throws Exception {
        ruleService.create(new RuleRequest("rule-conc", "初始", null,
                "*", "*", "*", "LOW"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Integer> seenVersions = ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger conflicts =
                new java.util.concurrent.atomic.AtomicInteger();
        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            tasks.add(() -> {
                RuleRequest req = new RuleRequest("rule-conc", "更新" + seq, null,
                        "*", "*", "*", "HIGH");
                try {
                    RuleView view = ruleService.update("rule-conc", req);
                    log.info("用例[并发更新] 线程={} 读到新版本=v{}", seq, view.version());
                    seenVersions.add(view.version());
                } catch (com.github.highcumontoa.springauditcompliancejava.error.ApiException ex) {
                    // 锁等待超时映射为稳定的 409，调用方据此重试；绝不能出现脏版本
                    assertEquals("RULE_VERSION_CONFLICT", ex.getErrorCode().name());
                    conflicts.incrementAndGet();
                    log.info("用例[并发更新] 线程={} 收到稳定冲突 409，可重试", seq);
                }
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        for (Future<Void> f : futures) {
            f.get();
        }

        List<RuleView> chain = ruleService.history("rule-conc");
        int success = threads - conflicts.get();
        log.info("用例[并发更新] 判定依据=锁点悲观写锁串行化；成功={}, 409冲突={}, 最终版本链={}",
                success, conflicts.get(),
                chain.stream().map(r -> "v" + r.version() + ":" + r.active())
                        .collect(Collectors.joining(",")));
        assertTrue(success >= 1, "至少一个更新成功");
        assertEquals(success, seenVersions.size(), "成功更新的新版本号互不重复");
        assertEquals(success + 1, chain.size(), "每个成功更新恰好产生一个新版本");
        assertEquals(1, chain.stream().filter(RuleRuleActive()).count(),
                "任意时刻只有一个生效版本");
        // 版本号连续无空洞且最终版本为最大号
        for (int v = 1; v <= chain.size(); v++) {
            final int expect = v;
            assertTrue(chain.stream().anyMatch(r -> r.version() == expect),
                    "版本号 " + expect + " 存在");
        }
    }

    private static java.util.function.Predicate<RuleView> RuleRuleActive() {
        return RuleView::active;
    }

    @Test
    void createRejectsInvalidSeverity() {
        RuleRequest bad = new RuleRequest("rule-bad", "坏规则", null,
                "*", "*", "*", "CATASTROPHIC");
        log.info("用例[规则非法级别] 输入={}", Map.of("minSeverity", "CATASTROPHIC"));
        try {
            ruleService.create(bad);
            throw new AssertionError("应拒绝非法级别");
        } catch (com.github.highcumontoa.springauditcompliancejava.error.ApiException ex) {
            assertEquals("VALIDATION_INVALID_SEVERITY", ex.getErrorCode().name());
            log.info("用例[规则非法级别] 判定依据=枚举校验，错误码={}", ex.getErrorCode());
        }
    }
}
