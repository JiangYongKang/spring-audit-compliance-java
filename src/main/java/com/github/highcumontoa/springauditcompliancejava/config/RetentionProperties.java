package com.github.highcumontoa.springauditcompliancejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据保留与时间校验策略（前缀 app.retention）。
 *
 * @param retentionDays       保留天数：导出只允许带出 eventTime 在 [now-retentionDays, now]
 *                            范围内的判定/事件，过期数据在数据访问层即被过滤。
 * @param maxClockSkewMinutes 接入时允许的事件时间未来偏移（分钟），超过即判为时间异常。
 */
@ConfigurationProperties(prefix = "app.retention")
public record RetentionProperties(int retentionDays, int maxClockSkewMinutes) {

    public RetentionProperties {
        if (retentionDays <= 0) {
            retentionDays = 90;
        }
        if (maxClockSkewMinutes <= 0) {
            maxClockSkewMinutes = 5;
        }
    }
}
