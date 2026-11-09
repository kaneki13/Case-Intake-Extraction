package com.caseintake.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.catalog.OptionDef;
import com.caseintake.catalog.ValidationRules;
import com.caseintake.text.QuoteLocator;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ConfidencePolicyTest {
    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Catalog CATALOG =
            new Catalog("t", List.of(), List.of(), List.of("high", "medium", "low"), Map.of(), null);
    private static final DocumentHints NO_HINT = new DocumentHints(DateConvention.UNKNOWN, false, false);

    private static FieldDef def(FieldType type) {
        return new FieldDef("f", "F", "s", type, List.of(), ValidationRules.NONE, "");
    }

    private static List<TextSpan> spans(SourceText text, String... quotes) {
        return java.util.Arrays.stream(quotes)
                .map(q -> QuoteLocator.locate(text, q, OptionalInt.empty()).orElseThrow())
                .toList();
    }

    private static ConfidencePolicy.Capped apply(FieldDef def, JsonNode value, boolean derived, SourceText text,
                                                 DocumentHints hints, String start, String... quotes) {
        return ConfidencePolicy.apply(def, value, derived, spans(text, quotes), text, hints, start, CATALOG);
    }

    @Test
    void derivedCapsAtMedium() {
        SourceText t = SourceText.of("t", "Born in 1972, reaction in 2026.");
        var c = apply(def(FieldType.INTEGER), J.numberNode(54), true, t, NO_HINT, "high", "Born in 1972");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).contains("DERIVED");
    }

    @Test
    void transcribedSourceCapsAtSecondLevel() {
        SourceText t = SourceText.of("t", "aged 54 years");
        var transcribed = new DocumentHints(DateConvention.UNKNOWN, false, true);

        var high = apply(def(FieldType.INTEGER), J.numberNode(54), false, t, transcribed, "high", "aged 54 years");
        assertThat(high.level()).isEqualTo("medium");
        assertThat(high.reasons()).containsExactly("TRANSCRIBED_SOURCE");

        var low = apply(def(FieldType.INTEGER), J.numberNode(54), false, t, transcribed, "low", "aged 54 years");
        assertThat(low.level()).isEqualTo("low");
        assertThat(low.reasons()).isEmpty();

        var notTranscribed = apply(def(FieldType.INTEGER), J.numberNode(54), false, t, NO_HINT, "high",
                "aged 54 years");
        assertThat(notTranscribed.level()).isEqualTo("high");

        Catalog single = new Catalog("t", List.of(), List.of(), List.of("only"), Map.of(), null);
        var one = ConfidencePolicy.apply(def(FieldType.INTEGER), J.numberNode(54), false,
                spans(t, "aged 54 years"), t, transcribed, "only", single);
        assertThat(one.level()).isEqualTo("only");
        assertThat(one.reasons()).isEmpty();
    }

    @Test
    void derivedDoesNotRaiseLow() {
        SourceText t = SourceText.of("t", "aged 54 years");
        var c = apply(def(FieldType.INTEGER), J.numberNode(54), true, t, NO_HINT, "low", "aged 54 years");
        assertThat(c.level()).isEqualTo("low");
        assertThat(c.reasons()).isEmpty();
    }

    @Test
    void ambiguousNumericDateCapsAtLow() {
        SourceText t = SourceText.of("t", "Onset on 03/04/2026 at home.");
        var c = apply(def(FieldType.DATE), J.textNode("2026-04-03"), false, t, NO_HINT, "high", "03/04/2026");
        assertThat(c.level()).isEqualTo("low");
        assertThat(c.reasons()).containsExactly("AMBIGUOUS_NUMERIC_DATE");
    }

    @Test
    void ambiguousDateWithLocatedHintCapsAtMedium() {
        SourceText t = SourceText.of("t", "Onset on 03/04/2026 at home.");
        var hints = new DocumentHints(DateConvention.DAY_FIRST, true, false);
        var c = apply(def(FieldType.DATE), J.textNode("2026-04-03"), false, t, hints, "high", "03/04/2026");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("AMBIGUOUS_NUMERIC_DATE");
    }

    @Test
    void ambiguousDateWithUnlocatedHintStillCapsAtLow() {
        SourceText t = SourceText.of("t", "Onset on 03/04/2026 at home.");
        var hints = new DocumentHints(DateConvention.DAY_FIRST, false, false);
        var c = apply(def(FieldType.DATE), J.textNode("2026-04-03"), false, t, hints, "high", "03/04/2026");
        assertThat(c.level()).isEqualTo("low");
    }

    @Test
    void unambiguousNumericDateNotCapped() {
        SourceText t = SourceText.of("t", "Onset on 15/05/2026 at home.");
        var c = apply(def(FieldType.DATE), J.textNode("2026-05-15"), false, t, NO_HINT, "high", "15/05/2026");
        assertThat(c.level()).isEqualTo("high");
        assertThat(c.reasons()).isEmpty();
    }

    @Test
    void sameDayAndMonthNotAmbiguous() {
        SourceText t = SourceText.of("t", "Onset on 05/05/2026 at home.");
        var c = apply(def(FieldType.DATE), J.textNode("2026-05-05"), false, t, NO_HINT, "high", "05/05/2026");
        assertThat(c.level()).isEqualTo("high");
    }

    @Test
    void literalAbsentLowersOneLevel() {
        SourceText t = SourceText.of("t", "Dose 250 mcg daily.");
        var c = apply(def(FieldType.NUMBER), J.numberNode(new BigDecimal("0.25")), false, t, NO_HINT, "high", "250 mcg");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("LITERAL_ABSENT");
    }

    @Test
    void literalPresentNumberNotLowered() {
        SourceText t = SourceText.of("t", "Dose 0.25 mg daily.");
        var c = apply(def(FieldType.NUMBER), J.numberNode(new BigDecimal("0.250")), false, t, NO_HINT, "high", "0.25 mg");
        assertThat(c.level()).isEqualTo("high");
        assertThat(c.reasons()).isEmpty();
    }

    @Test
    void literalWithTrailingZerosInTextIsPresent() {
        SourceText t = SourceText.of("t", "Dose 2.50 mg, then 250.0 mg.");
        var c1 = apply(def(FieldType.NUMBER), J.numberNode(new BigDecimal("2.5")), false, t, NO_HINT, "high", "2.50 mg");
        var c2 = apply(def(FieldType.NUMBER), J.numberNode(250), false, t, NO_HINT, "high", "250.0 mg");
        assertThat(c1.level()).isEqualTo("high");
        assertThat(c2.level()).isEqualTo("high");
    }

    @Test
    void literalMustBeWholeNumberToken() {
        SourceText t = SourceText.of("t", "Dose 125 mg, then 25.5 mg.");
        var c = apply(def(FieldType.NUMBER), J.numberNode(25), false, t, NO_HINT, "high", "125 mg", "25.5 mg");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("LITERAL_ABSENT");
    }

    @Test
    void literalAbsentForDateDayOfMonth() {
        SourceText t = SourceText.of("t", "Started last Tuesday, 2026.");
        var c = apply(def(FieldType.DATE), J.textNode("2026-05-15"), false, t, NO_HINT, "medium", "last Tuesday");
        assertThat(c.level()).isEqualTo("low");
        assertThat(c.reasons()).containsExactly("LITERAL_ABSENT");
    }

    @Test
    void dateDayOfMonthWithLeadingZeroIsPresent() {
        SourceText t = SourceText.of("t", "Started 4th March 2026; stopped 2026-03-04.");
        var c1 = apply(def(FieldType.DATE), J.textNode("2026-03-04"), false, t, NO_HINT, "high", "4th March 2026");
        var c2 = apply(def(FieldType.DATE), J.textNode("2026-03-04"), false, t, NO_HINT, "high", "2026-03-04");
        assertThat(c1.level()).isEqualTo("high");
        assertThat(c2.level()).isEqualTo("high");
    }

    @Test
    void literalAbsentDoesNotApplyToStrings() {
        SourceText t = SourceText.of("t", "Patient MK");
        var c = apply(def(FieldType.STRING), J.textNode("XY"), false, t, NO_HINT, "high", "Patient MK");
        assertThat(c.level()).isEqualTo("high");
    }

    @Test
    void listItemNotWrittenInAnyQuoteLowersOneLevel() {
        SourceText t = SourceText.of("t", "She developed a rash, then swelling of the lips and periorbital area.");
        JsonNode items = J.arrayNode().add("rash").add("swelling of the periorbital area");
        var c = apply(def(FieldType.STRING_LIST), items, false, t, NO_HINT, "high",
                "developed a rash, then swelling of the lips and periorbital area");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("LITERAL_ABSENT");
    }

    @Test
    void listItemsWrittenInQuotesIgnoringCaseAndSpacesAreNotLowered() {
        SourceText t = SourceText.of("t", "Concomitant: Omeprazole 20 mg and\nfolic   acid 5 mg.");
        JsonNode items = J.arrayNode().add("omeprazole").add("folic acid");
        var c = apply(def(FieldType.STRING_LIST), items, false, t, NO_HINT, "high",
                "Omeprazole 20 mg and\nfolic   acid 5 mg");
        assertThat(c.level()).isEqualTo("high");
        assertThat(c.reasons()).isEmpty();
    }

    @Test
    void listSupportedByMoreThanOneQuoteCapsAtMedium() {
        SourceText t = SourceText.of("t", "She developed a rash. Later she had a fever.");
        JsonNode items = J.arrayNode().add("rash").add("fever");
        var c = apply(def(FieldType.STRING_LIST), items, false, t, NO_HINT, "high",
                "developed a rash", "she had a fever");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("LIST_FROM_MULTIPLE_QUOTES");
    }

    @Test
    void listSupportedByOneQuoteIsNotCapped() {
        SourceText t = SourceText.of("t", "She developed a rash and a fever.");
        JsonNode items = J.arrayNode().add("rash").add("fever");
        var c = apply(def(FieldType.STRING_LIST), items, false, t, NO_HINT, "high", "a rash and a fever");
        assertThat(c.level()).isEqualTo("high");
        assertThat(c.reasons()).isEmpty();
    }

    private static FieldDef coded(FieldType type) {
        return new FieldDef("f", "F", "s", type,
                List.of(new OptionDef("male", "Male"), new OptionDef("female", "Female"),
                        new OptionDef("not_recovered", "Not recovered"), new OptionDef("oral", "Oral")),
                ValidationRules.NONE, "");
    }

    @Test
    void codedOptionNotWrittenInAnyQuoteLowersOneLevel() {
        SourceText t = SourceText.of("t", "One capsule three times daily.");
        var c = apply(coded(FieldType.CODED), J.textNode("oral"), false, t, NO_HINT, "high",
                "One capsule three times");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("OPTION_NOT_LITERAL");
    }

    @Test
    void codedOptionWrittenAsCodeOrLabelIsNotLowered() {
        SourceText t = SourceText.of("t", "Sex: MALE. Taken  orally? No: oral route. Not  recovered yet.");
        var byCode = apply(coded(FieldType.CODED), J.textNode("male"), false, t, NO_HINT, "high", "Sex: MALE");
        var byLabel = apply(coded(FieldType.CODED), J.textNode("not_recovered"), false, t, NO_HINT, "high",
                "Not  recovered yet");
        assertThat(byCode.level()).isEqualTo("high");
        assertThat(byLabel.level()).isEqualTo("high");
    }

    @Test
    void codedOptionMustMatchAsWholeWord() {
        SourceText t = SourceText.of("t", "Sex: female.");
        var c = apply(coded(FieldType.CODED), J.textNode("male"), false, t, NO_HINT, "high", "Sex: female");
        assertThat(c.level()).isEqualTo("medium");
        assertThat(c.reasons()).containsExactly("OPTION_NOT_LITERAL");
    }

    @Test
    void codedMultiIsLoweredOnlyWhenNoSelectedOptionIsWritten() {
        SourceText t = SourceText.of("t", "She is a female patient, taken by mouth.");
        JsonNode some = J.arrayNode().add("female").add("oral");
        JsonNode none = J.arrayNode().add("oral");
        var c1 = apply(coded(FieldType.CODED_MULTI), some, false, t, NO_HINT, "high",
                "a female patient, taken by mouth");
        var c2 = apply(coded(FieldType.CODED_MULTI), none, false, t, NO_HINT, "high", "taken by mouth");
        assertThat(c1.level()).isEqualTo("high");
        assertThat(c2.level()).isEqualTo("medium");
        assertThat(c2.reasons()).containsExactly("OPTION_NOT_LITERAL");
    }

    @Test
    void minimumOfCapsWins() {
        SourceText t = SourceText.of("t", "Onset on 03/04/2026 at home.");
        var hints = new DocumentHints(DateConvention.DAY_FIRST, true, false);
        // DERIVED caps at medium, ambiguous date with hint caps at medium, LITERAL_ABSENT (day 9) lowers high to medium;
        // without the hint the ambiguous date caps at low, which wins.
        var withHint = apply(def(FieldType.DATE), J.textNode("2026-04-09"), true, t, hints, "high", "03/04/2026");
        var noHint = apply(def(FieldType.DATE), J.textNode("2026-04-09"), true, t, NO_HINT, "high", "03/04/2026");
        assertThat(withHint.level()).isEqualTo("medium");
        assertThat(withHint.reasons()).containsExactly("DERIVED", "AMBIGUOUS_NUMERIC_DATE", "LITERAL_ABSENT");
        assertThat(noHint.level()).isEqualTo("low");
        assertThat(noHint.reasons()).containsExactly("DERIVED", "AMBIGUOUS_NUMERIC_DATE", "LITERAL_ABSENT");
    }

    @Test
    void modelLevelBelowEveryCapIsKept() {
        SourceText t = SourceText.of("t", "Dose 250 mcg daily.");
        var c = apply(def(FieldType.NUMBER), J.numberNode(250), true, t, NO_HINT, "low", "250 mcg");
        assertThat(c.level()).isEqualTo("low");
        assertThat(c.reasons()).isEmpty();
    }
}
