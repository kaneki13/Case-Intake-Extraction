package com.caseintake.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleExtractorTest {
    @Test
    void titlePrefersSubjectLine() {
        assertThat(TitleExtractor.title("From: a\nSUBJECT:  Rash after dose \nBody")).isEqualTo("Rash after dose");
    }

    @Test
    void titleFallsBackToFirstNonBlankLine() {
        assertThat(TitleExtractor.title("\n  \n  First real line  \nsecond")).isEqualTo("First real line");
        assertThat(TitleExtractor.title("x".repeat(200))).hasSize(120);
    }

    @Test
    void nullTextTitle() {
        assertThat(TitleExtractor.title(null)).isEqualTo("(no text layer)");
    }
}
