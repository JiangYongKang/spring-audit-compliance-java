package com.github.highcumontoa.springauditcompliancejava.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.highcumontoa.springauditcompliancejava.config.RetentionProperties;
import com.github.highcumontoa.springauditcompliancejava.domain.Decision;
import com.github.highcumontoa.springauditcompliancejava.dto.EventView;
import com.github.highcumontoa.springauditcompliancejava.dto.PageResponse;
import com.github.highcumontoa.springauditcompliancejava.dto.ResultView;
import com.github.highcumontoa.springauditcompliancejava.repo.AuditEventRepository;
import com.github.highcumontoa.springauditcompliancejava.repo.EvaluationResultRepository;

/**
 * 查询与导出。
 *
 * <p>所有读路径都计算保留截止线 {@code cutoff = now - retentionDays} 并下推到 SQL：
 * eventTime 早于截止线的数据在数据库层即被排除，因此导出文件不可能携带过期数据。
 *
 * <p>分页使用 (eventTime, id) 键集游标：并发写入新数据只会出现在时间线尾部，
 * 不影响已确定的元组边界，既不漏读也不重复。
 */
@Service
public class QueryExportService {

    /** 时间窗口缺省起点。 */
    private static final Instant EPOCH = Instant.EPOCH;

    private final AuditEventRepository events;
    private final EvaluationResultRepository results;
    private final CursorCodec cursors;
    private final RetentionProperties retention;

    public QueryExportService(AuditEventRepository events,
                              EvaluationResultRepository results,
                              CursorCodec cursors,
                              RetentionProperties retention) {
        this.events = events;
        this.results = results;
        this.cursors = cursors;
        this.retention = retention;
    }

    private Instant cutoff() {
        return Instant.now().minusSeconds(retention.retentionDays() * 86_400L);
    }

    private Instant orStart(Instant t) {
        return t == null ? EPOCH : t;
    }

    private Instant orEnd(Instant t) {
        return t == null ? Instant.parse("9999-12-31T23:59:59Z") : t;
    }

    @Transactional(readOnly = true)
    public PageResponse<EventView> queryEvents(String subject, Instant start, Instant end,
                                               String cursor, int size) {
        Instant cut = cutoff();
        int pageSize = normalizeSize(size);
        var decoded = cursors.decode(cursor);
        var pageable = PageRequest.of(0, pageSize + 1);

        List<EventView> rows;
        if (decoded == null) {
            rows = events.findFirstPageBySubject(subject, orStart(start), orEnd(end), cut,
                            pageable).stream().map(EventView::of).toList();
        } else {
            rows = events.findPageBySubject(subject, orStart(start), orEnd(end), cut,
                            decoded.time(), decoded.id(), pageable).stream()
                    .map(EventView::of).toList();
        }
        return buildPage(rows, pageSize, cut);
    }

    @Transactional(readOnly = true)
    public PageResponse<ResultView> queryResults(String subject, Decision decision,
                                                 String runId, Instant start, Instant end,
                                                 String cursor, int size) {
        Instant cut = cutoff();
        int pageSize = normalizeSize(size);
        var decoded = cursors.decode(cursor);
        var pageable = PageRequest.of(0, pageSize + 1);

        List<ResultView> rows;
        if (decoded == null) {
            rows = results.queryFirstPage(subject, decision, runId,
                            orStart(start), orEnd(end), cut, pageable).stream()
                    .map(ResultView::of).toList();
        } else {
            rows = results.queryNextPage(subject, decision, runId,
                            orStart(start), orEnd(end), cut,
                            decoded.time(), decoded.id(), pageable).stream()
                    .map(ResultView::of).toList();
        }
        return buildPage(rows, pageSize, cut);
    }

    /**
     * 导出判定结果 CSV。导出前再次以保留截止线过滤（SQL 已过滤，双重保证），
     * 过期数据不会出现在文件中。
     */
    @Transactional(readOnly = true)
    public String exportResultsCsv(String subject, Decision decision, String runId,
                                   Instant start, Instant end) {
        Instant cut = cutoff();
        List<ResultView> rows = results.queryForExport(subject, decision, runId,
                        orStart(start), orEnd(end), cut).stream()
                .map(ResultView::of)
                .toList();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("runId,eventId,ruleId,ruleVersion,ruleName,subject,eventTime,severity,decision,rationale\r\n");
        for (ResultView r : rows) {
            sb.append(csv(r.runId())).append(',')
                    .append(csv(r.eventId())).append(',')
                    .append(csv(r.ruleId())).append(',')
                    .append(r.ruleVersion()).append(',')
                    .append(csv(r.ruleName())).append(',')
                    .append(csv(r.subject())).append(',')
                    .append(r.eventTime()).append(',')
                    .append(csv(r.severity())).append(',')
                    .append(r.decision()).append(',')
                    .append(csv(r.rationale())).append("\r\n");
        }
        return sb.toString();
    }

    public Instant retentionCutoff() {
        return cutoff();
    }

    private <T> PageResponse<T> buildPage(List<T> rows, int requestedSize, Instant cut) {
        boolean hasMore = rows.size() > requestedSize;
        List<T> page = hasMore ? rows.subList(0, requestedSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            Object last = page.get(page.size() - 1);
            nextCursor = encodeLast(last);
        }
        return new PageResponse<>(List.copyOf(page), requestedSize, nextCursor, hasMore,
                cut.toEpochMilli());
    }

    private String encodeLast(Object last) {
        if (last instanceof EventView e) {
            return cursors.encode(e.eventTime(), e.rowId());
        }
        ResultView r = (ResultView) last;
        return cursors.encode(r.eventTime(), r.rowId());
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 200);
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
