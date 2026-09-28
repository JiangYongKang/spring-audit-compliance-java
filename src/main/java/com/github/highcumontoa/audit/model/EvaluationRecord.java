package com.github.highcumontoa.audit.model;

import java.time.Instant;

/**
 * 一次判定的可追溯记录：能看出使用了哪个规则版本以及判定依据。
 *
 * @param eventId     被判定的事件
 * @param ruleId      命中的规则（PASS 时为 null）
 * @param ruleVersion 判定使用的规则版本（PASS 时为评估时的规则快照版本，0 表示无规则）
 * @param decision    判定结果
 * @param basis       判定依据（人可读的匹配说明）
 * @param evaluatedAt 判定时间
 * @param replayRunId 重放批次标识（首次评估与每次重放各有一个批次号）
 */
public record EvaluationRecord(
        String eventId,
        String ruleId,
        long ruleVersion,
        EvaluationDecision decision,
        String basis,
        Instant evaluatedAt,
        long replayRunId) {
}
