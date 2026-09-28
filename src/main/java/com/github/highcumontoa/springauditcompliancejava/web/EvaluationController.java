package com.github.highcumontoa.springauditcompliancejava.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.springauditcompliancejava.domain.Decision;
import com.github.highcumontoa.springauditcompliancejava.dto.PageResponse;
import com.github.highcumontoa.springauditcompliancejava.dto.ReplayRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.ResultView;
import com.github.highcumontoa.springauditcompliancejava.dto.RunView;
import com.github.highcumontoa.springauditcompliancejava.error.ApiException;
import com.github.highcumontoa.springauditcompliancejava.error.ErrorCode;
import com.github.highcumontoa.springauditcompliancejava.service.EvaluationService;
import com.github.highcumontoa.springauditcompliancejava.service.QueryExportService;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluations;
    private final QueryExportService query;

    public EvaluationController(EvaluationService evaluations, QueryExportService query) {
        this.evaluations = evaluations;
        this.query = query;
    }

    /** 对指定时间窗口执行（重）评估；可在 ruleVersions 指定历史规则版本。 */
    @PostMapping("/runs")
    public RunView replay(@RequestBody ReplayRequest request) {
        return evaluations.evaluateWindow(request);
    }

    /** 查看批次元数据（含规则版本快照）。 */
    @GetMapping("/runs/{runId}")
    public RunView getRun(@PathVariable String runId) {
        return evaluations.getRun(runId);
    }

    /** 按主体/时间/判定结果分页查询，键集分页，遵守保留策略。 */
    @GetMapping("/results")
    public PageResponse<ResultView> list(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return query.queryResults(subject, parseDecision(decision), runId,
                parseTime(start), parseTime(end), cursor, size);
    }

    /**
     * 导出判定结果 CSV。服务端强制保留截止线，过期数据不会出现在文件中。
     */
    @GetMapping("/results/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        String csv = query.exportResultsCsv(subject, parseDecision(decision), runId,
                parseTime(start), parseTime(end));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"evaluation-results.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    private Decision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Decision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID_SEVERITY,
                    "判定结果过滤值非法（合法值: COMPLIANT/NON_COMPLIANT）: " + raw);
        }
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
