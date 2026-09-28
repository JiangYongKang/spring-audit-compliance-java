package com.github.highcumontoa.springauditcompliancejava.dto;

import java.time.Instant;

import com.github.highcumontoa.springauditcompliancejava.domain.EvaluationRun;

public record RunView(
        String runId,
        String mode,
        Instant windowStart,
        Instant windowEnd,
        String ruleSnapshotJson,
        int ruleCount,
        String status,
        int eventCount,
        int nonCompliantCount,
        Instant startedAt,
        Instant completedAt,
        String message) {

    public static RunView of(EvaluationRun r) {
        return new RunView(r.getRunId(), r.getMode(), r.getWindowStart(), r.getWindowEnd(),
                r.getRuleSnapshotJson(), r.getRuleCount(), r.getStatus().name(),
                r.getEventCount(), r.getNonCompliantCount(), r.getStartedAt(),
                r.getCompletedAt(), r.getMessage());
    }
}
