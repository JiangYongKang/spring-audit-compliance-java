package com.github.highcumontoa.springauditcompliancejava.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.springauditcompliancejava.config.RetentionProperties;
import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;
import com.github.highcumontoa.springauditcompliancejava.domain.Severity;
import com.github.highcumontoa.springauditcompliancejava.dto.EventIngestRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.EventView;
import com.github.highcumontoa.springauditcompliancejava.dto.IngestResponse;
import com.github.highcumontoa.springauditcompliancejava.error.ApiException;
import com.github.highcumontoa.springauditcompliancejava.error.ErrorCode;
import com.github.highcumontoa.springauditcompliancejava.repo.AuditEventRepository;

@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    private final AuditEventRepository events;
    private final EventIngestTx tx;
    private final RetentionProperties retention;

    public EventIngestService(AuditEventRepository events, EventIngestTx tx,
                              RetentionProperties retention) {
        this.events = events;
        this.tx = tx;
        this.retention = retention;
    }

    /**
     * 接入一个审计事件。校验在事务外完成（纯计算，失败直接返回稳定错误码）；
     * 持久化在独立事务 Bean 中执行。并发同 eventId 提交时内部事务因唯一约束回滚，
     * 外层在新事务中回查既有记录，保证只产生一条记录。
     */
    public IngestResponse ingest(EventIngestRequest req) {
        if (req == null) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD, "请求体为空");
        }
        requireText(req.eventId(), "eventId");
        requireText(req.subject(), "subject");
        requireText(req.action(), "action");

        Severity severity = parseSeverity(req.severity());
        Instant eventTime = parseEventTime(req.eventTime());
        Instant now = Instant.now();
        validateTimeRange(eventTime, now);

        try {
            return tx.persist(req, severity, eventTime, now);
        } catch (DataIntegrityViolationException duplicateRace) {
            AuditEvent raced = events.findByEventId(req.eventId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "并发写入冲突且无法回查既有事件"));
            log.info("事件并发幂等命中: eventId={}", req.eventId());
            return new IngestResponse(true, true, EventView.of(raced));
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD,
                    "缺少关键字段: " + field);
        }
    }

    private Severity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD,
                    "缺少关键字段: severity");
        }
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID_SEVERITY,
                    "严重级别非法: " + raw + "（合法值: LOW/MEDIUM/HIGH/CRITICAL）");
        }
    }

    private Instant parseEventTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_TIME_MISSING,
                    "事件时间缺失: eventTime");
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_TIME_ANOMALY,
                    "事件时间格式非法（需 ISO-8601，如 2026-09-28T10:15:30Z）: " + raw);
        }
    }

    private void validateTimeRange(Instant eventTime, Instant now) {
        Instant skew = now.plusSeconds(retention.maxClockSkewMinutes() * 60L);
        Instant earliest = now.minusSeconds(retention.retentionDays() * 86_400L);
        if (eventTime.isAfter(skew)) {
            throw new ApiException(ErrorCode.VALIDATION_TIME_ANOMALY,
                    "事件时间晚于当前时间且超过允许时钟偏移 "
                            + retention.maxClockSkewMinutes() + " 分钟: " + eventTime);
        }
        if (eventTime.isBefore(earliest)) {
            throw new ApiException(ErrorCode.VALIDATION_TIME_ANOMALY,
                    "事件时间早于保留窗口起点（" + retention.retentionDays()
                            + " 天之前）: " + eventTime);
        }
    }
}
