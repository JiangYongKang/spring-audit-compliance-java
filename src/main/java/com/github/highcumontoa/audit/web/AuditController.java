package com.github.highcumontoa.audit.web;

import com.github.highcumontoa.audit.model.AuditEvent;
import com.github.highcumontoa.audit.model.ComplianceRule;
import com.github.highcumontoa.audit.model.EvaluationDecision;
import com.github.highcumontoa.audit.model.IngestResult;
import com.github.highcumontoa.audit.service.AuditEventService;
import com.github.highcumontoa.audit.service.ComplianceRuleService;
import com.github.highcumontoa.audit.service.EvaluationService;
import com.github.highcumontoa.audit.service.QueryExportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** REST 入口：错误结果稳定（拒绝 422 且带 reason；非法参数 400）。 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private final AuditEventService eventService;
    private final ComplianceRuleService ruleService;
    private final EvaluationService evaluationService;
    private final QueryExportService queryExportService;

    public AuditController(AuditEventService eventService,
                           ComplianceRuleService ruleService,
                           EvaluationService evaluationService,
                           QueryExportService queryExportService) {
        this.eventService = eventService;
        this.ruleService = ruleService;
        this.evaluationService = evaluationService;
        this.queryExportService = queryExportService;
    }

    @PostMapping("/events")
    public ResponseEntity<IngestResult> ingest(@RequestBody AuditEvent event) {
        IngestResult result = eventService.ingest(event);
        return switch (result.status()) {
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok(result);
            case REJECTED -> ResponseEntity.unprocessableEntity().body(result);
        };
    }

    @PostMapping("/rules")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public ComplianceRule createRule(@RequestBody ComplianceRule rule) {
        return ruleService.create(rule);
    }

    @PutMapping("/rules/{ruleId}")
    public ComplianceRule updateRule(@PathVariable String ruleId, @RequestBody ComplianceRule rule) {
        if (!ruleId.equals(rule.ruleId())) {
            throw new IllegalArgumentException("ruleId in path and body must match");
        }
        return ruleService.update(rule);
    }

    @GetMapping("/rules")
    public List<ComplianceRule> rules() {
        return ruleService.snapshot();
    }

    @PostMapping("/replay")
    public Map<String, Long> replay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return Map.of("replayRunId", evaluationService.replay(from, to));
    }

    @GetMapping("/events/{eventId}/evaluations")
    public Object evaluations(@PathVariable String eventId) {
        return evaluationService.recordsOf(eventId);
    }

    @GetMapping("/events")
    public QueryExportService.Page query(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) EvaluationDecision decision,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String cursor) {
        return queryExportService.query(subject, from, to, decision, pageSize, cursor);
    }

    @GetMapping("/export")
    public List<AuditEvent> export(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queryExportService.export(subject, from, to, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
