package com.github.highcumontoa.audit.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.highcumontoa.audit.model.EvaluationRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 判定记录本地存储：内存列表 + JSONL 追加。每条记录含规则版本，重放历史可追溯。 */
@Component
public class EvaluationRecordStore {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRecordStore.class);
    private static final Comparator<EvaluationRecord> ORDER =
            Comparator.comparing(EvaluationRecord::evaluatedAt)
                    .thenComparing(EvaluationRecord::eventId)
                    .thenComparingLong(EvaluationRecord::replayRunId);

    private final CopyOnWriteArrayList<EvaluationRecord> records = new CopyOnWriteArrayList<>();
    private final Path dataFile;
    private final ObjectMapper mapper;

    public EvaluationRecordStore(@Value("${audit.data-dir:./audit-data}") String dataDir) {
        this.dataFile = Path.of(dataDir).resolve("evaluation-records.jsonl");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try (var lines = Files.lines(dataFile)) {
            lines.filter(l -> !l.isBlank()).forEach(line -> {
                try {
                    records.add(mapper.readValue(line, EvaluationRecord.class));
                } catch (IOException ex) {
                    log.warn("skip corrupted line in {}: {}", dataFile, ex.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("loaded {} evaluation records from {}", records.size(), dataFile);
    }

    public void append(EvaluationRecord record) {
        records.add(record);
        persist(record);
    }

    private synchronized void persist(EvaluationRecord record) {
        try {
            Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist evaluation record", e);
        }
    }

    public List<EvaluationRecord> findByEventId(String eventId) {
        return records.stream()
                .filter(r -> r.eventId().equals(eventId))
                .sorted(ORDER)
                .toList();
    }

    /** 某事件最新一次判定记录（供查询按判定结果过滤） */
    public EvaluationRecord latestOf(String eventId) {
        return records.stream()
                .filter(r -> r.eventId().equals(eventId))
                .max(Comparator.comparingLong(EvaluationRecord::replayRunId)
                        .thenComparing(EvaluationRecord::evaluatedAt))
                .orElse(null);
    }

    public List<EvaluationRecord> all() {
        return List.copyOf(records);
    }
}
