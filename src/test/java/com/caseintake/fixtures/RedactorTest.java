package com.caseintake.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.llm.LlmCredential;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedactorTest {
    static LlmCredential credential(String secret) {
        return new LlmCredential(secret, "TEST_API_KEY", Set.of("x-test-auth"));
    }

    @Test
    void redactsCredentialHeaderAndLiteralKey() {
        String out = Redactor.redact("{\"X-Test-Auth\":\"SECRET123\",\"key\":\"SECRET123\",\"a\":\"u?key=SECRET123&b=1\","
                + "\"t\":\"has SECRET123 inside\"}", credential("SECRET123"));
        assertThat(out).doesNotContain("SECRET123").doesNotContainIgnoringCase("x-test-auth").contains("REDACTED")
                .contains("key=REDACTED&b=1");
    }

    @Test
    void blankKeyOnlyRemovesStructure() {
        String out = Redactor.redact("{\"x-test-auth\":\"zzz\",\"a\":1}", credential(""));
        assertThat(out).isEqualTo("{\"a\":1}");
    }

    @Test
    void onlyTheCredentialsHeaderNamesAreDropped() {
        String out = Redactor.redact("{\"x-other-header\":\"v\",\"a\":1}", credential("S"));
        assertThat(out).isEqualTo("{\"x-other-header\":\"v\",\"a\":1}");
    }
}
