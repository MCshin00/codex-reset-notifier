package dev.codexresets.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NotifierTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void firstRunSkipsHistoryButSendsCurrentSchedule() throws Exception {
        var delivered = new ArrayList<Event>();
        try (var store = store()) {
            store.lock();
            var notifier = new Notifier(store, delivered::add);
            Event old = event("old", false);
            Event scheduled = event("next", true);
            assertEquals(1, notifier.process(List.of(old, scheduled)));
            assertEquals(List.of(scheduled), delivered);
            assertEquals(Set.of(old.key(), scheduled.key()), store.load());
        }
    }

    @Test void restartDoesNotResendAndSameIdCompletionIsSeparate() throws Exception {
        Event scheduled = event("same", true);
        try (var store = store()) {
            store.lock();
            new Notifier(store, ignored -> {}).process(List.of(scheduled));
        }
        var delivered = new ArrayList<Event>();
        try (var store = store()) {
            store.lock();
            var notifier = new Notifier(store, delivered::add);
            assertEquals(0, notifier.process(List.of(scheduled)));
            assertEquals(1, notifier.process(List.of(event("same", false))));
            assertFalse(delivered.get(0).scheduled());
        }
    }

    @Test void failedDeliveryIsRetriedButEarlierSuccessIsNotRepeated() throws Exception {
        try (var store = store()) {
            store.lock();
            store.save(Set.of());
            var delivered = new ArrayList<Event>();
            var notifier = new Notifier(store, e -> {
                if (e.id().equals("b")) throw new PollException("Discord HTTP 500");
                delivered.add(e);
            });
            List<Event> events = List.of(event("a", false), event("b", false), event("c", false));
            assertThrows(PollException.class, () -> notifier.process(events));
            assertEquals(Set.of("reset:a"), store.load());
            assertEquals(2, new Notifier(store, delivered::add).process(events));
            assertEquals(List.of("a", "b", "c"), delivered.stream().map(Event::id).toList());
        }
    }

    @Test void rescheduledTimeProducesOneNewNotice() throws Exception {
        try (var store = store()) {
            store.lock();
            var notifier = new Notifier(store, ignored -> {});
            Event before = event("same", true);
            Event after = new Event(before.id(), before.resetType(), before.announcedAt(), before.text(),
                    before.sourceUrl(), true, before.scheduledFor().plusSeconds(3600));
            assertEquals(1, notifier.process(List.of(before)));
            assertEquals(1, notifier.process(List.of(after)));
            assertEquals(0, notifier.process(List.of(after)));
        }
    }

    @Test void corruptedStateIsNotSilentlyReinitialized() throws Exception {
        Files.writeString(directory.resolve("state.json"), "{broken");
        try (var store = store()) {
            assertThrows(IOException.class, store::load);
            assertEquals("{broken", Files.readString(directory.resolve("state.json")));
        }
    }

    @Test void sameVolumeCannotBeUsedByTwoProcesses() throws Exception {
        try (var first = store(); var second = store()) {
            first.lock();
            assertThrows(IOException.class, second::lock);
        }
    }

    @Test void payloadHasAttributionNoMentionsAndFitsDiscordLimit() {
        var client = webhook(URI.create("http://localhost/"));
        Event longEvent = new Event("1", "banked", Instant.EPOCH, "@everyone " + "😀".repeat(3000),
                "https://x.com/thsottiaux/status/1", false, null);
        var payload = client.payload(longEvent);
        assertTrue(payload.path("content").asText().length() <= 2000);
        assertTrue(payload.path("content").asText().contains("https://codex-resets.com/"));
        assertTrue(payload.path("content").asText().contains("banked"));
        assertEquals(0, payload.path("allowed_mentions").path("parse").size());
        String content = payload.path("content").asText();
        for (int i = 0; i < content.length(); i++) {
            if (Character.isHighSurrogate(content.charAt(i))) {
                assertTrue(i + 1 < content.length() && Character.isLowSurrogate(content.charAt(++i)));
            } else assertFalse(Character.isLowSurrogate(content.charAt(i)));
        }
    }

    @Test void expiredScheduleIsStillLabeledAsScheduled() {
        String content = webhook(URI.create("http://localhost/"))
                .payload(event("old-schedule", true)).path("content").asText();
        assertTrue(content.contains("예정 공지"));
        assertTrue(content.contains("1970-01-01 10:00"));
    }

    @Test void apiTimestampsAreConvertedToKstWithoutMinuteAdjustment() {
        Event scheduled = new Event("2102254445082116335", "regular",
                Instant.parse("2026-09-22T04:31:32.000Z"), "Announcement",
                "https://x.com/thsottiaux/status/2102254445082116335", true,
                Instant.parse("2026-09-23T06:59:00.000Z"));
        String content = webhook(URI.create("http://localhost/"))
                .payload(scheduled).path("content").asText();
        assertTrue(content.contains("공지 시각: 2026-09-22 13:31 KST"));
        assertTrue(content.contains("예정 시각: 2026-09-23 15:59 KST"));
    }

    @Test void webhookWaitOverridesFalseAndPreservesThreadId() {
        assertEquals("https://discord.com/api/webhooks/1/token?thread_id=123&wait=true",
                DiscordWebhookClient.confirmedUri(URI.create(
                        "https://discord.com/api/webhooks/1/token?wait=false&thread_id=123")).toString());
    }

    @Test void actualHttpDeliveryRequestsConfirmationAndEncodesPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/webhook", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"id\":\"message-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            webhook(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook"))
                    .send(event("1", false));
            assertEquals("wait=true", query.get());
            assertEquals("Codex Resets", json.readTree(body.get()).path("username").asText());
        } finally { server.stop(0); }
    }

    @Test void unconfirmedDiscordResponseFailsWithoutLeakingWebhook() throws Exception {
        HttpServer server = server(200, "{}", null);
        try {
            PollException error = assertThrows(PollException.class,
                    () -> webhook(base(server).resolve("/secret-token")).send(event("1", false)));
            assertFalse(error.getMessage().contains("secret-token"));
        } finally { server.stop(0); }
    }

    @Test void rateLimitCarriesRetryAfterAndDoesNotRetryImmediately() throws Exception {
        HttpServer server = server(429, "{}", "601.5");
        try {
            var api = new ResetApiClient(base(server), new HttpTransport(), json);
            PollException error = assertThrows(PollException.class, () -> api.fetch(NotificationScope.RESET_AND_SCHEDULED));
            assertEquals(Duration.ofMillis(601500), error.retryAfter());
        } finally { server.stop(0); }
    }

    @Test void paginationCatchesUpAllRecordsAndSuppressesStaleScheduledStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            paths.add(path);
            String response;
            if (path.contains("/status")) {
                response = "{\"data\":{\"latest_reset\":" + resetJson("c", false)
                        + ",\"scheduled_reset\":" + resetJson("b", true) + "},\"meta\":{\"api_version\":\"v1\"}}";
            } else if (path.contains("cursor=next-page")) {
                response = page(resetJson("b", false).replace("\"text\":\"Reset\"", "\"text\":\"\""), false, null);
            } else response = page(resetJson("a", false), true, "next-page");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            List<Event> events = new ResetApiClient(base(server), new HttpTransport(), json).fetch(NotificationScope.RESET_AND_SCHEDULED);
            assertEquals(List.of("a", "b", "c"), events.stream().map(Event::id).toList());
            assertEquals("", events.get(1).text(), "OpenAPI permits empty text on observed resets");
            assertTrue(events.stream().noneMatch(Event::scheduled));
            assertEquals(3, paths.size());
            assertTrue(paths.get(2).contains("cursor=next-page"));
        } finally { server.stop(0); }
    }

    @Test void schemaChangeFailsInsteadOfTreatingEverythingAsEmpty() throws Exception {
        HttpServer server = server(200, "{\"data\":{},\"meta\":{\"api_version\":\"v1\"}}", null);
        try {
            assertThrows(PollException.class,
                    () -> new ResetApiClient(base(server), new HttpTransport(), json).fetch(NotificationScope.RESET_AND_SCHEDULED));
        } finally { server.stop(0); }
    }

    @Test void configAllowsDryRunWithoutSecretButRejectsInvalidNormalConfig() {
        assertNull(Config.from(Map.of(), new Properties(), true).webhook());
        assertEquals(Duration.ofMinutes(30), Config.from(Map.of(), new Properties(), true).interval());
        assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of(), new Properties(), false));
        Properties invalid = new Properties();
        invalid.setProperty("poll.interval.minutes", "0");
        assertThrows(IllegalArgumentException.class,
                () -> Config.from(Map.of(), invalid, true));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Config.from(Map.of("DISCORD_WEBHOOK_URL", "https://wrong.example/secret-token"), new Properties(), false));
        assertFalse(failure.getMessage().contains("secret-token"));
    }

    @Test void intervalComesFromUtf8ConfigFileAndChangesOnReload() throws Exception {
        Path config = directory.resolve("application.properties");
        Files.writeString(config, "# 조회 간격\npoll.interval.minutes=45\nnotify.scope=reset-only\n");
        Map<String, String> env = Map.of("CONFIG_FILE", config.toString());
        assertEquals(Duration.ofMinutes(45), Config.load(env, true).interval());
        assertEquals(NotificationScope.RESET_ONLY, Config.load(env, true).notificationScope());
        Files.writeString(config, "poll.interval.minutes=60\n");
        assertEquals(Duration.ofHours(1), Config.load(env, true).interval());
    }

    private StateStore store() { return new StateStore(directory.resolve("state.json"), json); }

    private DiscordWebhookClient webhook(URI uri) {
        return new DiscordWebhookClient(uri, new HttpTransport(), json, ZoneId.of("Asia/Seoul"));
    }

    private static Event event(String id, boolean scheduled) {
        return new Event(id, "regular", Instant.EPOCH, "Reset announcement", "https://example.com/" + id,
                scheduled, scheduled ? Instant.EPOCH.plusSeconds(3600) : null);
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static HttpServer server(int status, String body, String retryAfter) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String resetJson(String id, boolean scheduled) {
        return "{\"id\":\"" + id + "\",\"reset_type\":\"regular\",\"announced_at\":\"2026-01-01T00:00:00Z\","
                + "\"text\":\"Reset\",\"source\":{\"type\":\"observed\"}"
                + (scheduled ? ",\"status\":\"scheduled\",\"scheduled_for\":null" : "") + "}";
    }

    private static String page(String data, boolean more, String cursor) {
        return "{\"data\":[" + data + "],\"pagination\":{\"has_more\":" + more
                + ",\"next_cursor\":" + (cursor == null ? "null" : "\"" + cursor + "\"")
                + "},\"meta\":{\"api_version\":\"v1\"}}";
    }
}
