package dev.codexresets.notifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Runs on local clock boundaries, counted from midnight each day. */
final class WallClockSchedule {
    private final int minutes;
    private final ZoneId zone;

    WallClockSchedule(Duration interval, ZoneId zone) {
        this.minutes = Math.toIntExact(interval.toMinutes());
        if (minutes < 1 || minutes > 1440 || !interval.equals(Duration.ofMinutes(minutes))) {
            throw new IllegalArgumentException("Schedule interval must be 1 to 1440 whole minutes.");
        }
        this.zone = zone;
    }

    Instant nextAfter(Instant now) { return nextBoundary(now, false); }

    Instant nextAfter(Instant completedAt, Duration retryAfter) {
        Instant next = nextAfter(completedAt);
        if (retryAfter.isNegative() || retryAfter.isZero()) return next;
        Instant earliest = completedAt.plus(retryAfter);
        return next.isBefore(earliest) ? nextBoundary(earliest, true) : next;
    }

    private Instant nextBoundary(Instant threshold, boolean inclusive) {
        LocalDate date = threshold.atZone(zone).toLocalDate();
        while (true) {
            Instant earliest = null;
            for (int minute = 0; minute < 1440; minute += minutes) {
                LocalDateTime local = date.atStartOfDay().plusMinutes(minute);
                // Skip nonexistent local times; consider both offsets during a daylight-saving overlap.
                for (ZoneOffset offset : zone.getRules().getValidOffsets(local)) {
                    Instant candidate = local.toInstant(offset);
                    boolean eligible = inclusive ? !candidate.isBefore(threshold) : candidate.isAfter(threshold);
                    if (eligible && (earliest == null || candidate.isBefore(earliest))) earliest = candidate;
                }
            }
            if (earliest != null) return earliest;
            date = date.plusDays(1);
        }
    }

    static void waitUntil(Instant target) throws InterruptedException {
        while (true) {
            Duration remaining = Duration.between(Instant.now(), target);
            if (remaining.isNegative() || remaining.isZero()) return;
            // Recheck wall time periodically so clock corrections do not cause a full-interval drift.
            Thread.sleep(Math.max(1, Math.min(1000, remaining.toMillis())));
        }
    }
}
