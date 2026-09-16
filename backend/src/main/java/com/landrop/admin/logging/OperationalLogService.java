package com.landrop.admin.logging;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ring-buffer operational logging service storing up to 1000 in-memory operational audit entries.
 * Fast, thread-safe, and sanitized.
 */
public class OperationalLogService {

    private static final int DEFAULT_CAPACITY = 1000;
    private static final OperationalLogService INSTANCE = new OperationalLogService();

    private final int capacity;
    private final Deque<OperationalLogEntry> buffer;
    private final AtomicLong idCounter = new AtomicLong(1);

    public OperationalLogService(int capacity) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.buffer = new ArrayDeque<>(this.capacity);
    }

    public OperationalLogService() {
        this(DEFAULT_CAPACITY);
    }

    public static OperationalLogService getInstance() {
        return INSTANCE;
    }

    public synchronized OperationalLogEntry record(String eventType, String level, String actor, String target, String details, String ipAddress) {
        long id = idCounter.getAndIncrement();
        String safeDetails = sanitize(details);
        OperationalLogEntry entry = new OperationalLogEntry(
            id,
            Instant.now(),
            eventType != null ? eventType : "UNKNOWN",
            level != null ? level.toUpperCase() : "INFO",
            actor != null ? actor : "SYSTEM",
            target,
            safeDetails,
            ipAddress
        );

        if (buffer.size() >= capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(entry);
        return entry;
    }

    public OperationalLogEntry info(String eventType, String actor, String target, String details) {
        return record(eventType, "INFO", actor, target, details, null);
    }

    public OperationalLogEntry warn(String eventType, String actor, String target, String details) {
        return record(eventType, "WARN", actor, target, details, null);
    }

    public OperationalLogEntry error(String eventType, String actor, String target, String details) {
        return record(eventType, "ERROR", actor, target, details, null);
    }

    public synchronized List<OperationalLogEntry> getLogs(int limit, int offset, String levelFilter, String typeFilter, String search) {
        int maxLimit = Math.min(Math.max(1, limit), 500);
        int skip = Math.max(0, offset);

        // Buffer stores oldest to newest; return reverse order (most recent first)
        List<OperationalLogEntry> all = new ArrayList<>(buffer);
        java.util.Collections.reverse(all);

        List<OperationalLogEntry> filtered = new ArrayList<>();
        int count = 0;
        int skipped = 0;

        for (OperationalLogEntry e : all) {
            if (levelFilter != null && !levelFilter.isBlank() && !"ALL".equalsIgnoreCase(levelFilter)) {
                if (!e.level().equalsIgnoreCase(levelFilter)) continue;
            }
            if (typeFilter != null && !typeFilter.isBlank() && !"ALL".equalsIgnoreCase(typeFilter)) {
                if (!e.eventType().equalsIgnoreCase(typeFilter)) continue;
            }
            if (search != null && !search.isBlank()) {
                String q = search.toLowerCase();
                boolean match = (e.eventType() != null && e.eventType().toLowerCase().contains(q))
                             || (e.actor() != null && e.actor().toLowerCase().contains(q))
                             || (e.target() != null && e.target().toLowerCase().contains(q))
                             || (e.details() != null && e.details().toLowerCase().contains(q));
                if (!match) continue;
            }

            if (skipped < skip) {
                skipped++;
                continue;
            }

            filtered.add(e);
            count++;
            if (count >= maxLimit) {
                break;
            }
        }

        return filtered;
    }

    public synchronized int size() {
        return buffer.size();
    }

    public synchronized void clear() {
        buffer.clear();
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("(?i)(password|token|secret|authorization)=[^,\\s&]+", "$1=***");
    }
}
