package dev.codexresets.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

final class ResetApiClient {
    private final URI base;
    private final HttpTransport http;
    private final ObjectMapper json;
    private final Clock clock;

    ResetApiClient(URI base, HttpTransport http, ObjectMapper json) {
        this(base, http, json, Clock.systemUTC());
    }

    ResetApiClient(URI base, HttpTransport http, ObjectMapper json, Clock clock) {
        this.base = base;
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    List<Event> fetch(NotificationScope scope) throws PollException, InterruptedException {
        JsonNode status = get("/api/v1/status").path("data");
        if (!status.has("latest_reset") || !status.has("scheduled_reset")) {
            throw new PollException("상태 API의 필수 필드가 없습니다.");
        }
        LinkedHashMap<String, Event> resets = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; ; page++) {
            if (page >= 100) throw new PollException("API 페이지 수가 상한을 초과했습니다.");
            String path = "/api/v1/resets?limit=100&order=asc";
            if (cursor != null) path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            JsonNode response = get(path);
            JsonNode data = response.path("data");
            JsonNode pagination = response.path("pagination");
            if (!data.isArray() || !pagination.path("has_more").isBoolean()) {
                throw new PollException("리셋 API 응답 구조가 변경되었습니다.");
            }
            for (JsonNode item : data) {
                Event event = event(item, false);
                resets.put(event.id(), event);
            }
            if (!pagination.path("has_more").booleanValue()) break;
            cursor = requiredText(pagination, "next_cursor");
            if (!cursors.add(cursor)) throw new PollException("API 페이지 커서가 반복됩니다.");
        }
        if (!status.path("latest_reset").isNull()) {
            Event latest = event(status.path("latest_reset"), false);
            resets.put(latest.id(), latest);
        }
        List<Event> events = new ArrayList<>(resets.values());
        events.sort(Comparator.comparing(Event::announcedAt).thenComparing(Event::id));
        if (scope.includesScheduled() && !status.path("scheduled_reset").isNull()) {
            Event scheduled = event(status.path("scheduled_reset"), true);
            // Suppress a stale status response if history already records execution.
            if (!resets.containsKey(scheduled.id())) events.add(scheduled);
        }
        if (scope.includesWatch()) {
            if (!status.has("active_watch")) throw new PollException("API 필수 필드 누락: active_watch");
            if (!status.path("active_watch").isNull()) {
                Event forecast = watch(status.path("active_watch"));
                if (forecast.watch().expiresAt().isAfter(clock.instant())) events.add(forecast);
            }
        }
        return List.copyOf(events);
    }

    private Event watch(JsonNode node) throws PollException {
        try {
            String level = requiredText(node, "level");
            if (!Set.of("elevated", "strong").contains(level)) throw new PollException("알 수 없는 예측 강도입니다.");
            JsonNode chance = node.path("reset_chance_percent");
            if (!chance.isNull() && (!chance.isIntegralNumber() || !chance.canConvertToInt()
                    || chance.intValue() < 0 || chance.intValue() > 100)) {
                throw new PollException("예측 가능성은 0~100 정수 또는 null이어야 합니다.");
            }
            if (!node.path("forecast_window").isTextual() || !node.path("text").isTextual()) {
                throw new PollException("예측 API의 본문 또는 예측 구간이 누락되었습니다.");
            }
            Instant observedAt = Instant.parse(requiredText(node, "observed_at"));
            Instant expiresAt = Instant.parse(requiredText(node, "expires_at"));
            Event.Watch details = new Event.Watch(level, chance.isNull() ? null : chance.intValue(),
                    node.path("forecast_window").textValue(), expiresAt);
            return Event.forecast(observedAt, node.path("text").textValue(), sourceUrl(node), details);
        } catch (RuntimeException e) {
            throw new PollException("예측 API의 날짜 또는 주소 형식이 잘못되었습니다.");
        }
    }

    private JsonNode get(String path) throws PollException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                .header("User-Agent", "codex-reset-notifier/1.0").GET().build();
        String body = http.send(request, "Codex Resets API").body();
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !"v1".equals(root.path("meta").path("api_version").asText())) {
                throw new PollException("지원하지 않는 API 버전입니다.");
            }
            return root;
        } catch (java.io.IOException e) {
            throw new PollException("API가 올바른 JSON을 반환하지 않았습니다.");
        }
    }

    private Event event(JsonNode node, boolean scheduled) throws PollException {
        try {
            String id = requiredText(node, "id");
            String type = requiredText(node, "reset_type");
            if (!Set.of("regular", "banked").contains(type)) {
                throw new PollException("알 수 없는 reset_type입니다.");
            }
            if (scheduled && !"scheduled".equals(requiredText(node, "status"))) {
                throw new PollException("알 수 없는 예정 공지 상태입니다.");
            }
            Instant announced = Instant.parse(requiredText(node, "announced_at"));
            Instant scheduledFor = scheduled && !node.path("scheduled_for").isNull()
                    ? Instant.parse(requiredText(node, "scheduled_for")) : null;
            String url = sourceUrl(node);
            // The OpenAPI schema allows empty text, including observed resets without a post.
            if (!node.path("text").isTextual()) throw new PollException("API 필수 필드 누락: text");
            return new Event(id, type, announced, node.path("text").textValue(), url, scheduled, scheduledFor);
        } catch (RuntimeException e) {
            throw new PollException("API 공지의 날짜 또는 주소 형식이 잘못되었습니다.");
        }
    }

    private static String sourceUrl(JsonNode node) {
        String url = node.path("source").path("url").asText("");
        if (!url.isBlank()) {
            URI source = URI.create(url);
            if (!"https".equals(source.getScheme()) || source.getHost() == null) return "";
        }
        return url;
    }

    private static String requiredText(JsonNode node, String field) throws PollException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new PollException("API 필수 필드 누락: " + field);
        }
        return value.textValue();
    }
}
