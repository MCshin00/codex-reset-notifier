package dev.codexresets.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class Main {
    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args) {
        List<String> options = Arrays.asList(args);
        if (options.contains("--help")) {
            System.out.println("""
                    Codex Reset Notifier (Java 17+)
                    기본: 설정 시간대의 자정을 기준으로 N분 간격의 시각에 확인 (30분이면 매시 00분/30분)
                    --once     예약 시각을 기다리지 않고 즉시 한 번 확인 후 종료
                    --dry-run  API/메시지 미리보기만 실행 (전송/상태 변경 없음, 웹훅 불필요)
                    --help     도움말
                    설정 및 Docker 실행법은 README.md를 참고하세요.
                    """);
            return 0;
        }
        if (options.stream().anyMatch(option -> !Set.of("--once", "--dry-run").contains(option))) {
            log("알 수 없는 실행 옵션입니다. --help를 확인하세요.");
            return 2;
        }
        try {
            boolean dryRun = options.contains("--dry-run");
            Config config = Config.load(System.getenv(), dryRun);
            ObjectMapper json = new ObjectMapper();
            HttpTransport http = new HttpTransport();
            ResetApiClient api = new ResetApiClient(URI.create("https://codex-resets.com"), http, json);
            DiscordWebhookClient discord = new DiscordWebhookClient(config.webhook(), http, json, config.zone());
            try (StateStore store = new StateStore(config.stateFile(), json)) {
                if (dryRun) {
                    List<Event> events = api.fetch(config.notificationScope());
                    var seen = store.load();
                    Event latest = events.stream().filter(Event::executed).reduce((a, b) -> b).orElse(null);
                    List<Event> preview = seen == null
                            ? events.stream().filter(e -> !e.executed() || e.equals(latest)).toList()
                            : events.stream().filter(e -> !seen.contains(e.key())).toList();
                    log("미리보기: " + events.size() + "건 조회, 전송/상태 저장 없음. 최초 실행 시 과거 리셋은 생략됩니다.");
                    for (Event event : preview) System.out.println(discord.payload(event).toPrettyString());
                    return 0;
                }
                store.lock();
                // Detect corrupt state before network work or notification delivery.
                store.load();
                Notifier notifier = new Notifier(store, discord::send);
                log("실행 시작. 조회 간격: " + config.interval().toMinutes() + "분, 알림 범위: " + config.notificationScope());
                boolean once = options.contains("--once");
                WallClockSchedule schedule = new WallClockSchedule(config.interval(), config.zone());
                DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(config.zone());
                Instant nextRun = schedule.nextAfter(Instant.now());
                do {
                    if (!once) {
                        log("다음 조회: " + timeFormat.format(nextRun));
                        WallClockSchedule.waitUntil(nextRun);
                    }
                    Duration retryAfter = Duration.ZERO;
                    try {
                        int count = notifier.process(api.fetch(config.notificationScope()));
                        log("확인 완료. 새 알림 " + count + "건");
                    } catch (PollException e) {
                        log(e.getMessage());
                        if (once) return 1;
                        retryAfter = e.retryAfter();
                    }
                    if (once) return 0;
                    nextRun = schedule.nextAfter(Instant.now(), retryAfter);
                } while (!Thread.currentThread().isInterrupted());
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (PollException e) {
            log(e.getMessage());
            return 1;
        } catch (Exception e) {
            // Raw exceptions can contain webhook URLs or external payloads; don't print them.
            log("실행을 중단했습니다 (" + e.getClass().getSimpleName()
                    + "). 설정 파일, 환경변수, 상태 파일 형식/권한, 중복 실행 여부를 확인하세요.");
            return 1;
        }
    }

    static void log(String message) { System.out.println(Instant.now() + " " + message); }
}
