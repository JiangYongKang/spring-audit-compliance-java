package com.github.highcumontoa.springauditcompliancejava.repo;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.github.highcumontoa.springauditcompliancejava.domain.RuleLock;

public interface RuleLockRepository extends JpaRepository<RuleLock, String> {

    /** 对规则锁点行取悲观写锁：串行化同一 ruleId 的并发更新。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from RuleLock l where l.ruleId = :ruleId")
    java.util.Optional<RuleLock> lockForUpdate(@Param("ruleId") String ruleId);
}
