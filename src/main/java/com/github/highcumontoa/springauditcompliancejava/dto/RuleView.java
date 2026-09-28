package com.github.highcumontoa.springauditcompliancejava.dto;

import java.time.Instant;

import com.github.highcumontoa.springauditcompliancejava.domain.ComplianceRule;

public record RuleView(
        String ruleId,
        int version,
        String name,
        String description,
        String matchSubject,
        String matchAction,
        String matchResource,
        String minSeverity,
        boolean active,
        Instant createdAt) {

    public static RuleView of(ComplianceRule r) {
        return new RuleView(r.getRuleId(), r.getVersion(), r.getName(), r.getDescription(),
                r.getMatchSubject(), r.getMatchAction(), r.getMatchResource(),
                r.getMinSeverity(), r.isActive(), r.getCreatedAt());
    }
}
