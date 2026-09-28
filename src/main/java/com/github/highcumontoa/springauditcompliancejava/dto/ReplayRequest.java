package com.github.highcumontoa.springauditcompliancejava.dto;

import java.util.Map;

/**
 * 历史重放（或即时评估）请求。
 *
 * @param windowStart ISO 时间，含
 * @param windowEnd   ISO 时间，不含
 * @param ruleVersions 可选：指定每个 ruleId 使用的版本号；为空时使用当前生效版本。
 *                     指定版本使同窗口重放逐位可复现。
 * @param runId        可选：调用方提供的幂等批次标识，重复提交返回同一批次。
 */
public record ReplayRequest(
        String windowStart,
        String windowEnd,
        Map<String, Integer> ruleVersions,
        String runId) {
}
