package dev.codexresets.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

final class DiscordWebhookClient {
    private final URI webhook;
    private final HttpTransport http;
    private final ObjectMapper json;
    private final ZoneId zone;

    DiscordWebhookClient(URI webhook, HttpTransport http, ObjectMapper json, ZoneId zone) {
        this.webhook = webhook;
        this.http = http;
        this.json = json;
        this.zone = zone;
    }

    void send(Event event) throws PollException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(confirmedUri(webhook))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("User-Agent", "codex-reset-notifier/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload(event).toString())).build();
        String body = http.send(request, "Discord").body();
        try {
            var message = json.readTree(body);
            if (message == null || !message.path("id").isTextual() || message.path("id").asText().isBlank()) {
                throw new PollException("Discord 메시지 저장 확인에 실패했습니다.");
            }
        } catch (java.io.IOException e) {
            throw new PollException("Discord 응답을 확인하지 못했습니다.");
        }
    }

    ObjectNode payload(Event event) {
        String title = event.forecast() ? "Codex 리셋 가능성 예측"
                : event.scheduled() ? "Codex 리셋 예정 공지" : "Codex 리셋 기록 업데이트";
        String type = event.resetType().equals("banked") ? "리셋 크레딧 지급 (banked)" : "사용량 리셋 (regular)";
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(zone);
        StringBuilder content = new StringBuilder("**").append(title).append("**\n");
        if (event.forecast()) {
            Event.Watch watch = event.watch();
            content.append("사이트 예측 강도: ").append(watch.level()).append("\n")
                    .append("사이트 예측 가능성: ").append(watch.chancePercent() == null ? "미제공" : watch.chancePercent() + "%").append("\n")
                    .append("예측 구간: ").append(truncate(watch.forecastWindow(), 200)).append("\n")
                    .append("관측 시각: ").append(format.format(event.announcedAt())).append("\n")
                    .append("예측 만료: ").append(format.format(watch.expiresAt())).append("\n");
        } else {
            content.append("유형: ").append(type).append("\n")
                    .append("공지 시각: ").append(format.format(event.announcedAt())).append("\n");
        }
        if (event.scheduled()) {
            content.append("예정 시각: ")
                    .append(event.scheduledFor() == null ? "미정" : format.format(event.scheduledFor())).append("\n");
        }
        // Bound the whole content by UTF-16 units (conservative for Discord's 2,000 character limit).
        String source = event.sourceUrl().length() <= 400 ? event.sourceUrl() : "";
        String footer = (source.isBlank() ? "" : "\n원문: <" + source + ">")
                + "\n출처: <https://codex-resets.com/>";
        String excerpt = truncate(event.text(), Math.max(0, 1900 - content.length() - footer.length() - 2));
        content.append("\n").append(excerpt).append(footer);
        ObjectNode payload = json.createObjectNode();
        payload.put("username", "Codex Resets");
        payload.put("content", content.toString());
        payload.putObject("allowed_mentions").putArray("parse");
        return payload;
    }

    static URI confirmedUri(URI webhook) {
        String query = webhook.getRawQuery();
        String kept = query == null ? "" : Arrays.stream(query.split("&"))
                .filter(part -> !part.split("=", 2)[0].equalsIgnoreCase("wait"))
                .collect(Collectors.joining("&"));
        return URI.create(webhook.toString().split("\\?", 2)[0] + "?"
                + (kept.isEmpty() ? "" : kept + "&") + "wait=true");
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        int end = Math.max(0, max - 1);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
