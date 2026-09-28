package com.github.highcumontoa.springauditcompliancejava.service;

import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;
import com.github.highcumontoa.springauditcompliancejava.domain.ComplianceRule;
import com.github.highcumontoa.springauditcompliancejava.domain.Decision;
import com.github.highcumontoa.springauditcompliancejava.domain.Severity;

/**
 * 无状态纯函数判定器。对同一 (规则版本, 事件) 输入永远得到同一结论与依据，
 * 这是历史重放可复现的基础。
 *
 * <p>判定规则：subject/action/resource 三个维度全部匹配（精确相等或 "*" 表示任意），
 * 且事件严重级别不低于规则阈值时，判为 NON_COMPLIANT；否则 COMPLIANT。
 */
public final class RuleEngine {

    private RuleEngine() {
    }

    public static boolean matches(String pattern, String value) {
        return "*".equals(pattern) || pattern.equals(value);
    }

    /**
     * 单规则对单事件的判定。
     */
    public static Evaluation evaluate(ComplianceRule rule, AuditEvent event) {
        Severity threshold = Severity.valueOf(rule.getMinSeverity());
        boolean subjectMatch = matches(rule.getMatchSubject(), event.getSubject());
        boolean actionMatch = matches(rule.getMatchAction(), event.getAction());
        boolean resourceMatch = matches(rule.getMatchResource(),
                event.getResource() == null ? "" : event.getResource());
        boolean severityMatch = event.getSeverity().ordinal() >= threshold.ordinal();

        boolean allDimensions = subjectMatch && actionMatch
                && resourceMatch && severityMatch;
        Decision decision = allDimensions ? Decision.NON_COMPLIANT : Decision.COMPLIANT;

        String rationale = "rule=" + rule.getRuleId() + " v" + rule.getVersion()
                + " | subject[" + rule.getMatchSubject() + " vs " + event.getSubject()
                + "]=" + subjectMatch
                + ", action[" + rule.getMatchAction() + " vs " + event.getAction()
                + "]=" + actionMatch
                + ", resource[" + rule.getMatchResource() + " vs "
                + (event.getResource() == null ? "" : event.getResource())
                + "]=" + resourceMatch
                + ", severity[" + event.getSeverity() + ">=" + threshold + "]="
                + severityMatch
                + " => " + decision;

        return new Evaluation(decision, rationale);
    }

    /**
     * 判定结果（结论 + 判定依据文本）。
     */
    public record Evaluation(Decision decision, String rationale) {
    }
}
