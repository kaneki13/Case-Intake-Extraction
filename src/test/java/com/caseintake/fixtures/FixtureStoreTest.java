package com.caseintake.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureStoreTest {
    @TempDir Path dir;
    static final ExchangeKey KEY = new ExchangeKey("D01", "cases");
    final ObjectMapper om = new ObjectMapper();

    FixtureRecord rec(int attempt, String text) throws Exception {
        return new FixtureRecord("D01", "cases", attempt, "gemini", "m", "1.0", "h", "2026-01-01T00:00:00Z", text,
                new FixtureRecord.Request("u", om.readTree("{}")), new FixtureRecord.Response(200, om.readTree("{}")));
    }

    @Test
    void latestPicksHighestAttempt() throws Exception {
        FixtureStore s = new FixtureStore(dir);
        assertThat(s.nextAttempt(KEY)).isEqualTo(1);
        assertThat(s.latest(KEY)).isEmpty();
        s.write(KEY, 1, rec(1, "one"), "K");
        s.write(KEY, 10, rec(10, "ten"), "K");
        s.write(KEY, 2, rec(2, "two"), "K");
        assertThat(s.latest(KEY).orElseThrow().outputText()).isEqualTo("ten");
        assertThat(s.nextAttempt(KEY)).isEqualTo(11);
        String text = Files.readString(dir.resolve("D01/cases.attempt-2.json"));
        assertThat(text).endsWith("\n").contains("\n  \"document_id\"");
    }

    @Test
    void storeRefusesToWriteWhenKeyStillPresent() throws Exception {
        FixtureStore s = new FixtureStore(dir);
        assertThatThrownBy(() -> s.write(KEY, 1, rec(1, "leak SECRET123"), "SECRET123"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(dir.resolve("D01/cases.attempt-1.json"))).isFalse();
    }

    @Test
    void storeRejectsBlankRedactionKey() throws Exception {
        FixtureStore s = new FixtureStore(dir);
        assertThatThrownBy(() -> s.write(KEY, 1, rec(1, "x"), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.write(KEY, 1, rec(1, "x"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(dir.resolve("D01/cases.attempt-1.json"))).isFalse();
    }

    @Test
    void keyRejectsPathSegments() {
        assertThatThrownBy(() -> new ExchangeKey("../x", "cases")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExchangeKey("D01", "a/b")).isInstanceOf(IllegalArgumentException.class);
    }
}
