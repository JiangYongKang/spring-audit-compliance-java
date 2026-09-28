package com.github.highcumontoa.springauditcompliancejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;
import com.github.highcumontoa.springauditcompliancejava.domain.Severity;
import com.github.highcumontoa.springauditcompliancejava.dto.EventIngestRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.EventView;
import com.github.highcumontoa.springauditcompliancejava.dto.IngestResponse;
import com.github.highcumontoa.springauditcompliancejava.repo.AuditEventRepository;

import java.time.Instant;

/**
 * 事件接入的事务边界 Bean：先查后插 + flush，唯一约束冲突抛出后整个事务回滚，
 * 由外层 {@link EventIngestService} 在新事务中回查既有记录。
 */
@Component
public class EventIngestTx {

    private static final Logger log = LoggerFactory.getLogger(EventIngestTx.class);

    private final AuditEventRepository events;

    public EventIngestTx(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional
    public IngestResponse persist(EventIngestRequest req, Severity severity,
                                  Instant eventTime, Instant now) {
        var existing = events.findByEventId(req.eventId());
        if (existing.isPresent()) {
            log.info("事件幂等命中: eventId={}, subject={}, eventTime={}",
                    req.eventId(), req.subject(), eventTime);
            return new IngestResponse(true, true, EventView.of(existing.get()));
        }

        AuditEvent saved = events.save(new AuditEvent(
                req.eventId(), req.subject(), req.action(),
                blankToNull(req.resource()), severity, eventTime, now,
                blankToNull(req.detailJson())));
        events.flush();

        log.info("事件已接收: eventId={}, subject={}, action={}, severity={}, eventTime={}, id={}",
                saved.getEventId(), saved.getSubject(), saved.getAction(),
                saved.getSeverity(), saved.getEventTime(), saved.getId());
        return new IngestResponse(true, false, EventView.of(saved));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
