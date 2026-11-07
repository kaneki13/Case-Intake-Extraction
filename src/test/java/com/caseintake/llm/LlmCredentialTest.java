package com.caseintake.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmCredentialTest {
    @Test
    void presenceAndToStringNeverShowTheSecret() {
        LlmCredential c = new LlmCredential("super-secret", "SOME_API_KEY", Set.of("x-auth"));
        assertThat(c.isPresent()).isTrue();
        assertThat(c.toString()).doesNotContain("super-secret").contains("SOME_API_KEY", "x-auth", "***");
        assertThat(new LlmCredential(" ", "SOME_API_KEY", Set.of()).isPresent()).isFalse();
        assertThat(new LlmCredential(null, "SOME_API_KEY", Set.of()).toString()).contains("<empty>");
    }
}
