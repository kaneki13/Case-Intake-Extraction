package com.caseintake.duplicates;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.output.FieldResult;
import com.caseintake.output.FieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueComparatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ValueComparator comparator = new ValueComparator(1);

    private static FieldDef field(FieldType type) {
        return new FieldDef("f", "F", "s", type, List.of(), null, null);
    }

    private static FieldResult extracted(String json) {
        try {
            JsonNode value = JSON.readTree(json);
            return new FieldResult(FieldStatus.EXTRACTED, value, "high", List.of(), null);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static FieldResult absent(FieldStatus status) {
        return new FieldResult(status, null, "high", List.of(), null);
    }

    private Comparison cmp(FieldType type, String a, String b) {
        return comparator.compare(field(type), extracted(a), extracted(b));
    }

    @Test
    void stringIsEqualAfterTrimAndCaseFold() {
        assertThat(cmp(FieldType.STRING, "\" Digoxin \"", "\"DIGOXIN\"")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.STRING, "\"digoxin\"", "\"digitoxin\"")).isEqualTo(Comparison.DIFFER);
    }

    @Test
    void integerIsEqualWithinTolerance() {
        assertThat(cmp(FieldType.INTEGER, "81", "82")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.INTEGER, "81", "83")).isEqualTo(Comparison.DIFFER);
        assertThat(new ValueComparator(0).compare(field(FieldType.INTEGER), extracted("81"), extracted("82")))
                .isEqualTo(Comparison.DIFFER);
    }

    @Test
    void numberIsNumericallyEqual() {
        assertThat(cmp(FieldType.NUMBER, "0.50", "0.5")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.NUMBER, "500", "500.0")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.NUMBER, "0.5", "0.25")).isEqualTo(Comparison.DIFFER);
    }

    @Test
    void dateAndCodedMustBeEqual() {
        assertThat(cmp(FieldType.DATE, "\"2026-01-05\"", "\"2026-01-05\"")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.DATE, "\"2026-01-05\"", "\"2026-01-06\"")).isEqualTo(Comparison.DIFFER);
        assertThat(cmp(FieldType.CODED, "\"female\"", "\"female\"")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.CODED, "\"female\"", "\"male\"")).isEqualTo(Comparison.DIFFER);
    }

    @Test
    void codedMultiIsEqualAsSets() {
        assertThat(cmp(FieldType.CODED_MULTI, "[\"a\",\"b\"]", "[\"b\",\"a\"]")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.CODED_MULTI, "[\"a\",\"b\"]", "[\"a\"]")).isEqualTo(Comparison.DIFFER);
    }

    @Test
    void stringListMatchesWhenBothEmptyOrOverlapping() {
        assertThat(cmp(FieldType.STRING_LIST, "[]", "[]")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.STRING_LIST, "[\"Rash\",\"nausea\"]", "[\" rash \"]")).isEqualTo(Comparison.MATCH);
        assertThat(cmp(FieldType.STRING_LIST, "[\"rash\"]", "[\"nausea\"]")).isEqualTo(Comparison.DIFFER);
        assertThat(cmp(FieldType.STRING_LIST, "[]", "[\"rash\"]")).isEqualTo(Comparison.DIFFER);
    }

    @Test
    void unknownVsExtractedIsOneSided() {
        assertThat(comparator.compare(field(FieldType.STRING), absent(FieldStatus.UNKNOWN), extracted("\"x\"")))
                .isEqualTo(Comparison.ONE_SIDED);
        assertThat(comparator.compare(field(FieldType.STRING), extracted("\"x\""), absent(FieldStatus.CONFLICT)))
                .isEqualTo(Comparison.ONE_SIDED);
    }

    @Test
    void bothUnknownIsBothAbsent() {
        assertThat(comparator.compare(field(FieldType.STRING), absent(FieldStatus.UNKNOWN),
                absent(FieldStatus.CONFLICT))).isEqualTo(Comparison.BOTH_ABSENT);
    }
}
