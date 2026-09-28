package com.github.highcumontoa.springauditcompliancejava.domain;

/**
 * 评估批次状态。批次在单事务内完成时只可能落为 COMPLETED/FAILED，
 * 外部永远看不到正在写入一半结果的 RUNNING 批次。
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
