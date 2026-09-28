package com.github.highcumontoa.springauditcompliancejava.dto;

import java.time.Instant;

import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;

/**
 * 事件视图。rowId 为库内单调序号，仅用于键集分页游标，不是业务标识。
 */
public record EventView(
        long rowId,
        String eventId,
        String subject,
        String action,
        String resource,
        String severity,
        Instant eventTime,
        Instant receivedAt,
        String detailJson) {

    public static EventView of(AuditEvent e) {
        return new EventView(e.getId(), e.getEventId(), e.getSubject(), e.getAction(),
                e.getResource(), e.getSeverity().name(), e.getEventTime(),
                e.getReceivedAt(), e.getDetailJson());
    }
}
