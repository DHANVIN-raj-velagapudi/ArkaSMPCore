package com.arka.smp.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Cooldowns {
    private final Map<String, Long> until = new HashMap<>();

    private static String key(String type, UUID id) {
        return type + ":" + id;
    }

    /** Seconds left (rounded up), 0 if ready. */
    public long remaining(String type, UUID id) {
        String k = key(type, id);
        Long end = until.get(k);
        if (end == null) return 0;
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            until.remove(k);
            return 0;
        }
        return (left + 999) / 1000;
    }

    public void start(String type, UUID id, int seconds) {
        if (seconds > 0) until.put(key(type, id), System.currentTimeMillis() + seconds * 1000L);
    }

    public void clear(UUID id) {
        until.keySet().removeIf(k -> k.endsWith(id.toString()));
    }

    /** Drop expired entries (called every few minutes). */
    public void prune() {
        long now = System.currentTimeMillis();
        until.values().removeIf(t -> t <= now);
    }
}
