package com.caseintake.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Sliding 60-second window of at most {@code rpm} calls per model. */
public class RateLimiter {
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final int rpm;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Deque<Instant>> calls = new HashMap<>();

    public RateLimiter(int rpm, Clock clock, Sleeper sleeper) {
        if (rpm < 1) {
            throw new IllegalArgumentException("requests-per-minute must be >= 1");
        }
        this.rpm = rpm;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Blocks until a slot is free in the model's window, then records the call. */
    public synchronized void acquire(String model) {
        Deque<Instant> q = calls.computeIfAbsent(model, k -> new ArrayDeque<>());
        while (true) {
            Instant now = clock.instant();
            while (!q.isEmpty() && !q.peekFirst().plus(WINDOW).isAfter(now)) {
                q.pollFirst();
            }
            if (q.size() < rpm) {
                q.addLast(now);
                return;
            }
            sleeper.sleep(Duration.between(now, q.peekFirst().plus(WINDOW)));
        }
    }
}
