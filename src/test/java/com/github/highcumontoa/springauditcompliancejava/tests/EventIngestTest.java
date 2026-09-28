package com.github.highcumontoa.springauditcompliancejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springauditcompliancejava.repo.AuditEventRepository;

/**
 * 事件接入：重复提交（串行/并发）不产生重复记录；非法输入按可区分原因拒绝。
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class EventIngestTest {

    private static final Logger log = LoggerFactory.getLogger(EventIngestTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AuditEventRepository eventRepo;

    @Test
    void validEventIsAccepted() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-valid-1",
                "subject", "alice",
                "action", "login",
                "severity", "HIGH",
                "eventTime", Instant.now().toString());
        log.info("用例[合法接入] 输入={}", body);

        var resp = post(body);
        assertEquals(200, resp.status());
        var json = objectMapper.readTree(resp.body());
        assertTrue(json.get("accepted").asBoolean());
        assertFalse(json.get("duplicate").asBoolean());
        assertEquals("alice", json.get("event").get("subject").asText());
        log.info("用例[合法接入] 判定依据=accepted, 响应={}", resp.body());
    }

    @Test
    void duplicateSubmissionIsIdempotent() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-dup-1",
                "subject", "bob",
                "action", "delete",
                "severity", "CRITICAL",
                "eventTime", Instant.now().toString());
        log.info("用例[串行重复提交] 输入={}", body);

        var first = post(body);
        var second = post(body);
        var third = post(body);
        assertEquals(200, second.status());
        assertEquals(200, third.status());

        assertFalse(objectMapper.readTree(first.body()).get("duplicate").asBoolean());
        assertTrue(objectMapper.readTree(second.body()).get("duplicate").asBoolean());
        assertTrue(objectMapper.readTree(third.body()).get("duplicate").asBoolean());
        assertEquals(1, eventRepo.findAllByEventId("evt-dup-1").size());
        log.info("用例[串行重复提交] 判定依据=eventId 唯一约束 + 先查后插，库内记录数=1");
    }

    @Test
    void concurrentDuplicateSubmissionsCreateExactlyOneRow() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-dup-concurrent",
                "subject", "carol",
                "action", "export",
                "severity", "MEDIUM",
                "eventTime", Instant.now().toString());
        log.info("用例[并发重复提交] 输入={} 线程数=8", body);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> post(body).status());
        }
        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get());
        }
        assertEquals(1, eventRepo.findAllByEventId("evt-dup-concurrent").size());
        log.info("用例[并发重复提交] 判定依据=唯一约束兜底+DataIntegrity 回查，库内记录数=1");
    }

    @Test
    void rejectsWithDistinguishableReasons() throws Exception {
        // 缺少关键字段
        var missing = Map.of("eventId", "evt-bad-1", "subject", "x");
        log.info("用例[缺少字段] 输入={}", missing);
        assertEquals("VALIDATION_MISSING_FIELD", post(missing).errorCode());

        // 严重级别非法
        var badSeverity = Map.of(
                "eventId", "evt-bad-2", "subject", "x", "action", "y",
                "severity", "URGENT", "eventTime", Instant.now().toString());
        log.info("用例[非法级别] 输入={}", badSeverity);
        assertEquals("VALIDATION_INVALID_SEVERITY", post(badSeverity).errorCode());

        // 时间异常：未来时间
        var futureTime = Map.of(
                "eventId", "evt-bad-3", "subject", "x", "action", "y",
                "severity", "LOW",
                "eventTime", Instant.now().plusSeconds(3600).toString());
        log.info("用例[未来时间] 输入={}", futureTime);
        assertEquals("VALIDATION_TIME_ANOMALY", post(futureTime).errorCode());

        // 时间异常：远超保留期
        var ancient = Map.of(
                "eventId", "evt-bad-4", "subject", "x", "action", "y",
                "severity", "LOW",
                "eventTime", Instant.parse("2000-01-01T00:00:00Z").toString());
        log.info("用例[过早时间] 输入={}", ancient);
        assertEquals("VALIDATION_TIME_ANOMALY", post(ancient).errorCode());

        // 时间缺失单独可区分
        var noTime = Map.of(
                "eventId", "evt-bad-5", "subject", "x", "action", "y", "severity", "LOW");
        log.info("用例[时间缺失] 输入={}", noTime);
        assertEquals("VALIDATION_TIME_MISSING", post(noTime).errorCode());
        log.info("用例[非法输入] 判定依据=逐项校验并返回稳定错误码");
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        log.info("用例[非法JSON] 输入={{not-json}");
        int status = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andReturn().getResponse().getStatus();
        assertEquals(400, status);
        log.info("用例[非法JSON] 判定依据=HttpMessageNotReadable 映射 VALIDATION_INVALID_JSON");
    }

    private record Resp(int status, String body, String errorCode) {
    }

    private Resp post(Object body) throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        int status = result.getResponse().getStatus();
        String text = result.getResponse().getContentAsString();
        String code = null;
        if (status >= 400) {
            code = objectMapper.readTree(text).get("code").asText();
        }
        return new Resp(status, text, code);
    }
}
