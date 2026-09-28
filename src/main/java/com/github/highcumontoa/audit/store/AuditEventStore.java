package com.github.highcumontoa.audit.store;

import com.github.highcumontoa.audit.model.AuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 审计事件本地存储：线程安全、按 eventId 幂等去重、本地文件持久化。 */
public interface AuditEventStore {

    /** @return true 表示新写入，false 表示重复事件被忽略 */
    boolean saveIfAbsent(AuditEvent event);

    Optional<AuditEvent> findById(String eventId);

    /** 按 (occurredAt, eventId) 升序返回全部事件的快照 */
    List<AuditEvent> findAllSorted();

    /** 查询时间窗口 [from, to] 内的事件，按 (occurredAt, eventId) 升序 */
    List<AuditEvent> findInWindow(Instant from, Instant to);
}
