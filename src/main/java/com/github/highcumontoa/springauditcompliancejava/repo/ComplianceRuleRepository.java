package com.github.highcumontoa.springauditcompliancejava.repo;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.github.highcumontoa.springauditcompliancejava.domain.ComplianceRule;

public interface ComplianceRuleRepository extends JpaRepository<ComplianceRule, Long> {

    String RULE_CHAIN_ORDER = " order by r.version asc";

    /**
     * 读取某规则的完整版本链并加悲观写锁。
     * 规则更新事务调用：把"停用旧版本 + 插入新版本"串行化，
     * 杜绝两个并发更新产生相同版本号或两个 active 版本。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ComplianceRule r where r.ruleId = :ruleId" + RULE_CHAIN_ORDER)
    List<ComplianceRule> findChainForUpdate(@Param("ruleId") String ruleId);

    /**
     * 评估事务内读取当前生效版本。读到的是不可变版本行，
     * 与正在进行的更新事务互不影响（读已提交下只会看到完整的旧版或新版）。
     */
    Optional<ComplianceRule> findByRuleIdAndActiveTrue(String ruleId);

    List<ComplianceRule> findByActiveTrueOrderByRuleIdAsc();

    @Query("select r from ComplianceRule r where r.ruleId = :ruleId and r.version = :version")
    Optional<ComplianceRule> findVersion(@Param("ruleId") String ruleId,
                                         @Param("version") int version);

    @Query("select r from ComplianceRule r where r.ruleId = :ruleId" + RULE_CHAIN_ORDER)
    List<ComplianceRule> findChain(@Param("ruleId") String ruleId);
}
