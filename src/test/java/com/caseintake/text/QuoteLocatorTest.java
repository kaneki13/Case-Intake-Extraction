package com.caseintake.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class QuoteLocatorTest {
    private static final Path DOC = Path.of("documents/D01.txt");

    private static SourceText d01() throws Exception {
        return SourceText.read("D01", DOC);
    }

    private static String ws(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    @Test
    void exactMatch() throws Exception {
        var span = QuoteLocator.locate(d01(), "Mrs M.K.", OptionalInt.empty());
        assertThat(span).contains(new TextSpan(276, 284));
    }

    @Test
    void foldsCaseAndWhitespace() throws Exception {
        var span = QuoteLocator.locate(d01(), "54-YEAR-OLD   woman", OptionalInt.empty());
        assertThat(span).contains(new TextSpan(288, 305));
    }

    @Test
    void emojiBeforeQuoteCountsOneCodePoint() {
        SourceText t = SourceText.of("X", "😀 hello world");
        assertThat(t.length()).isEqualTo(13);
        assertThat(QuoteLocator.locate(t, "hello", OptionalInt.empty())).contains(new TextSpan(2, 7));
    }

    @Test
    void bomAndCrlfCountAsCodePoints() {
        SourceText t = SourceText.of("X", "﻿a\r\nbcd");
        assertThat(QuoteLocator.locate(t, "bcd", OptionalInt.empty())).contains(new TextSpan(4, 7));
    }

    @Test
    void picksOccurrenceNearestAnchor() {
        SourceText t = SourceText.of("X", "fever one. fever two. fever three.");
        assertThat(QuoteLocator.locate(t, "fever", OptionalInt.empty())).contains(new TextSpan(0, 5));
        assertThat(QuoteLocator.locate(t, "fever", OptionalInt.of(23))).contains(new TextSpan(22, 27));
        assertThat(QuoteLocator.locate(t, "fever", OptionalInt.of(100))).contains(new TextSpan(22, 27));
        assertThat(QuoteLocator.locate(t, "fever", OptionalInt.of(16))).contains(new TextSpan(11, 16));
    }

    @Test
    void rejectsQuoteShorterThanThreeChars() {
        SourceText t = SourceText.of("X", "ab abc");
        assertThat(QuoteLocator.locate(t, "ab", OptionalInt.empty())).isEmpty();
        assertThat(QuoteLocator.locate(t, "  ab  ", OptionalInt.empty())).isEmpty();
        assertThat(QuoteLocator.locate(t, "abc", OptionalInt.empty())).contains(new TextSpan(3, 6));
    }

    @Test
    void quoteShorterThanThreeAfterTrailingDotsIsRejected() {
        SourceText t = SourceText.of("X", "ab. ... abc.");
        assertThat(QuoteLocator.locate(t, "ab.", OptionalInt.empty())).isEmpty();
        assertThat(QuoteLocator.locate(t, "...", OptionalInt.empty())).isEmpty();
        assertThat(QuoteLocator.locate(t, "ab...", OptionalInt.empty())).isEmpty();
    }

    @Test
    void quoteWithTrailingDotAndThreeLettersIsLocated() {
        SourceText t = SourceText.of("X", "see abc. now");
        assertThat(QuoteLocator.locate(t, "abc.", OptionalInt.empty())).contains(new TextSpan(4, 8));
    }

    @Test
    void smartQuotesFold() {
        SourceText t = SourceText.of("X", "She said “no rash” and it’s – fine");
        assertThat(QuoteLocator.locate(t, "\"no rash\"", OptionalInt.empty())).contains(new TextSpan(9, 18));
        assertThat(QuoteLocator.locate(t, "it's - fine", OptionalInt.empty())).isPresent();
        assertThat(QuoteLocator.locate(SourceText.of("X", "say \"hi there\""), "“hi there”", OptionalInt.empty()))
                .isPresent();
    }

    @Test
    void ellipsisQuoteIsNotLocated() {
        SourceText t = SourceText.of("X", "rash on the arm for days, then resolved");
        assertThat(QuoteLocator.locate(t, "rash … resolved", OptionalInt.empty())).isEmpty();
        assertThat(QuoteLocator.locate(t, "rash ... resolved", OptionalInt.empty())).isEmpty();
    }

    @Test
    void returnedSpanTextNormalisesToQuote() throws Exception {
        SourceText t = d01();
        JsonNode root = new ObjectMapper().readTree(Path.of("schema/EXAMPLE-output-D01.json").toFile());
        int[] count = {0};
        walk(root, node -> {
            if (node.has("quote") && node.has("start") && node.has("end")) {
                String quote = node.get("quote").asText();
                var span = QuoteLocator.locate(t, quote, OptionalInt.of(node.get("start").asInt()));
                assertThat(span).as(quote).isPresent();
                assertThat(ws(t.substring(span.get().start(), span.get().end()))).isEqualTo(ws(quote));
                count[0]++;
            }
        });
        assertThat(count[0]).isGreaterThan(0);
    }

    private static void walk(JsonNode n, java.util.function.Consumer<JsonNode> f) {
        f.accept(n);
        n.forEach(c -> walk(c, f));
    }
}
