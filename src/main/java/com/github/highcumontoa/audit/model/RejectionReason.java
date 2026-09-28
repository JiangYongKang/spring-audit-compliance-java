package com.github.highcumontoa.audit.model;

/** 事件被拒绝的可区分原因，给调用方稳定的错误结果。 */
public enum RejectionReason {
    /** 缺少关键字段（eventId/subject/action/occurredAt/severity 为空） */
    MISSING_FIELD,
    /** 时间异常（发生时间在未来，或早于系统允许的最远过去时间） */
    TIME_ANOMALY,
    /** 严重级别非法 */
    INVALID_SEVERITY
}
