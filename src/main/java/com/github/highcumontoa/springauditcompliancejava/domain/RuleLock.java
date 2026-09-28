package com.github.highcumontoa.springauditcompliancejava.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 规则锁点（每规则一行，ruleId 即主键）。
 *
 * <p>版本链上的悲观行锁无法阻止"插入新版本"的幻影写入，因此并发更新需要一个
 * 恒定存在的锁点：更新事务先对该行取悲观写锁，从而对同一 ruleId 的更新完全串行化。
 * 评估事务从不访问该表，只读不可变版本行，故评估不会被更新阻塞。
 */
@Entity
@Table(name = "rule_lock")
public class RuleLock {

    @Id
    private String ruleId;

    protected RuleLock() {
    }

    public RuleLock(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleId() {
        return ruleId;
    }
}
