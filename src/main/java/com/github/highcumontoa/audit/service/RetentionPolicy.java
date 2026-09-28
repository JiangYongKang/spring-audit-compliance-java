package com.github.highcumontoa.audit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 数据保留策略：默认保留 90 天。
 * 导出时只要事件 occurredAt 早于 cutoff，一律禁止带出。
 */
@Component
public class RetentionPolicy {

    private final Duration retention;

    public RetentionPolicy(@Value("${audit.retention-days:90}") long retentionDays) {
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("audit.retention-days must be positive");
        }
        this.retention = Duration.ofDays(retentionDays);
    }

    public Duration retention() {
        return retention;
    }

    public Instant cutoff(Instant now) {
        return now.minus(retention);
    }

    public boolean isExpired(Instant occurredAt, Instant now) {
        return occurredAt.isBefore(cutoff(now));
    }
}
