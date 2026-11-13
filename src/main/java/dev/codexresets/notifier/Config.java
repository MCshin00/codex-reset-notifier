package dev.codexresets.notifier;

import java.net.URI;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

record Config(URI webhook, Path stateFile, Duration interval, NotificationScope notificationScope, ZoneId zone) {
    static Config load(Map<String, String> env, boolean dryRun) throws IOException {
        Path file = Path.of(env.getOrDefault("CONFIG_FILE", "config/application.properties"));
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return from(env, properties, dryRun);
    }

    static Config from(Map<String, String> env, Properties properties, boolean dryRun) {
        URI webhook = null;
        if (!dryRun) {
            try {
                webhook = URI.create(env.getOrDefault("DISCORD_WEBHOOK_URL", ""));
                if (!"https".equals(webhook.getScheme())
                        || !Set.of("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com")
                               .contains(webhook.getHost() == null ? "" : webhook.getHost())
                        || !webhook.getPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9_-]+")
                        || webhook.getUserInfo() != null || webhook.getFragment() != null
                        || webhook.getPort() != -1) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("DISCORD_WEBHOOK_URL에 유효한 Discord 웹훅 주소를 설정하세요.");
            }
        }
        long minutes;
        try {
            minutes = Long.parseLong(properties.getProperty("poll.interval.minutes", "30").strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("poll.interval.minutes는 분 단위 정수여야 합니다.");
        }
        if (minutes < 1 || minutes > 1440) {
            throw new IllegalArgumentException("poll.interval.minutes는 1~1440 범위여야 합니다.");
        }
        return new Config(webhook, Path.of(env.getOrDefault("STATE_FILE", "data/state.json")),
                Duration.ofMinutes(minutes), NotificationScope.from(properties),
                ZoneId.of(properties.getProperty("display.time.zone", "Asia/Seoul").strip()));
    }
}
