package com.caseintake.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.fixtures.ExchangeKey;
import org.junit.jupiter.api.Test;

class DocumentIdTest {
    @Test
    void acceptsShortSafeIdsOnly() {
        assertThat(DocumentId.PATTERN.pattern()).isEqualTo("^[A-Za-z0-9_-]{1,32}$");
        assertThat(DocumentId.isValid("D01")).isTrue();
        assertThat(DocumentId.isValid("a_b-9")).isTrue();
        assertThat(DocumentId.isValid("a".repeat(32))).isTrue();
        assertThat(DocumentId.isValid("a".repeat(33))).isFalse();
        assertThat(DocumentId.isValid("")).isFalse();
        assertThat(DocumentId.isValid("../x")).isFalse();
        assertThat(DocumentId.isValid("bad id")).isFalse();
        assertThat(DocumentId.isValid(null)).isFalse();
    }

    @Test
    void exchangeKeysUseTheSharedPattern() {
        assertThat(new ExchangeKey("a".repeat(32), "cases").documentId()).hasSize(32);
        assertThatThrownBy(() -> new ExchangeKey("a".repeat(33), "cases"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DocumentId.PATTERN.pattern());
    }
}
