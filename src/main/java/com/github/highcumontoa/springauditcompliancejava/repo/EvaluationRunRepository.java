package com.github.highcumontoa.springauditcompliancejava.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.highcumontoa.springauditcompliancejava.domain.EvaluationRun;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, Long> {

    Optional<EvaluationRun> findByRunId(String runId);

    boolean existsByRunId(String runId);
}
