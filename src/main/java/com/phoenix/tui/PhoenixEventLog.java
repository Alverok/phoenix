package com.phoenix.tui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.google.inject.Singleton;

@Singleton
public class PhoenixEventLog {

    private static final int MAX_LINES = 300;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Deque<String> lines = new ArrayDeque<>();

    public synchronized void append(String line) {

        if (line == null || line.isBlank()) {
            return;
        }

        String normalized = line.strip();

        lines.addLast(
                "[" + LocalTime.now().format(TIME) + "] " + normalized
        );

        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public synchronized List<String> recent(int limit) {

        List<String> snapshot =
                new ArrayList<>(lines);

        int from =
                Math.max(
                        0,
                        snapshot.size() - limit
                );

        return List.copyOf(
                snapshot.subList(
                        from,
                        snapshot.size()
                )
        );
    }

    public synchronized void clear() {
        lines.clear();
    }
}
