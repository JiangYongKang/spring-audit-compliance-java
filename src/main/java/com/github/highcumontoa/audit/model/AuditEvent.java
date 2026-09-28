package com.github.highcumontoa.audit.model;

import java.time.Instant;
import java.util.Map;

/**
 * 审计事件。eventId 由调用方提供，作为幂等去重键。
 *
 * @param eventId    事件唯一标识（去重键）
 * @param subject    操作主体
 * @param action     操作类型
 * @param occurredAt 事件发生时间
 * @param severity   严重级别（字符串，便于在校验层区分非法值）
 * @param attributes 扩展属性
 */
public record AuditEvent(
        String eventId,
        String subject,
        String action,
        Instant occurredAt,
        String severity,
        Map<String, String> attributes) {
}
