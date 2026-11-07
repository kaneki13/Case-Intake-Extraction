package com.caseintake.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    /** Clock advanced by the recording sleeper. */
    static class FakeTime extends Clock implements Sleeper {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final List<Duration> sleeps = new ArrayList<>();

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public void sleep(Duration d) { sleeps.add(d); now = now.plus(d); }
    }

    @Test
    void ninthCallInMinuteWaitsForWindow() {
        FakeTime t = new FakeTime();
        RateLimiter rl = new RateLimiter(8, t, t);
        for (int i = 0; i < 8; i++) {
            rl.acquire("m");
            t.now = t.now.plusSeconds(1);
        }
        assertThat(t.sleeps).isEmpty();
        rl.acquire("m");
        assertThat(t.sleeps).containsExactly(Duration.ofSeconds(52));
    }

    @Test
    void windowsArePerModel() {
        FakeTime t = new FakeTime();
        RateLimiter rl = new RateLimiter(1, t, t);
        rl.acquire("a");
        rl.acquire("b");
        assertThat(t.sleeps).isEmpty();
    }
}
