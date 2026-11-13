package dev.codexresets.notifier;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WallClockScheduleTest {
    private final WallClockSchedule halfHourly = schedule(30, "Asia/Seoul");
    @TempDir Path directory;

    @Test void startAt2128WaitsFor2130Then2200Then2230() {
        assertNext("2026-09-22T12:28:00Z", "2026-09-22T12:30:00Z");
        assertNext("2026-09-22T12:30:00Z", "2026-09-22T13:00:00Z");
        assertNext("2026-09-22T13:00:00Z", "2026-09-22T13:30:00Z");
    }

    @Test void processingTimeDoesNotShiftTheNextRun() {
        assertNext("2026-09-22T12:30:17.123Z", "2026-09-22T13:00:00Z");
    }

    @Test void changingConfigFromThirtyToSixtyMinutesRealignsToWholeHours() throws Exception {
        Path file = directory.resolve("application.properties");
        Map<String, String> env = Map.of("CONFIG_FILE", file.toString());
        Instant startedAt = Instant.parse("2026-09-22T12:28:00Z");
        Files.writeString(file, "poll.interval.minutes=30\ndisplay.time.zone=Asia/Seoul\n");
        Config thirty = Config.load(env, true);
        assertEquals(Instant.parse("2026-09-22T12:30:00Z"),
                new WallClockSchedule(thirty.interval(), thirty.zone()).nextAfter(startedAt));

        Files.writeString(file, "poll.interval.minutes=60\ndisplay.time.zone=Asia/Seoul\n");
        Config sixty = Config.load(env, true);
        WallClockSchedule hourly = new WallClockSchedule(sixty.interval(), sixty.zone());
        assertEquals(Instant.parse("2026-09-22T13:00:00Z"), hourly.nextAfter(startedAt));
        assertEquals(Instant.parse("2026-09-22T14:00:00Z"),
                hourly.nextAfter(Instant.parse("2026-09-22T13:00:12Z")));
    }

    @Test void restartAndLongPollResumeAtNextBoundary() {
        assertNext("2026-09-22T12:42:00Z", "2026-09-22T13:00:00Z");
        assertNext("2026-09-22T13:01:00Z", "2026-09-22T13:30:00Z");
    }

    @Test void dailyRolloverReturnsMidnight() {
        assertNext("2026-09-22T14:59:59Z", "2026-09-22T15:00:00Z");
    }

    @Test void retryAfterSkipsRestrictedBoundariesAndKeepsClockAlignment() {
        Instant completed = Instant.parse("2026-09-22T12:30:10Z");
        assertEquals(Instant.parse("2026-09-22T13:30:00Z"), halfHourly.nextAfter(completed, Duration.ofMinutes(40)));
        assertEquals(Instant.parse("2026-09-22T13:00:00Z"), halfHourly.nextAfter(completed, Duration.ofSeconds(10)));
    }

    @Test void retryEndingExactlyOnBoundaryCanUseThatBoundary() {
        Instant completed = Instant.parse("2026-09-22T12:30:10Z");
        assertEquals(Instant.parse("2026-09-22T13:30:00Z"), halfHourly.nextAfter(completed, Duration.ofSeconds(3590)));
    }

    @Test void differentIntervalsAreAnchoredAtLocalMidnight() {
        assertEquals(Instant.parse("2026-09-22T12:30:00Z"), schedule(15, "Asia/Seoul").nextAfter(Instant.parse("2026-09-22T12:28:00Z")));
        assertEquals(Instant.parse("2026-09-22T13:00:00Z"), schedule(60, "Asia/Seoul").nextAfter(Instant.parse("2026-09-22T12:28:00Z")));
        assertEquals(Instant.parse("2026-09-22T16:30:00Z"), schedule(45, "Asia/Seoul").nextAfter(Instant.parse("2026-09-22T16:00:00Z")));
        assertEquals(Instant.parse("2026-09-22T15:00:00Z"), schedule(1440, "Asia/Seoul").nextAfter(Instant.parse("2026-09-22T12:28:00Z")));
        assertEquals(Instant.parse("2026-09-22T15:00:00Z"), schedule(50, "Asia/Seoul").nextAfter(Instant.parse("2026-09-22T14:50:00Z")));
    }

    @Test void scheduleUsesConfiguredTimezone() {
        Instant now = Instant.parse("2026-09-22T12:28:00Z");
        assertEquals(Instant.parse("2026-09-22T12:45:00Z"), schedule(30, "Asia/Kathmandu").nextAfter(now));
    }

    @Test void springGapSkipsNonexistentLocalTimes() {
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), schedule(30, "America/New_York")
                .nextAfter(Instant.parse("2026-03-08T06:50:00Z")));
    }

    @Test void autumnOverlapConsidersBothOccurrencesInInstantOrder() {
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), schedule(30, "America/New_York")
                .nextAfter(Instant.parse("2026-11-01T05:50:00Z")));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), schedule(30, "America/New_York")
                .nextAfter(Instant.parse("2026-11-01T06:10:00Z")));
    }

    private void assertNext(String now, String expected) {
        assertEquals(Instant.parse(expected), halfHourly.nextAfter(Instant.parse(now)));
    }

    private static WallClockSchedule schedule(int minutes, String zone) {
        return new WallClockSchedule(Duration.ofMinutes(minutes), ZoneId.of(zone));
    }
}
