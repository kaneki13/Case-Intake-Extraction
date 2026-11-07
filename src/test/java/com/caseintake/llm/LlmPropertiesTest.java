package com.caseintake.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmPropertiesTest {
    @Test
    void geminiPropertiesToStringHidesKey() {
        var g = new LlmProperties.Gemini("http://x", "super-secret-key", Duration.ofSeconds(1));
        assertThat(g.toString()).doesNotContain("super-secret-key").contains("apiKey=***");
        assertThat(new LlmProperties.Gemini("http://x", "", Duration.ofSeconds(1)).toString()).contains("apiKey=<empty>");
        assertThat(new LlmProperties.Gemini("http://x", null, Duration.ofSeconds(1)).toString()).contains("apiKey=<empty>");
    }
}
