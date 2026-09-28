package com.github.highcumontoa.springauditcompliancejava.error;

import org.springframework.http.HttpStatus;

/**
 * 调用方可依赖的稳定错误码。同一类输入问题永远返回同一 code 与 HTTP 状态，
 * 不随实现细节或异常类型变化。
 */
public enum ErrorCode {

    // 400 —— 输入可区分的拒绝原因
    VALIDATION_MISSING_FIELD("缺少关键字段", HttpStatus.BAD_REQUEST),
    VALIDATION_INVALID_JSON("请求体不是合法 JSON", HttpStatus.BAD_REQUEST),
    VALIDATION_INVALID_SEVERITY("严重级别非法", HttpStatus.BAD_REQUEST),
    VALIDATION_TIME_MISSING("事件时间缺失", HttpStatus.BAD_REQUEST),
    VALIDATION_TIME_ANOMALY("事件时间异常（过早或超过允许的时钟偏移）", HttpStatus.BAD_REQUEST),
    VALIDATION_BAD_TIME_RANGE("时间窗口非法（start 必须早于 end）", HttpStatus.BAD_REQUEST),
    VALIDATION_INVALID_RULE("规则内容非法", HttpStatus.BAD_REQUEST),
    VALIDATION_INVALID_CURSOR("分页游标非法", HttpStatus.BAD_REQUEST),

    // 404
    RULE_NOT_FOUND("规则不存在", HttpStatus.NOT_FOUND),
    RUN_NOT_FOUND("评估批次不存在", HttpStatus.NOT_FOUND),

    // 409
    RULE_VERSION_CONFLICT("规则版本并发冲突（已被他人更新，请基于最新版本重试）",
            HttpStatus.CONFLICT),

    // 500
    INTERNAL_ERROR("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String defaultMessage;
    private final HttpStatus status;

    ErrorCode(String defaultMessage, HttpStatus status) {
        this.defaultMessage = defaultMessage;
        this.status = status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
