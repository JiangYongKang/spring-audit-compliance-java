package com.github.highcumontoa.springauditcompliancejava.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.github.highcumontoa.springauditcompliancejava.domain.Decision;
import com.github.highcumontoa.springauditcompliancejava.domain.EvaluationResult;

public interface EvaluationResultRepository extends JpaRepository<EvaluationResult, Long> {

    List<EvaluationResult> findByRunId(String runId);

    /** 全量导出（不分页），同样强制保留截止线，过期数据不会被带出。 */
    @Query("select r from EvaluationResult r "
            + "where (:subject is null or r.subject = :subject) "
            + "and (:decision is null or r.decision = :decision) "
            + "and (:runId is null or r.runId = :runId) "
            + "and r.eventTime >= :start and r.eventTime < :end "
            + "and r.eventTime >= :cutoff "
            + "order by r.eventTime asc, r.id asc")
    List<EvaluationResult> queryForExport(@Param("subject") String subject,
                                          @Param("decision") Decision decision,
                                          @Param("runId") String runId,
                                          @Param("start") Instant start,
                                          @Param("end") Instant end,
                                          @Param("cutoff") Instant cutoff);

    /** 查询首页（无游标）。 */
    @Query("select r from EvaluationResult r "
            + "where (:subject is null or r.subject = :subject) "
            + "and (:decision is null or r.decision = :decision) "
            + "and (:runId is null or r.runId = :runId) "
            + "and r.eventTime >= :start and r.eventTime < :end "
            + "and r.eventTime >= :cutoff "
            + "order by r.eventTime asc, r.id asc")
    List<EvaluationResult> queryFirstPage(@Param("subject") String subject,
                                          @Param("decision") Decision decision,
                                          @Param("runId") String runId,
                                          @Param("start") Instant start,
                                          @Param("end") Instant end,
                                          @Param("cutoff") Instant cutoff,
                                          Pageable pageable);

    /**
     * 键集分页后续页：游标 (cursorTime, cursorId) 为上一页最后一条。
     * 元组比较保证并发写入时不漏读、不重复边界记录。
     */
    @Query("select r from EvaluationResult r "
            + "where (:subject is null or r.subject = :subject) "
            + "and (:decision is null or r.decision = :decision) "
            + "and (:runId is null or r.runId = :runId) "
            + "and r.eventTime >= :start and r.eventTime < :end "
            + "and r.eventTime >= :cutoff "
            + "and (r.eventTime > :cursorTime "
            + "     or (r.eventTime = :cursorTime and r.id > :cursorId)) "
            + "order by r.eventTime asc, r.id asc")
    List<EvaluationResult> queryNextPage(@Param("subject") String subject,
                                         @Param("decision") Decision decision,
                                         @Param("runId") String runId,
                                         @Param("start") Instant start,
                                         @Param("end") Instant end,
                                         @Param("cutoff") Instant cutoff,
                                         @Param("cursorTime") Instant cursorTime,
                                         @Param("cursorId") long cursorId,
                                         Pageable pageable);
}
