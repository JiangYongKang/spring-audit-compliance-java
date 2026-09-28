package com.github.highcumontoa.springauditcompliancejava.dto;

/**
 * 审计事件接入请求。字段是否缺失、严重级别/时间是否合法由服务层逐项区分校验。
 *
 * @param eventTime ISO-8601 UTC 时间字符串，如 2026-09-28T10:15:30Z
 */
public record EventIngestRequest(
        String eventId,
        String subject,
        String action,
        String resource,
        String severity,
        String eventTime,
        String detailJson) {
}
