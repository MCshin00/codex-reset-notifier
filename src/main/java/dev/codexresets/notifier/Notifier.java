package dev.codexresets.notifier;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Notifier {
    @FunctionalInterface interface Sender {
        void send(Event event) throws PollException, InterruptedException;
    }

    private final StateStore store;
    private final Sender sender;

    Notifier(StateStore store, Sender sender) {
        this.store = store;
        this.sender = sender;
    }

    int process(List<Event> events) throws IOException, PollException, InterruptedException {
        Set<String> seen = store.load();
        if (seen == null) {
            seen = new LinkedHashSet<>();
            // Baseline only executed history; enabled current schedules and forecasts are delivered.
            for (Event event : events) if (event.executed()) seen.add(event.key());
            store.save(seen);
            Main.log("최초 실행: 기존 리셋 기록을 저장했습니다. 과거 기록은 전송하지 않습니다.");
        }
        int sent = 0;
        for (Event event : events) {
            if (seen.contains(event.key())) continue;
            sender.send(event);
            seen.add(event.key());
            store.save(seen);
            sent++;
            Main.log("알림 전송 완료: " + event.key());
        }
        return sent;
    }
}
