package com.github.highcumontoa.audit.service;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.IngestResult;
import com.github.highcumontoa.audit.model.RejectionReason;
import com.github.highcumontoa.audit.model.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 事件校验。拒绝原因可区分：
 * MISSING_FIELD / TIME_ANOMALY / INVALID_SEVERITY。
 */
@Component
public class AuditEventValidator {

    private final Clock clock;
    private final Duration maxFutureSkew;
    private final Duration maxEventAge;

    public AuditEventValidator(Clock clock,
                               @Value("${audit.max-future-skew-minutes:5}") long maxFutureSkewMinutes,
                               @Value("${audit.max-event-age-days:365}") long maxEventAgeDays) {
        this.clock = clock;
        this.maxFutureSkew = Duration.ofMinutes(maxFutureSkewMinutes);
        this.maxEventAge = Duration.ofDays(maxEventAgeDays);
    }

    public Optional<IngestResult> validate(AuditEvent event) {
        if (event == null) {
            return Optional.of(IngestResult.rejected(RejectionReason.MISSING_FIELD, "event is null"));
        }
        if (isBlank(event.eventId())) {
            return Optional.of(IngestResult.rejected(RejectionReason.MISSING_FIELD, "eventId is required"));
        }
        if (isBlank(event.subject())) {
            return Optional.of(IngestResult.rejected(RejectionReason.MISSING_FIELD, "subject is required"));
        }
        if (isBlank(event.action())) {
            return Optional.of(IngestResult.rejected(RejectionReason.MISSING_FIELD, "action is required"));
        }
        if (event.occurredAt() == null) {
            return Optional.of(IngestResult.rejected(RejectionReason.MISSING_FIELD, "occurredAt is required"));
        }
        if (isBlank(event.severity())) {
            return Optional.of(IngestResult.rejected(RejectionReason.MISSING_FIELD, "severity is required"));
        }
        if (!Severity.isValid(event.severity())) {
            return Optional.of(IngestResult.rejected(RejectionReason.INVALID_SEVERITY,
                    "severity must be one of LOW/MEDIUM/HIGH/CRITICAL, got: " + event.severity()));
        }
        Instant now = clock.instant();
        if (event.occurredAt().isAfter(now.plus(maxFutureSkew))) {
            return Optional.of(IngestResult.rejected(RejectionReason.TIME_ANOMALY,
                    "occurredAt is in the future beyond allowed skew: " + event.occurredAt()));
        }
        if (event.occurredAt().isBefore(now.minus(maxEventAge))) {
            return Optional.of(IngestResult.rejected(RejectionReason.TIME_ANOMALY,
                    "occurredAt is older than allowed max age: " + event.occurredAt()));
        }
        return Optional.empty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
