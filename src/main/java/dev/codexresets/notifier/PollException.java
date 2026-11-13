package dev.codexresets.notifier;

import java.time.Duration;

final class PollException extends Exception {
    private final Duration retryAfter;

    PollException(String message) { this(message, Duration.ZERO); }

    PollException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() { return retryAfter; }
}
