package dev.codexresets.notifier;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class HttpTransport {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    HttpResponse<String> send(HttpRequest request, String service) throws PollException, InterruptedException {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new PollException(service + " HTTP " + status,
                        retryAfter(response.headers().firstValue("Retry-After").orElse("")));
            }
            return response;
        } catch (IOException e) {
            // Never log request URLs or exception messages: webhook paths contain credentials.
            throw new PollException(service + " 연결 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    static Duration retryAfter(String value) {
        try {
            return Duration.ofMillis(Math.max(0, (long) Math.ceil(Double.parseDouble(value) * 1000)));
        } catch (NumberFormatException e) {
            try {
                Duration duration = Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (RuntimeException ignored) {
                return Duration.ZERO;
            }
        }
    }
}
