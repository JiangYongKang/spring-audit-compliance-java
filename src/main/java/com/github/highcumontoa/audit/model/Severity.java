package com.github.highcumontoa.audit.model;

/** 合法严重级别。非法输入（如 "FATAL"）会被校验层拒绝。 */
public enum Severity {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (Severity s : values()) {
            if (s.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
