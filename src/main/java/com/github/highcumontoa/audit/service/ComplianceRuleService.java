package com.github.highcumontoa.audit.service;

import com.github.highcumontoa.audit.model.ComplianceRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则管理。
 *
 * 并发性：全部规则保存在一个 AtomicReference 指向的不可变 Map 中，
 * 创建/更新通过一次原子引用替换完成。评估线程通过 snapshot() 拿到的
 * 永远是某一版本的完整一致快照，读不到“半更新”状态。
 *
 * 版本：同一 ruleId 的版本号单调递增（新建为 1，每次更新 +1）。
 */
@Service
public class ComplianceRuleService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceRuleService.class);

    private final AtomicReference<Map<String, ComplianceRule>> rulesRef =
            new AtomicReference<>(Map.of());

    public ComplianceRule create(ComplianceRule rule) {
        if (rule == null || rule.ruleId() == null || rule.ruleId().isBlank()) {
            throw new IllegalArgumentException("ruleId is required");
        }
        if (rule.version() != 0) {
            throw new IllegalArgumentException("new rule must not carry a version, got " + rule.version());
        }
        while (true) {
            Map<String, ComplianceRule> current = rulesRef.get();
            if (current.containsKey(rule.ruleId())) {
                throw new IllegalStateException("rule already exists: " + rule.ruleId());
            }
            Map<String, ComplianceRule> next = new TreeMap<>(current);
            next.put(rule.ruleId(), new ComplianceRule(rule.ruleId(), 1,
                    rule.subjectPattern(), rule.action(), rule.minSeverity(),
                    rule.keyword(), rule.description()));
            if (rulesRef.compareAndSet(current, Map.copyOf(next))) {
                log.info("rule created: {} version=1", rule.ruleId());
                return next.get(rule.ruleId());
            }
        }
    }

    public ComplianceRule update(ComplianceRule rule) {
        if (rule == null || rule.ruleId() == null || rule.ruleId().isBlank()) {
            throw new IllegalArgumentException("ruleId is required");
        }
        while (true) {
            Map<String, ComplianceRule> current = rulesRef.get();
            ComplianceRule existing = current.get(rule.ruleId());
            if (existing == null) {
                throw new IllegalStateException("rule not found: " + rule.ruleId());
            }
            long newVersion = existing.version() + 1;
            ComplianceRule updated = new ComplianceRule(rule.ruleId(), newVersion,
                    rule.subjectPattern(), rule.action(), rule.minSeverity(),
                    rule.keyword(), rule.description());
            Map<String, ComplianceRule> next = new TreeMap<>(current);
            next.put(rule.ruleId(), updated);
            if (rulesRef.compareAndSet(current, Map.copyOf(next))) {
                log.info("rule updated: {} version {} -> {}", rule.ruleId(), existing.version(), newVersion);
                return updated;
            }
        }
    }

    public Optional<ComplianceRule> find(String ruleId) {
        return Optional.ofNullable(rulesRef.get().get(ruleId));
    }

    /** 当前规则的不可变一致快照 */
    public List<ComplianceRule> snapshot() {
        return List.copyOf(rulesRef.get().values());
    }
}
