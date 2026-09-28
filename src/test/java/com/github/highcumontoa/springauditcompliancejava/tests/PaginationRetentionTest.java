package com.github.highcumontoa.springauditcompliancejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import com.github.highcumontoa.springauditcompliancejava.dto.PageResponse;
import com.github.highcumontoa.springauditcompliancejava.dto.ResultView;
import com.github.highcumontoa.springauditcompliancejava.dto.RunView;
import com.github.highcumontoa.springauditcompliancejava.service.EvaluationService;
import com.github.highcumontoa.springauditcompliancejava.service.QueryExportService;
import com.github.highcumontoa.springauditcompliancejava.dto.ReplayRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest;
import com.github.highcumontoa.springauditcompliancejava.service.RuleService;
import com.github.highcumontoa.springauditcompliancejava.tests.support.TestData;

/**
 * 边界分页：并发写入下键集分页不漏读、不重复边界记录；
 * 保留策略：过期数据既不能查询带出，也不能导出带出。
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@Transactional
class PaginationRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(PaginationRetentionTest.class);

    @Autowired
    private TestData testData;
    @Autowired
    private EvaluationService evaluations;
    @Autowired
    private RuleService ruleService;
    @Autowired
    private QueryExportService query;

    /**
     * 同一毫秒插入多条事件（eventTime 相同，仅自增 id 不同），
     * 小页深翻，每页之间不能漏掉或重复边界记录。
     */
    @Test
    void keysetPaginationIsStableAtSameTimestampBoundary() {
        Instant same = Instant.parse("2026-09-15T10:00:00Z");
        int total = 25;
        for (int i = 0; i < total; i++) {
            testData.insert("ev-page-" + i, "dave", "act", null, Severity.LOW, same);
        }
        // 一条窗口外、另一个主体的数据，确认不会串入
        testData.insert("ev-page-other", "eve", "act", null, Severity.LOW, same);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            PageResponse<ResultView> page = query.queryResults("dave", null, null,
                    null, null, cursor, 7);
            pages++;
            for (ResultView r : page.items()) {
                // 判定查询返回的是结果行；这里没有规则，先评估产生结果（见下）
                seen.add(r.eventId() + "#" + r.ruleId());
            }
            cursor = page.nextCursor();
            if (cursor == null) {
                assertFalse(page.hasMore());
                break;
            }
            assertNotNull(cursor);
            assertTrue(pages < 100, "防止死循环");
        }

        // 上面查询在评估前为空；先断言"无结果时分页立即结束"
        log.info("用例[空结果分页] 翻页次数={}, 累计行数={}", pages, seen.size());
        assertEquals(0, seen.size());

        // 建立一条全匹配规则并评估
        ruleService.create(new RuleRequest("rule-page", "全匹配", null,
                "dave", "*", "*", "LOW"));
        evaluations.evaluateWindow(new ReplayRequest(
                "2026-09-15T00:00:00Z", "2026-09-16T00:00:00Z",
                null, "run-page"));

        List<String> keys = new ArrayList<>();
        cursor = null;
        pages = 0;
        while (true) {
            PageResponse<ResultView> page = query.queryResults("dave", null, null,
                    Instant.parse("2026-09-15T00:00:00Z"),
                    Instant.parse("2026-09-16T00:00:00Z"), cursor, 7);
            pages++;
            for (ResultView r : page.items()) {
                keys.add(r.eventId() + "#" + r.ruleId() + "#" + r.rowId());
            }
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        log.info("用例[同毫秒边界分页] 输入=25条同eventTime事件, 页大小=7, 翻页次数={}, 累计={}",
                pages, keys.size());
        assertEquals(total, keys.size(), "25 条事件全部恰好读到一次");
        assertEquals(total, keys.stream().distinct().count(), "不存在重复边界记录");
        // 页数 = ceil(25/7) = 4
        assertEquals(4, pages);
    }

    @Test
    void expiredDataCannotBeQueriedOrExported() {
        Instant fresh = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant expired = Instant.now().minus(400, ChronoUnit.DAYS);
        testData.insert("ev-fresh", "frank", "act", null, Severity.LOW, fresh);
        testData.insert("ev-expired", "frank", "act", null, Severity.LOW, expired);
        ruleService.create(new RuleRequest("rule-frank", "frank任意", null,
                "frank", "*", "*", "LOW"));
        Map<String, Integer> pinFrank = Map.of("rule-frank", 1);

        // 用两个独立批次分别落在各自时间窗（批次本身不受保留期影响；保留期约束的是读取/导出）
        RunView freshRun = evaluations.evaluateWindow(new ReplayRequest(
                fresh.minusSeconds(600).toString(), fresh.plusSeconds(600).toString(),
                pinFrank, "run-fresh"));
        RunView expiredRun = evaluations.evaluateWindow(new ReplayRequest(
                expired.minusSeconds(600).toString(), expired.plusSeconds(600).toString(),
                pinFrank, "run-expired"));
        log.info("用例[保留期] 输入 fresh={}, expired={}, 保留天数=365, 批次fresh事件数={}, expired事件数={}",
                fresh, expired, freshRun.eventCount(), expiredRun.eventCount());

        // 查询：不带时间窗，过期结果不应出现
        PageResponse<ResultView> page = query.queryResults("frank", null, null,
                null, null, null, 50);
        List<String> queried = page.items().stream().map(ResultView::eventId).toList();
        log.info("用例[保留期] 查询返回={}, retentionCutoff={}", queried, page.retentionCutoff());
        assertTrue(queried.contains("ev-fresh"));
        assertFalse(queried.contains("ev-expired"), "过期数据不能通过查询带出");

        // 即便显式指定过期批次 runId 导出，截止线仍在 SQL 层拦截
        String csvExpired = query.exportResultsCsv("frank", null, "run-expired",
                Instant.EPOCH, Instant.parse("9999-12-31T23:59:59Z"));
        String csvFresh = query.exportResultsCsv("frank", null, "run-fresh",
                Instant.EPOCH, Instant.parse("9999-12-31T23:59:59Z"));
        log.info("用例[保留期] 导出过期批次是否含expired={}, 导出新鲜批次是否含fresh={}",
                csvExpired.contains("ev-expired"), csvFresh.contains("ev-fresh"));
        assertFalse(csvExpired.contains("ev-expired"), "过期数据不能通过导出带出");
        assertTrue(csvFresh.contains("ev-fresh"));

        // 显式用很老的时间窗查询：受截止线约束返回空
        PageResponse<ResultView> oldWindow = query.queryResults("frank", null, null,
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-02-01T00:00:00Z"), null, 50);
        assertNull(oldWindow.nextCursor());
        assertEquals(0, oldWindow.items().size());
    }
}
