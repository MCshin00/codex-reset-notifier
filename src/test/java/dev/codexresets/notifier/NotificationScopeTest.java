package dev.codexresets.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NotificationScopeTest {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path directory;

    @Test void supportedScopeValuesAndDefault() {
        assertEquals(NotificationScope.RESET_AND_SCHEDULED, scope(new Properties()));
        for (var entry : Map.of("reset-only", NotificationScope.RESET_ONLY,
                "reset-and-scheduled", NotificationScope.RESET_AND_SCHEDULED, "all", NotificationScope.ALL).entrySet()) {
            Properties properties = new Properties();
            properties.setProperty("notify.scope", entry.getKey());
            assertEquals(entry.getValue(), scope(properties));
        }
    }

    @Test void invalidScopeFailsInsteadOfEnablingOrDisablingAlertsSilently() {
        Properties properties = new Properties();
        properties.setProperty("notify.scope", "al1");
        assertThrows(IllegalArgumentException.class, () -> scope(properties));
    }

    @Test void legacySettingRetainsBehaviorButExplicitScopeWins() {
        Properties properties = new Properties();
        properties.setProperty("notify.scheduled", "false");
        assertEquals(NotificationScope.RESET_ONLY, scope(properties));
        properties.setProperty("notify.scheduled", "true");
        assertEquals(NotificationScope.RESET_AND_SCHEDULED, scope(properties));
        properties.setProperty("notify.scope", "all");
        assertEquals(NotificationScope.ALL, scope(properties));
    }

    @Test void realApiResponsesAreFilteredForEachScope() throws Exception {
        assertEquals(List.of("reset:old"), fetch(NotificationScope.RESET_ONLY, watchJson()).stream().map(Event::key).toList());
        List<Event> scheduled = fetch(NotificationScope.RESET_AND_SCHEDULED, watchJson());
        assertEquals(2, scheduled.size());
        assertTrue(scheduled.get(1).scheduled());
        List<Event> all = fetch(NotificationScope.ALL, watchJson());
        assertEquals(3, all.size());
        assertTrue(all.get(2).forecast());
        assertEquals(Integer.valueOf(80), all.get(2).watch().chancePercent());
    }

    @Test void allAcceptsNullWatchAndFiltersExpiredWatchIncludingExactExpiry() throws Exception {
        assertEquals(2, fetch(NotificationScope.ALL, null).size());
        ObjectNode expired = watchJson();
        expired.put("expires_at", NOW.toString());
        assertEquals(2, fetch(NotificationScope.ALL, expired).size());
        expired.put("expires_at", NOW.minusSeconds(1).toString());
        assertEquals(2, fetch(NotificationScope.ALL, expired).size());
    }

    @Test void nullableChanceAndBothStrengthsAreIncluded() throws Exception {
        for (String level : List.of("elevated", "strong")) {
            ObjectNode watch = watchJson();
            watch.put("level", level).putNull("reset_chance_percent");
            Event event = fetch(NotificationScope.ALL, watch).get(2);
            assertNull(event.watch().chancePercent());
            String message = client().payload(event).path("content").asText();
            assertTrue(message.contains("미제공"));
            assertTrue(message.contains("Codex 리셋 가능성 예측"));
            assertTrue(message.contains(level));
            assertFalse(message.contains("유형: 사용량 리셋"));
        }
    }

    @Test void invalidForecastDoesNotAffectDisabledScopeButFailsAllScope() throws Exception {
        ObjectNode watch = watchJson();
        watch.put("reset_chance_percent", 101);
        assertEquals(1, fetch(NotificationScope.RESET_ONLY, watch).size());
        assertThrows(PollException.class, () -> fetch(NotificationScope.ALL, watch));
    }

    @Test void onlyActualHistoryIsBaselinedAndForecastSurvivesRestartWithoutDuplicates() throws Exception {
        List<Event> events = fetch(NotificationScope.ALL, watchJson());
        List<Event> sent = new ArrayList<>();
        Path path = directory.resolve("state.json");
        try (StateStore store = new StateStore(path, json)) {
            store.lock();
            assertEquals(2, new Notifier(store, sent::add).process(events));
            assertTrue(sent.stream().noneMatch(Event::executed));
        }
        try (StateStore store = new StateStore(path, json)) {
            store.lock();
            assertEquals(0, new Notifier(store, sent::add).process(events));
        }
        assertEquals(2, sent.size());
    }

    @Test void wideningScopeDeliversCurrentForecastButNarrowingDoesNotResetState() throws Exception {
        List<Event> all = fetch(NotificationScope.ALL, watchJson());
        try (StateStore store = new StateStore(directory.resolve("state.json"), json)) {
            store.lock();
            Notifier notifier = new Notifier(store, ignored -> {});
            assertEquals(0, notifier.process(all.subList(0, 1)));
            assertEquals(2, notifier.process(all));
            assertEquals(0, notifier.process(all.subList(0, 1)));
            assertEquals(0, notifier.process(all));
        }
    }

    @Test void timestampsAloneDoNotRepeatForecastButContentChangesDo() throws Exception {
        ObjectNode original = watchJson();
        Event before = fetch(NotificationScope.ALL, original).get(2);
        ObjectNode refreshed = original.deepCopy();
        refreshed.put("observed_at", NOW.plusSeconds(300).toString());
        refreshed.put("expires_at", NOW.plusSeconds(7200).toString());
        Event after = fetch(NotificationScope.ALL, refreshed).get(2);
        assertEquals(before.key(), after.key());
        for (String field : List.of("level", "reset_chance_percent", "forecast_window", "text", "source")) {
            ObjectNode changed = original.deepCopy();
            switch (field) {
                case "level" -> changed.put(field, "elevated");
                case "reset_chance_percent" -> changed.put(field, 90);
                case "source" -> ((ObjectNode) changed.path(field)).put("url", "https://x.com/thsottiaux/status/other");
                default -> changed.put(field, "changed");
            }
            assertNotEquals(before.key(), fetch(NotificationScope.ALL, changed).get(2).key(), field);
        }
    }

    @Test void forecastPromotionToScheduleAndExecutionProducesDistinctMessages() throws Exception {
        Event forecast = fetch(NotificationScope.ALL, watchJson()).get(2);
        String source = forecast.sourceUrl();
        Event scheduled = new Event("same-post", "regular", NOW, "Scheduled", source, true, NOW.plusSeconds(3600));
        Event executed = new Event("same-post", "regular", NOW, "Executed", source, false, null);
        try (StateStore store = new StateStore(directory.resolve("state.json"), json)) {
            store.lock();
            Notifier notifier = new Notifier(store, ignored -> {});
            assertEquals(1, notifier.process(List.of(forecast)));
            assertEquals(1, notifier.process(List.of(scheduled)));
            assertEquals(1, notifier.process(List.of(executed)));
            assertEquals(0, notifier.process(List.of(forecast, scheduled, executed)));
        }
    }

    @Test void forecastMessageFitsLimitWithLongWindowAndBody() throws Exception {
        ObjectNode watch = watchJson();
        watch.put("forecast_window", "😀".repeat(2000));
        watch.put("text", "@everyone " + "😀".repeat(3000));
        var payload = client().payload(fetch(NotificationScope.ALL, watch).get(2));
        assertTrue(payload.path("content").asText().length() <= 2000);
        assertEquals(0, payload.path("allowed_mentions").path("parse").size());
        assertTrue(payload.path("content").asText().contains("https://codex-resets.com/"));
    }

    private NotificationScope scope(Properties properties) {
        return Config.from(Map.of(), properties, true).notificationScope();
    }

    private DiscordWebhookClient client() {
        return new DiscordWebhookClient(null, new HttpTransport(), json, ZoneId.of("Asia/Seoul"));
    }

    private ObjectNode watchJson() {
        ObjectNode watch = json.createObjectNode();
        watch.put("level", "strong").put("reset_chance_percent", 80).put("forecast_window", "next 24 hours")
                .put("observed_at", NOW.toString()).put("expires_at", NOW.plusSeconds(3600).toString())
                .put("text", "Synthetic test forecast; not a real announcement.");
        watch.putObject("source").put("type", "x_post").put("author", "thsottiaux")
                .put("url", "https://x.com/thsottiaux/status/same-post");
        return watch;
    }

    private List<Event> fetch(NotificationScope scope, ObjectNode watch) throws Exception {
        ObjectNode reset = json.createObjectNode().put("id", "old").put("reset_type", "regular")
                .put("announced_at", NOW.toString()).put("text", "Executed test reset");
        reset.putObject("source").put("type", "observed");
        ObjectNode scheduled = reset.deepCopy().put("id", "future").put("status", "scheduled")
                .putNull("scheduled_for");
        ObjectNode status = json.createObjectNode();
        ObjectNode data = status.putObject("data");
        data.set("latest_reset", reset);
        data.set("scheduled_reset", scheduled);
        data.set("active_watch", watch == null ? json.nullNode() : watch);
        status.putObject("meta").put("api_version", "v1");
        ObjectNode history = json.createObjectNode();
        history.putArray("data").add(reset);
        history.putObject("pagination").put("has_more", false).putNull("next_cursor");
        history.putObject("meta").put("api_version", "v1");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith("/status") ? status.toString() : history.toString();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            return new ResetApiClient(base, new HttpTransport(), json, Clock.fixed(NOW, ZoneOffset.UTC)).fetch(scope);
        } finally { server.stop(0); }
    }
}
