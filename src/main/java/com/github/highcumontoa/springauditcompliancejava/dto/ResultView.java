package com.github.highcumontoa.springauditcompliancejava.dto;

import java.time.Instant;

import com.github.highcumontoa.springauditcompliancejava.domain.EvaluationResult;

/**
 * 判定结果视图：含判定所用的规则版本与判定依据。
 */
public record ResultView(
        long rowId,
        String runId,
        String eventId,
        String ruleId,
        int ruleVersion,
        String ruleName,
        String subject,
        Instant eventTime,
        String severity,
        String decision,
        String rationale) {

    public static ResultView of(EvaluationResult r) {
        return new ResultView(r.getId(), r.getRunId(), r.getEventId(), r.getRuleId(),
                r.getRuleVersion(), r.getRuleName(), r.getSubject(), r.getEventTime(),
                r.getSeverity(), r.getDecision().name(), r.getRationale());
    }
}
