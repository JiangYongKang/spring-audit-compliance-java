package com.github.highcumontoa.springauditcompliancejava.dto;

import java.util.List;

/**
 * 键集分页响应。nextCursor 为 null 表示无后续页；
 * 游标不透明，调用方原样回传即可。
 */
public record PageResponse<T>(
        List<T> items,
        int pageSize,
        String nextCursor,
        boolean hasMore,
        long retentionCutoff) {
}
