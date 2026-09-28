package com.github.highcumontoa.audit;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.EvaluationDecision;
import com.github.highcumontoa.audit.service.QueryExportService;
import com.github.highcumontoa.audit.service.RetentionPolicy;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：
 * 1) 边界分页：同一 occurredAt 多条记录时，(time, eventId) 游标不漏不重；
 * 2) 并发写入同时翻页：初始记录每条恰好出现一次，顺序稳定；
 * 3) 导出保留策略：过期数据不能带出。
 */
class PaginationAndExportTest {

    private static final Logger log = LoggerFactory.getLogger(PaginationAndExportTest.class);

    @TempDir
    Path tmp;

    private static final Instant T = Instant.parse("2026-09-20T10:00:00Z");

    private AuditEvent event(String id, Instant at, String subject) {
        return new AuditEvent(id, subject, "login", at, "LOW", java.util.Map.of());
    }

    @Test
    void keyset_pagination_is_stable_at_same_timestamp_boundary() {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore(tmp.toString());
        EvaluationRecordStore evalStore = new EvaluationRecordStore(tmp.toString());
        RetentionPolicy retention = new RetentionPolicy(90);
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), java.time.ZoneOffset.UTC);
        QueryExportService service = new QueryExportService(store, evalStore, retention, clock);

        // 25 个事件共用同一时间戳，仅 eventId 不同 —— 最容易踩分页边界
        for (int i = 0; i < 25; i++) {
            store.saveIfAbsent(event(String.format("id-%02d", i), T, "alice"));
        }

        List<String> all = walkPages(service, 7);
        log.info("walked {} ids: {}", all.size(), all);
        assertEquals(25, all.size());
        assertEquals(25, new HashSet<>(all).size(), "must not contain duplicate boundary records");
        List<String> sorted = all.stream().sorted().toList();
        assertEquals(sorted, all, "page order must stay (occurredAt,eventId) sorted");
    }

    @Test
    void pagination_under_concurrent_writes_does_not_duplicate_or_skip_originals() throws Exception {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore(tmp.toString());
        EvaluationRecordStore evalStore = new EvaluationRecordStore(tmp.toString());
        RetentionPolicy retention = new RetentionPolicy(3650);
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), java.time.ZoneOffset.UTC);
        QueryExportService service = new QueryExportService(store, evalStore, retention, clock);

        Set<String> originalIds = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String id = String.format("orig-%03d", i);
            store.saveIfAbsent(event(id, T.plusSeconds(i), "alice"));
            originalIds.add(id);
        }

        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> {
            // 新写入全部落在初始记录之后（排序键更大），每页翻页都可能看到新数据
            for (int i = 0; i < 40; i++) {
                store.saveIfAbsent(event(String.format("late-%03d", i),
                        T.plusSeconds(1000 + i), "alice"));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        List<String> seen = new ArrayList<>();
        String cursor = null;
        while (true) {
            QueryExportService.Page page = service.query("alice", null, null, null, 5, cursor);
            page.events().forEach(e -> seen.add(e.eventId()));
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("total seen={} (originals=40, late writes up to 40)", seen.size());
        assertEquals(seen.size(), new HashSet<>(seen).size(), "no record may appear twice across pages");
        // 初始 40 条必须全部出现且仅一次（无漏读、无重复）
        for (String id : originalIds) {
            assertTrue(seen.contains(id), "original record missing from pagination: " + id);
        }
        long origCount = seen.stream().filter(s -> s.startsWith("orig-")).count();
        assertEquals(40, origCount);
    }

    @Test
    void export_never_carries_out_expired_data() {
        InMemoryAuditEventStore store = new InMemoryAuditEventStore(tmp.toString());
        EvaluationRecordStore evalStore = new EvaluationRecordStore(tmp.toString());
        RetentionPolicy retention = new RetentionPolicy(90);
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        QueryExportService service = new QueryExportService(store, evalStore, retention, clock);

        Instant fresh = now.minus(10, java.time.temporal.ChronoUnit.DAYS);
        Instant expired = now.minus(120, java.time.temporal.ChronoUnit.DAYS);
        store.saveIfAbsent(event("fresh-1", fresh, "alice"));
        store.saveIfAbsent(event("expired-1", expired, "alice"));

        // 即使调用方显式请求很早的 from，也会被保留窗口钳制
        List<AuditEvent> exported = service.export("alice", Instant.EPOCH, now, now);
        log.info("exported with retention cutoff={}: {}", retention.cutoff(now),
                exported.stream().map(AuditEvent::eventId).toList());
        assertEquals(1, exported.size());
        assertEquals("fresh-1", exported.get(0).eventId());
        assertFalse(exported.stream().anyMatch(e -> e.occurredAt().isBefore(retention.cutoff(now))));
    }

    private List<String> walkPages(QueryExportService service, int pageSize) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 100) {
            QueryExportService.Page page = service.query("alice", null, null,
                    (EvaluationDecision) null, pageSize, cursor);
            page.events().forEach(e -> ids.add(e.eventId()));
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        return ids;
    }
}
