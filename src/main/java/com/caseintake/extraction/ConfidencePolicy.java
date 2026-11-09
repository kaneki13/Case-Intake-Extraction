package com.caseintake.extraction;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic confidence caps (LLD §9.3). Confidence can only go down: the final level is the minimum of the
 * starting level and every rule's cap. A rule is reported only when its own cap is below the starting level.
 * LITERAL_ABSENT also covers a list item not written in any quote; LIST_FROM_MULTIPLE_QUOTES and OPTION_NOT_LITERAL
 * cover lists assembled from several places and option mappings. TRANSCRIBED_SOURCE caps every value read from a model
 * transcript of an image-only document at the second level.
 */
public final class ConfidencePolicy {
    public static final String DERIVED = "DERIVED";
    public static final String AMBIGUOUS_NUMERIC_DATE = "AMBIGUOUS_NUMERIC_DATE";
    public static final String LITERAL_ABSENT = "LITERAL_ABSENT";
    public static final String LIST_FROM_MULTIPLE_QUOTES = "LIST_FROM_MULTIPLE_QUOTES";
    public static final String OPTION_NOT_LITERAL = "OPTION_NOT_LITERAL";
    public static final String TRANSCRIBED_SOURCE = "TRANSCRIBED_SOURCE";

    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b");

    public record Capped(String level, List<String> reasons) {}

    private ConfidencePolicy() {}

    public static Capped apply(FieldDef def, JsonNode value, boolean derived, List<TextSpan> spans, SourceText text,
                               DocumentHints hints, String startLevel, Catalog catalog) {
        List<String> levels = catalog.confidenceLevels();
        String medium = levels.get(Math.min(1, levels.size() - 1));
        String lowest = levels.get(levels.size() - 1);
        List<String> spanTexts = spans.stream().map(s -> text.substring(s.start(), s.end())).toList();

        String level = startLevel;
        List<String> reasons = new ArrayList<>();
        if (derived) {
            level = cap(catalog, startLevel, level, medium, DERIVED, reasons);
        }
        if (def.type() == FieldType.DATE && spanTexts.stream().anyMatch(ConfidencePolicy::hasAmbiguousNumericDate)) {
            boolean hinted = hints.dateConvention() != DateConvention.UNKNOWN && hints.evidenceLocated();
            level = cap(catalog, startLevel, level, hinted ? medium : lowest, AMBIGUOUS_NUMERIC_DATE, reasons);
        }
        if (def.type() == FieldType.STRING_LIST && spans.stream().distinct().count() > 1) {
            level = cap(catalog, startLevel, level, medium, LIST_FROM_MULTIPLE_QUOTES, reasons);
        }
        Pattern literal = literalToken(def.type(), value);
        if ((literal != null && spanTexts.stream().noneMatch(s -> literal.matcher(s).find()))
                || (def.type() == FieldType.STRING_LIST && hasItemNotWritten(value, spanTexts))) {
            level = cap(catalog, startLevel, level, catalog.lowerConfidence(startLevel, 1), LITERAL_ABSENT, reasons);
        }
        if ((def.type() == FieldType.CODED || def.type() == FieldType.CODED_MULTI)
                && !anyOptionWritten(def, value, spanTexts)) {
            level = cap(catalog, startLevel, level, catalog.lowerConfidence(startLevel, 1), OPTION_NOT_LITERAL,
                    reasons);
        }
        if (hints.sourceTranscribed()) {
            level = cap(catalog, startLevel, level, medium, TRANSCRIBED_SOURCE, reasons);
        }
        return new Capped(level, List.copyOf(reasons));
    }

    private static String cap(Catalog catalog, String start, String current, String ruleCap, String rule,
                              List<String> reasons) {
        if (!catalog.minConfidence(start, ruleCap).equals(start)) {
            reasons.add(rule);
        }
        return catalog.minConfidence(current, ruleCap);
    }

    /**
     * True when some list item is not written in any quote. Case and runs of whitespace are ignored, so a stated term
     * copied as written passes, and a term the model composed or reworded does not.
     */
    private static boolean hasItemNotWritten(JsonNode value, List<String> spanTexts) {
        List<String> quotes = spanTexts.stream().map(ConfidencePolicy::fold).toList();
        for (JsonNode item : value) {
            String folded = fold(item.asText());
            if (quotes.stream().noneMatch(q -> q.contains(folded))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when some selected option is written in a quote as its code, its code with underscores read as spaces, or
     * its label, as whole words. Case and runs of whitespace are ignored.
     */
    private static boolean anyOptionWritten(FieldDef def, JsonNode value, List<String> spanTexts) {
        List<String> quotes = spanTexts.stream().map(ConfidencePolicy::fold).toList();
        List<String> codes = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(v -> codes.add(v.asText()));
        } else {
            codes.add(value.asText());
        }
        for (String code : codes) {
            List<String> words = new ArrayList<>(List.of(code, code.replace('_', ' ')));
            def.options().stream().filter(o -> o.code().equals(code)).forEach(o -> words.add(o.label()));
            for (String w : words) {
                Pattern whole = Pattern.compile(
                        "(?<![\\p{L}\\p{N}])" + Pattern.quote(fold(w)) + "(?![\\p{L}\\p{N}])");
                if (quotes.stream().anyMatch(q -> whole.matcher(q).find())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String fold(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean hasAmbiguousNumericDate(String s) {
        Matcher m = NUMERIC_DATE.matcher(s);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a <= 12 && b <= 12 && a != b) {
                return true;
            }
        }
        return false;
    }

    /**
     * The number token the value must appear as in some quote, or null when the rule does not apply. A number may be
     * written with trailing zeros ("2.50" for 2.5, "250.0" for 250); a date is checked by its day of month.
     */
    private static Pattern literalToken(FieldType type, JsonNode value) {
        return switch (type) {
            case INTEGER, NUMBER -> {
                String plain = new BigDecimal(value.asText()).stripTrailingZeros().toPlainString();
                String zeros = plain.contains(".") ? "0*" : "(?:\\.0+)?";
                yield Pattern.compile("(?<![\\d.])" + Pattern.quote(plain) + zeros + "(?!\\d|\\.\\d)");
            }
            case DATE -> Pattern.compile("(?<!\\d)0?" + LocalDate.parse(value.asText()).getDayOfMonth() + "(?!\\d)");
            default -> null;
        };
    }
}
