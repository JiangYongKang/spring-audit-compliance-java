package com.github.highcumontoa.audit.model;

/**
 * 合规规则（不可变，更新即产生新版本）。
 *
 * @param ruleId       规则标识
 * @param version      规则版本（同一 ruleId 单调递增，从 1 开始）
 * @param subjectPattern 匹配主体的子串（null/空 表示匹配全部）
 * @param action       匹配的操作类型（null/空 表示匹配全部）
 * @param minSeverity  触发判定的最低严重级别（null 表示不过滤）
 * @param keyword      事件属性中必须出现的关键字（null/空 表示不要求）
 * @param description  规则描述
 */
public record ComplianceRule(
        String ruleId,
        long version,
        String subjectPattern,
        String action,
        Severity minSeverity,
        String keyword,
        String description) {

    public ComplianceRule withVersion(long newVersion) {
        return new ComplianceRule(ruleId, newVersion, subjectPattern, action, minSeverity, keyword, description);
    }
}
