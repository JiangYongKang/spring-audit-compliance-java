package com.github.highcumontoa.springauditcompliancejava.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.highcumontoa.springauditcompliancejava.domain.ComplianceRule;
import com.github.highcumontoa.springauditcompliancejava.domain.RuleLock;
import com.github.highcumontoa.springauditcompliancejava.domain.Severity;
import com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RuleView;
import com.github.highcumontoa.springauditcompliancejava.error.ApiException;
import com.github.highcumontoa.springauditcompliancejava.error.ErrorCode;
import com.github.highcumontoa.springauditcompliancejava.repo.RuleLockRepository;
import com.github.highcumontoa.springauditcompliancejava.repo.ComplianceRuleRepository;

@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final ComplianceRuleRepository rules;
    private final RuleLockRepository locks;

    public RuleService(ComplianceRuleRepository rules, RuleLockRepository locks) {
        this.rules = rules;
        this.locks = locks;
    }

    @Transactional
    public RuleView create(RuleRequest req) {
        validate(req);
        String ruleId = (req.ruleId() == null || req.ruleId().isBlank())
                ? "rule-" + UUID.randomUUID()
                : req.ruleId().trim();
        if (locks.existsById(ruleId)) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID_RULE,
                    "规则已存在，请使用更新接口: " + ruleId);
        }
        // 锁点与 v1 同事务插入；锁点唯一约束兜底并发创建。
        locks.saveAndFlush(new RuleLock(ruleId));
        ComplianceRule v1 = new ComplianceRule(ruleId, 1, req.name().trim(),
                req.description(), normalize(req.matchSubject()), normalize(req.matchAction()),
                normalize(req.matchResource()), normalizeSeverity(req.minSeverity()),
                true, Instant.now());
        rules.save(v1);
        log.info("规则已创建: ruleId={}, version=1, minSeverity={}",
                ruleId, v1.getMinSeverity());
        return RuleView.of(v1);
    }

    /**
     * 更新规则：先对恒定锁点行取悲观写锁（同一 ruleId 的更新因此完全串行），
     * 再"停用旧版本 + 插入新版本"。锁获取失败映射为 409，调用方可基于最新版本重试。
     */
    @Transactional
    public RuleView update(String ruleId, RuleRequest req) {
        validate(req);
        try {
            RuleLock lockPoint = locks.lockForUpdate(ruleId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RULE_NOT_FOUND,
                            "规则不存在: " + ruleId));
            lockPoint.getRuleId();

            List<ComplianceRule> chain = rules.findChain(ruleId);
            if (chain.isEmpty()) {
                throw new ApiException(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
            }
            int nextVersion = chain.stream().mapToInt(ComplianceRule::getVersion).max().orElse(0) + 1;
            for (ComplianceRule r : chain) {
                if (r.isActive()) {
                    r.deactivate();
                }
            }
            ComplianceRule next = new ComplianceRule(ruleId, nextVersion, req.name().trim(),
                    req.description(), normalize(req.matchSubject()), normalize(req.matchAction()),
                    normalize(req.matchResource()), normalizeSeverity(req.minSeverity()),
                    true, Instant.now());
            rules.save(next);
            rules.flush();
            log.info("规则已更新: ruleId={}, newVersion={}（旧版本均停用，仍保留用于重放）",
                    ruleId, nextVersion);
            return RuleView.of(next);
        } catch (ApiException ex) {
            throw ex;
        } catch (CannotAcquireLockException ex) {
            throw new ApiException(ErrorCode.RULE_VERSION_CONFLICT,
                    "规则正被并发更新，请稍后基于最新版本重试: " + ruleId);
        }
    }

    @Transactional(readOnly = true)
    public List<RuleView> listActive() {
        return rules.findByActiveTrueOrderByRuleIdAsc().stream().map(RuleView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<RuleView> history(String ruleId) {
        List<ComplianceRule> chain = rules.findChain(ruleId);
        if (chain.isEmpty()) {
            throw new ApiException(ErrorCode.RULE_NOT_FOUND, "规则不存在: " + ruleId);
        }
        return chain.stream().map(RuleView::of).toList();
    }

    private String normalize(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        return p.isEmpty() ? "*" : p;
    }

    private String normalizeSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD,
                    "缺少关键字段: minSeverity");
        }
        try {
            return Severity.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID_SEVERITY,
                    "规则最低严重级别非法: " + raw);
        }
    }

    private void validate(RuleRequest req) {
        if (req == null) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD, "请求体为空");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_MISSING_FIELD,
                    "缺少关键字段: name");
        }
        normalizeSeverity(req.minSeverity());
        normalize(req.matchSubject());
        normalize(req.matchAction());
        normalize(req.matchResource());
    }
}
