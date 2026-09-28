package com.github.highcumontoa.springauditcompliancejava.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.springauditcompliancejava.dto.EventIngestRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.EventView;
import com.github.highcumontoa.springauditcompliancejava.dto.IngestResponse;
import com.github.highcumontoa.springauditcompliancejava.dto.PageResponse;
import com.github.highcumontoa.springauditcompliancejava.error.ApiException;
import com.github.highcumontoa.springauditcompliancejava.error.ErrorCode;
import com.github.highcumontoa.springauditcompliancejava.service.EventIngestService;
import com.github.highcumontoa.springauditcompliancejava.service.QueryExportService;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventIngestService ingest;
    private final QueryExportService query;

    public EventController(EventIngestService ingest, QueryExportService query) {
        this.ingest = ingest;
        this.query = query;
    }

    /**
     * 接入审计事件。重复提交同一 eventId 返回 200 且 duplicate=true，不产生新记录。
     */
    @PostMapping
    public IngestResponse ingest(@RequestBody EventIngestRequest request) {
        return ingest.ingest(request);
    }

    /**
     * 按主体查询事件（可限定时间窗口），键集分页，自动遵守保留策略。
     */
    @GetMapping
    public PageResponse<EventView> list(
            @RequestParam String subject,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return query.queryEvents(subject, parseTime(start), parseTime(end), cursor, size);
    }

    private Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_BAD_TIME_RANGE,
                    "时间参数格式非法: " + raw);
        }
    }
}
