package com.github.highcumontoa.springauditcompliancejava.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Optional<AuditEvent> findByEventId(String eventId);

    /** 断言用：理论上至多一条（唯一约束），用 List 形式便于断言计数。 */
    List<AuditEvent> findAllByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /**
     * 评估时按事件时间窗口扫描事件（start 含、end 不含），
     * 以 (eventTime, id) 全序确定，保证重放处理顺序确定可复现。
     */
    @Query("select e from AuditEvent e where e.eventTime >= :start and e.eventTime < :end "
            + "order by e.eventTime asc, e.id asc")
    List<AuditEvent> findWindow(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * 键集分页：游标为上一页最后一条的 (eventTime, id)。
     * 仅返回事件时间不早于保留截止线 cutoff 的数据（保留策略强制）。
     * 用元组比较保证同一毫秒的事件也不漏读、不重复。
     */
    @Query("select e from AuditEvent e "
            + "where e.subject = :subject "
            + "and e.eventTime >= :start and e.eventTime < :end "
            + "and e.eventTime >= :cutoff "
            + "and (e.eventTime > :cursorTime "
            + "     or (e.eventTime = :cursorTime and e.id > :cursorId)) "
            + "order by e.eventTime asc, e.id asc")
    List<AuditEvent> findPageBySubject(@Param("subject") String subject,
                                       @Param("start") Instant start,
                                       @Param("end") Instant end,
                                       @Param("cutoff") Instant cutoff,
                                       @Param("cursorTime") Instant cursorTime,
                                       @Param("cursorId") long cursorId,
                                       Pageable pageable);

    /**
     * 键集分页首页：无游标时用最小可能值初始化。
     */
    @Query("select e from AuditEvent e "
            + "where e.subject = :subject "
            + "and e.eventTime >= :start and e.eventTime < :end "
            + "and e.eventTime >= :cutoff "
            + "order by e.eventTime asc, e.id asc")
    List<AuditEvent> findFirstPageBySubject(@Param("subject") String subject,
                                            @Param("start") Instant start,
                                            @Param("end") Instant end,
                                            @Param("cutoff") Instant cutoff,
                                            Pageable pageable);
}
