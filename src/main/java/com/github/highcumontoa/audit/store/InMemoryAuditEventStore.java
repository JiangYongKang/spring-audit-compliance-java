package com.github.highcumontoa.audit.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.highcumontoa.audit.model.AuditEvent;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地事件存储：内存索引 + JSONL 文件追加持久化。
 * 去重键为 eventId；所有读操作基于写时快照，并发写入下结果稳定。
 */
@Component
public class InMemoryAuditEventStore implements AuditEventStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditEventStore.class);
    private static final Comparator<AuditEvent> ORDER =
            Comparator.comparing(AuditEvent::occurredAt).thenComparing(AuditEvent::eventId);

    private final ConcurrentHashMap<String, AuditEvent> events = new ConcurrentHashMap<>();
    private final Path dataFile;
    private final ObjectMapper mapper;

    public InMemoryAuditEventStore(@Value("${audit.data-dir:./audit-data}") String dataDir) {
        this.dataFile = Path.of(dataDir).resolve("audit-events.jsonl");
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
                    AuditEvent e = mapper.readValue(line, AuditEvent.class);
                    events.putIfAbsent(e.eventId(), e);
                } catch (IOException ex) {
                    log.warn("skip corrupted line in {}: {}", dataFile, ex.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("loaded {} audit events from {}", events.size(), dataFile);
    }

    @Override
    public boolean saveIfAbsent(AuditEvent event) {
        // putIfAbsent 保证同一 eventId 只落库一次，重复提交不产生重复记录
        if (events.putIfAbsent(event.eventId(), event) != null) {
            return false;
        }
        appendToFile(event);
        return true;
    }

    private synchronized void appendToFile(AuditEvent event) {
        try {
            Files.createDirectories(dataFile.getParent());
            String line = mapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(dataFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist audit event " + event.eventId(), e);
        }
    }

    @Override
    public Optional<AuditEvent> findById(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    @Override
    public List<AuditEvent> findAllSorted() {
        List<AuditEvent> snapshot = new ArrayList<>(events.values());
        snapshot.sort(ORDER);
        return List.copyOf(snapshot);
    }

    @Override
    public List<AuditEvent> findInWindow(Instant from, Instant to) {
        return findAllSorted().stream()
                .filter(e -> !e.occurredAt().isBefore(from) && !e.occurredAt().isAfter(to))
                .toList();
    }
}
