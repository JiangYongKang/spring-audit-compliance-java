package com.github.highcumontoa.springauditcompliancejava.dto;

/**
 * 事件接入结果。duplicate=true 表示幂等命中已有记录，不会新生成一条记录。
 */
public record IngestResponse(boolean accepted, boolean duplicate, EventView event) {
}
