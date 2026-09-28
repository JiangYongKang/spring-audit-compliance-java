package com.github.highcumontoa.audit.service;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.EvaluationDecision;
import com.github.highcumontoa.audit.model.EvaluationRecord;
import com.github.highcumontoa.audit.store.AuditEventStore;
import com.github.highcumontoa.audit.store.EvaluationRecordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 查询与导出。
 *
 * 分页稳定性：排序键固定为 (occurredAt, eventId)，游标编码上一页最后一个排序键，
 * 后续页只取排序键严格大于游标的记录。基于写时快照 + 键集（keyset）分页，
 * 并发写入时不会重复边界记录，也不会漏读游标之后新落入的记录。
 *
 * 导出安全：请求的 from 会被钳制到保留窗口起点 cutoff，过期数据无法被带出。
 */
@Service
public class QueryExportService {

    private static final Logger log = LoggerFactory.getLogger(QueryExportService.class);

    private final AuditEventStore eventStore;
    private final EvaluationRecordStore recordStore;
    private final RetentionPolicy retentionPolicy;
    private final Clock clock;

    public QueryExportService(AuditEventStore eventStore,
                              EvaluationRecordStore recordStore,
                              RetentionPolicy retentionPolicy,
                              Clock clock) {
        this.eventStore = eventStore;
        this.recordStore = recordStore;
        this.retentionPolicy = retentionPolicy;
        this.clock = clock;
    }

    public record Page(List<AuditEvent> events, String nextCursor) {
    }

    public Page query(String subject, Instant from, Instant to,
                      EvaluationDecision decision, int pageSize, String cursor) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        String[] after = decodeCursor(cursor); // [epochMillis, eventId]
        List<AuditEvent> result = new ArrayList<>(pageSize);
        String next = null;

        // 写时快照：本次请求遍历期间，新写入不影响本页已确定的排序边界
        for (AuditEvent e : eventStore.findAllSorted()) {
            if (afterKey(e, after) <= 0) {
                continue; // 游标之前（含游标本身）的记录跳过，杜绝边界重复
            }
            if (!matches(e, subject, from, to, decision)) {
                continue;
            }
            result.add(e);
            if (result.size() == pageSize) {
                next = encodeCursor(e);
                break;
            }
        }
        return new Page(List.copyOf(result), next);
    }

    public List<AuditEvent> export(String subject, Instant from, Instant to, Instant now) {
        Instant effectiveNow = now != null ? now : clock.instant();
        Instant cutoff = retentionPolicy.cutoff(effectiveNow);
        Instant effectiveFrom = (from == null || from.isBefore(cutoff)) ? cutoff : from;
        Instant effectiveTo = to != null ? to : effectiveNow;
        if (effectiveFrom.isAfter(effectiveTo)) {
            return List.of();
        }
        List<AuditEvent> exported = eventStore.findInWindow(effectiveFrom, effectiveTo).stream()
                .filter(e -> subject == null || subject.isBlank() || subject.equals(e.subject()))
                .toList();
        log.info("export subject={} requestedFrom={} effectiveFrom={}(retentionCutoff) to={} count={}",
                subject, from, cutoff, effectiveTo, exported.size());
        return exported;
    }

    private boolean matches(AuditEvent e, String subject, Instant from, Instant to,
                            EvaluationDecision decision) {
        if (subject != null && !subject.isBlank() && !subject.equals(e.subject())) {
            return false;
        }
        if (from != null && e.occurredAt().isBefore(from)) {
            return false;
        }
        if (to != null && e.occurredAt().isAfter(to)) {
            return false;
        }
        if (decision != null) {
            EvaluationRecord latest = recordStore.latestOf(e.eventId());
            if (latest == null || latest.decision() != decision) {
                return false;
            }
        }
        return true;
    }

    private static int afterKey(AuditEvent e, String[] cursor) {
        if (cursor == null) {
            return 1;
        }
        long ts = e.occurredAt().toEpochMilli();
        long afterTs = Long.parseLong(cursor[0]);
        if (ts != afterTs) {
            return Long.compare(ts, afterTs);
        }
        return e.eventId().compareTo(cursor[1]);
    }

    private static String encodeCursor(AuditEvent e) {
        String raw = e.occurredAt().toEpochMilli() + "|" + e.eventId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int sep = raw.indexOf('|');
        return new String[]{raw.substring(0, sep), raw.substring(sep + 1)};
    }
}
