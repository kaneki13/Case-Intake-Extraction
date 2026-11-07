package com.caseintake.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.llm.LlmClient;
import com.caseintake.llm.LlmCredential;
import com.caseintake.llm.LlmRequest;
import com.caseintake.llm.LlmResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingExchangeSourceTest {
    @TempDir Path dir;
    static final ExchangeKey KEY = new ExchangeKey("D01", "cases");
    static final String API = "SECRET123";
    final AtomicInteger calls = new AtomicInteger();
    static final LlmCredential CREDENTIAL = new LlmCredential(API, "TEST_API_KEY", Set.of("x-test-auth"));
    final LlmClient client = new LlmClient() {
        public LlmResponse generate(LlmRequest r) {
            int n = calls.incrementAndGet();
            return new LlmResponse("m", 200, "{\"x-test-auth\":\"" + API + "\",\"p\":1}",
                    n == 3 ? "not json " + API : "{\"o\":" + n + "}", "https://h/x?key=" + API, "out" + n);
        }

        public String providerId() {
            return "test";
        }

        public LlmCredential credential() {
            return CREDENTIAL;
        }
    };
    final Clock clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
    final LlmRequest req = new LlmRequest("m", "sys", "user", null, java.util.List.of());

    RecordingExchangeSource src(boolean force) {
        return new RecordingExchangeSource(client, new FixtureStore(dir), "1.0", force, clock);
    }

    @Test
    void recordingWritesFixtureBeforeReturning() throws Exception {
        Exchange ex = src(false).obtain(KEY, () -> req);
        Exchange disk = new FixtureExchangeSource(new FixtureStore(dir)).obtain(KEY, () -> null);
        assertThat(ex).isEqualTo(disk);
        assertThat(ex.attempt()).isEqualTo(1);
        assertThat(ex.outputText()).isEqualTo("out1");
        String file = Files.readString(dir.resolve("D01/cases.attempt-1.json"));
        assertThat(file).doesNotContain(API).doesNotContain("x-test-auth")
                .contains("\"recorded_at\" : \"2026-11-08T10:00:00Z\"")
                .contains("\"prompt_sha256\"").doesNotContain("fence_marker");
    }

    @Test
    void recordingReusesExistingFixtureWithoutForce() {
        src(false).obtain(KEY, () -> req);
        Exchange again = src(false).obtain(KEY, () -> { throw new AssertionError("supplier used"); });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(again.attempt()).isEqualTo(1);
        assertThat(src(true).obtain(KEY, () -> req).attempt()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retryRecordsNextAttemptAndKeepsNonJsonBodyAsString() throws Exception {
        src(false).obtain(KEY, () -> req);
        src(false).retry(KEY, () -> req);
        Exchange third = src(false).retry(KEY, () -> req);
        assertThat(third.attempt()).isEqualTo(3);
        String file = Files.readString(dir.resolve("D01/cases.attempt-3.json"));
        assertThat(file).contains("\"body\" : \"not json REDACTED\"").doesNotContain(API);
    }

    static LlmClient withSecret(String secret) {
        return new LlmClient() {
            public LlmResponse generate(LlmRequest r) {
                throw new AssertionError("not called");
            }

            public String providerId() {
                return "test";
            }

            public LlmCredential credential() {
                return new LlmCredential(secret, "TEST_API_KEY", Set.of("x-test-auth"));
            }
        };
    }

    @Test
    void blankApiKeyIsRejected() {
        FixtureStore st = new FixtureStore(dir);
        assertThatThrownBy(() -> new RecordingExchangeSource(withSecret(""), st, "1.0", false, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEST_API_KEY");
        assertThatThrownBy(() -> new RecordingExchangeSource(withSecret(null), st, "1.0", false, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnedExchangeComesFromDiskAndOutputIsRedacted() {
        LlmClient leaky = new LlmClient() {
            public LlmResponse generate(LlmRequest r) {
                return new LlmResponse("m", 200, "{}", "{}", "https://h/x", "leak " + API + " end");
            }

            public String providerId() {
                return "test";
            }

            public LlmCredential credential() {
                return CREDENTIAL;
            }
        };
        Exchange ex = new RecordingExchangeSource(leaky, new FixtureStore(dir), "1.0", false, clock)
                .obtain(KEY, () -> req);
        assertThat(ex.outputText()).contains("REDACTED").doesNotContain(API);
    }

    @Test
    void replayMissingFixtureThrows() {
        FixtureExchangeSource replay = new FixtureExchangeSource(new FixtureStore(dir));
        assertThatThrownBy(() -> replay.obtain(KEY, () -> req)).isInstanceOf(FixtureMissingException.class);
        assertThatThrownBy(() -> replay.retry(KEY, () -> req)).isInstanceOf(FixtureMissingException.class);
    }
}
