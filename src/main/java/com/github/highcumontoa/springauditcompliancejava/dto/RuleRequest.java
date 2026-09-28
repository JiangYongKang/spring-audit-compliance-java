package com.github.highcumontoa.springauditcompliancejava.dto;

/**
 * 规则新建/更新载荷。匹配字段为精确值或 "*"（匹配任意）。
 */
public record RuleRequest(
        String ruleId,
        String name,
        String description,
        String matchSubject,
        String matchAction,
        String matchResource,
        String minSeverity) {
}
