package com.caseintake.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class RetryPolicyTest {
    private final List<Duration> sleeps = new ArrayList<>();
    private final Random zeroJitter = new Random() {
        @Override public double nextDouble() { return 0; }
    };
    private final LlmResponse OK = new LlmResponse("m", 200, "", "", "", "t");

    private RetryPolicy policy(int max) {
        return new RetryPolicy(new LlmProperties.Retry(max, Duration.ofSeconds(2), Duration.ofSeconds(10)),
                sleeps::add, zeroJitter);
    }

    private static Supplier<LlmResponse> failing(AtomicInteger calls, RuntimeException e, LlmResponse ok, int failures) {
        return () -> {
            if (calls.incrementAndGet() <= failures) {
                throw e;
            }
            return ok;
        };
    }

    @Test
    void honoursServerRetryDelay() {
        String body = "{\"error\":{\"details\":[{\"@type\":\"x.RetryInfo\",\"retryDelay\":\"17s\"}]}}";
        var calls = new AtomicInteger();
        assertThat(policy(3).execute(failing(calls, new LlmHttpException(429, body), OK, 1))).isSameAs(OK);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isGreaterThanOrEqualTo(Duration.ofSeconds(17));
    }

    @Test
    void parsesFractionalDelay() {
        String body = "{\"error\":{\"details\":[{\"retryDelay\":\"1.5s\"}]}}";
        policy(2).execute(failing(new AtomicInteger(), new LlmHttpException(429, body), OK, 1));
        assertThat(sleeps).containsExactly(Duration.ofMillis(2000)); // backoff 2s > 1.5s
    }

    @Test
    void exponentialBackoffCappedAtMax() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(5).execute(failing(calls, new LlmHttpException(503, "x"), OK, 99)))
                .isInstanceOf(LlmException.class).hasMessageContaining("5 attempts");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(10));
        assertThat(calls).hasValue(5);
    }

    @Test
    void jitterComesFromRandomWithinInitialBackoff() {
        RetryPolicy p = new RetryPolicy(new LlmProperties.Retry(2, Duration.ofSeconds(2), Duration.ofSeconds(10)),
                sleeps::add, new Random() {
                    @Override public double nextDouble() { return 0.5; }
                });
        p.execute(failing(new AtomicInteger(), new LlmHttpException(500, "x"), OK, 1));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void perDayQuotaThrowsWithoutRetry() {
        String body = "{\"error\":{\"details\":[{\"violations\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}";
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(5).execute(failing(calls, new LlmHttpException(429, body), OK, 99)))
                .isInstanceOf(DailyQuotaExhaustedException.class)
                .hasMessageContaining("GenerateRequestsPerDayPerProjectPerModel-FreeTier");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void nonRetryable4xxThrowsImmediately() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(5).execute(failing(calls, new LlmHttpException(400, "bad"), OK, 99)))
                .isInstanceOf(LlmHttpException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void nonHttpLlmExceptionRethrownImmediately() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(5).execute(failing(calls, new LlmException("finishReason=SAFETY"), OK, 99)))
                .hasMessage("finishReason=SAFETY");
        assertThat(calls).hasValue(1);
    }

    @Test
    void unparseableBodyFallsBackToBackoff() {
        policy(2).execute(failing(new AtomicInteger(), new LlmHttpException(429, "<html>"), OK, 1));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void timeoutIsRetried() {
        var e = new ResourceAccessException("t", new SocketTimeoutException("read"));
        var calls = new AtomicInteger();
        assertThat(policy(3).execute(failing(calls, e, OK, 2))).isSameAs(OK);
        assertThat(calls).hasValue(3);
    }

    @Test
    void constructorRejectsInvalidSettings() {
        Duration two = Duration.ofSeconds(2);
        assertThatThrownBy(() -> new RetryPolicy(new LlmProperties.Retry(0, two, two), sleeps::add, zeroJitter))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("max-attempts must be >= 1, got 0");
        assertThatThrownBy(() -> new RetryPolicy(new LlmProperties.Retry(3, Duration.ofSeconds(-1), two),
                sleeps::add, zeroJitter))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("initial-backoff must be >= 0, got PT-1S");
        assertThatThrownBy(() -> new RetryPolicy(new LlmProperties.Retry(3, two, null), sleeps::add, zeroJitter))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("max-backoff must be >= 0, got null");
        assertThat(new RetryPolicy(new LlmProperties.Retry(1, Duration.ZERO, Duration.ZERO), sleeps::add, zeroJitter))
                .isNotNull();
    }
}
