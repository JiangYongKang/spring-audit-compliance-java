package com.github.highcumontoa.audit.service;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.IngestResult;
import com.github.highcumontoa.audit.store.AuditEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件接入：
 * 1) 校验（区分拒绝原因）；
 * 2) 按 eventId 幂等落库（重复提交不产生重复记录，返回 DUPLICATE）；
 * 3) 新事件落库后立即按当前规则评估。
 */
@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditEventValidator validator;
    private final AuditEventStore store;
    private final EvaluationService evaluationService;

    public AuditEventService(AuditEventValidator validator,
                             AuditEventStore store,
                             EvaluationService evaluationService) {
        this.validator = validator;
        this.store = store;
        this.evaluationService = evaluationService;
    }

    public IngestResult ingest(AuditEvent event) {
        var rejected = validator.validate(event);
        if (rejected.isPresent()) {
            IngestResult r = rejected.get();
            log.warn("event rejected input={} reason={} detail={}",
                    event, r.rejectionReason(), r.detail());
            return r;
        }
        boolean inserted = store.saveIfAbsent(event);
        if (!inserted) {
            log.info("duplicate event ignored eventId={}", event.eventId());
            return IngestResult.duplicate();
        }
        evaluationService.evaluate(event);
        log.info("event accepted eventId={} subject={} action={} occurredAt={} severity={}",
                event.eventId(), event.subject(), event.action(), event.occurredAt(), event.severity());
        return IngestResult.accepted();
    }
}
