package com.caseintake.duplicates;

import com.caseintake.catalog.FieldDef;
import com.caseintake.output.FieldResult;
import com.caseintake.output.FieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Compares one field of two cases by catalog type (STRETCH 2.2). Unknown and conflict values are absent. */
public final class ValueComparator {
    private final int integerTolerance;

    public ValueComparator(int integerTolerance) {
        this.integerTolerance = integerTolerance;
    }

    public Comparison compare(FieldDef field, FieldResult a, FieldResult b) {
        boolean hasA = present(a);
        boolean hasB = present(b);
        if (!hasA && !hasB) {
            return Comparison.BOTH_ABSENT;
        }
        if (hasA != hasB) {
            return Comparison.ONE_SIDED;
        }
        return equal(field, a.value(), b.value()) ? Comparison.MATCH : Comparison.DIFFER;
    }

    static boolean present(FieldResult r) {
        return r != null && r.status() == FieldStatus.EXTRACTED && r.value() != null && !r.value().isNull();
    }

    private boolean equal(FieldDef field, JsonNode a, JsonNode b) {
        return switch (field.type()) {
            case STRING -> fold(text(field, a)).equals(fold(text(field, b)));
            case INTEGER -> Math.abs(integer(field, a) - integer(field, b)) <= integerTolerance;
            case NUMBER -> number(field, a).compareTo(number(field, b)) == 0;
            case DATE, CODED -> text(field, a).equals(text(field, b));
            case CODED_MULTI -> set(field, a, false).equals(set(field, b, false));
            case STRING_LIST -> {
                Set<String> x = set(field, a, true);
                Set<String> y = set(field, b, true);
                yield (x.isEmpty() && y.isEmpty()) || x.stream().anyMatch(y::contains);
            }
        };
    }

    private static String fold(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(FieldDef f, JsonNode n) {
        if (!n.isTextual()) {
            throw malformed(f, "a string");
        }
        return n.asText();
    }

    private static long integer(FieldDef f, JsonNode n) {
        if (!n.isIntegralNumber()) {
            throw malformed(f, "an integer");
        }
        return n.asLong();
    }

    private static BigDecimal number(FieldDef f, JsonNode n) {
        if (!n.isNumber()) {
            throw malformed(f, "a number");
        }
        return n.decimalValue();
    }

    private static Set<String> set(FieldDef f, JsonNode n, boolean folded) {
        if (!n.isArray()) {
            throw malformed(f, "an array");
        }
        Set<String> out = new HashSet<>();
        for (JsonNode item : n) {
            String s = text(f, item);
            out.add(folded ? fold(s) : s);
        }
        return out;
    }

    private static IllegalStateException malformed(FieldDef f, String expected) {
        return new IllegalStateException("an extracted value of a " + f.type().json() + " field is not " + expected);
    }

    /** The value as compact text: lists joined with "/", numbers without trailing zeros. */
    static String render(FieldDef field, JsonNode value) {
        if (value.isArray()) {
            return StreamSupport.stream(value.spliterator(), false).map(JsonNode::asText)
                    .collect(Collectors.joining("/"));
        }
        if (value.isNumber() && !value.isIntegralNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        return value.asText();
    }
}
