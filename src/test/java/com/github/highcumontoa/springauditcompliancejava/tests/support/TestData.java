package com.github.highcumontoa.springauditcompliancejava.tests.support;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.github.highcumontoa.springauditcompliancejava.domain.AuditEvent;
import com.github.highcumontoa.springauditcompliancejava.domain.Severity;
import com.github.highcumontoa.springauditcompliancejava.repo.AuditEventRepository;

/**
 * 测试辅助：直接向事件表写入任意时间的事件，绕过接入接口的时间异常校验，
 * 以便构造历史窗口重放、保留期过期等场景。
 */
@Component
public class TestData {

    private final AuditEventRepository events;

    public TestData(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional
    public AuditEvent insert(String eventId, String subject, String action,
                             String resource, Severity severity, Instant eventTime) {
        return events.saveAndFlush(new AuditEvent(eventId, subject, action, resource,
                severity, eventTime, eventTime, null));
    }
}
