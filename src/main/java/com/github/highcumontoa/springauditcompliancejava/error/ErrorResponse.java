package com.github.highcumontoa.springauditcompliancejava.error;

import java.time.Instant;

/**
 * 稳定错误响应体：code 为可枚举、可依赖的字符串；message 为可读说明；
 * timestamp 为服务端时间，便于调用方对账。
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, Instant.now());
    }
}
