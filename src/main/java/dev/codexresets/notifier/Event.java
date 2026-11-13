package dev.codexresets.notifier;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

record Event(String id, String resetType, Instant announcedAt, String text,
             String sourceUrl, boolean scheduled, Instant scheduledFor, Watch watch) {
    record Watch(String level, Integer chancePercent, String forecastWindow, Instant expiresAt) {}

    Event(String id, String resetType, Instant announcedAt, String text,
          String sourceUrl, boolean scheduled, Instant scheduledFor) {
        this(id, resetType, announcedAt, text, sourceUrl, scheduled, scheduledFor, null);
    }

    boolean forecast() { return watch != null; }
    boolean executed() { return !scheduled && !forecast(); }

    static Event forecast(Instant observedAt, String text, String sourceUrl, Watch watch) {
        // Watch has no API ID. Ignore timestamp-only refreshes to avoid repeating the same prediction.
        List<String> parts = List.of(sourceUrl, text, watch.level(), String.valueOf(watch.chancePercent()),
                watch.forecastWindow());
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) hash.update((part.length() + ":" + part).getBytes(StandardCharsets.UTF_8));
            return new Event(HexFormat.of().formatHex(hash.digest()), "", observedAt, text, sourceUrl,
                    false, null, watch);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    String key() {
        if (forecast()) return "watch:" + id;
        // A scheduled announcement can later become an executed reset with the same source ID.
        return scheduled ? "scheduled:" + id + ":" + scheduledFor : "reset:" + id;
    }
}
