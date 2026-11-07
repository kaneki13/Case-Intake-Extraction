package com.caseintake.extraction;

import com.caseintake.text.QuoteLocator;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns the stage-1 model response into verified cases. A case is kept only when its anchor and all four
 * minimum elements are quoted and the quotes can be located in the document.
 */
public final class CaseFinder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LABEL_MAX = 40;
    private static final int REASON_MAX = 200;

    private CaseFinder() {}

    public static CaseFinding find(SourceText text, String outputText) {
        JsonNode root = parse(outputText);
        JsonNode patients = root.get("patients");
        if (patients == null || !patients.isArray()) {
            throw new ResponseShapeException("patients missing or not an array");
        }
        JsonNode instructions = root.get("embedded_instructions");
        if (instructions == null || !instructions.isArray()) {
            throw new ResponseShapeException("embedded_instructions missing or not an array");
        }
        JsonNode reasonNode = root.get("no_case_reason");
        if (reasonNode != null && !reasonNode.isNull() && !reasonNode.isTextual()) {
            throw new ResponseShapeException("no_case_reason not a string");
        }
        DocumentHints hints = hints(text, root.get("date_convention"));

        List<CaseCandidate> kept = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int dropped = 0;
        boolean allDroppedMissingEvent = true;
        for (int i = 0; i < patients.size(); i++) {
            Candidate c = candidate(text, patients.get(i), "patients[" + i + "]");
            if (c.missing.isEmpty()) {
                kept.add(c.kept);
            } else {
                dropped++;
                allDroppedMissingEvent &= c.missingEvent;
                notes.add("Patient \"" + truncate(c.label, LABEL_MAX) + "\" dropped: missing "
                        + String.join(", ", c.missing));
            }
        }

        EnumSet<DocumentFlag> flags = EnumSet.noneOf(DocumentFlag.class);
        if (patients.isEmpty()) {
            flags.add(DocumentFlag.NO_ADVERSE_EVENT);
            if (reasonNode != null && reasonNode.isTextual() && !reasonNode.asText().isBlank()) {
                notes.add("No case: " + truncate(reasonNode.asText(), REASON_MAX));
            }
        } else if (kept.isEmpty()) {
            flags.add(allDroppedMissingEvent ? DocumentFlag.NO_ADVERSE_EVENT : DocumentFlag.MISSING_MINIMUM_ELEMENTS);
        } else if (dropped > 0) {
            flags.add(DocumentFlag.MISSING_MINIMUM_ELEMENTS);
        }
        if (kept.size() > 1) {
            flags.add(DocumentFlag.MULTIPLE_PATIENTS);
        }
        for (JsonNode quote : instructions) {
            if (!quote.isTextual()) {
                throw new ResponseShapeException("embedded_instructions item not a string");
            }
            if (QuoteLocator.locate(text, quote.asText(), OptionalInt.empty()).isPresent()) {
                flags.add(DocumentFlag.EMBEDDED_INSTRUCTIONS_IGNORED);
            }
        }
        return new CaseFinding(kept, hints, flags, notes);
    }

    private record Candidate(String label, CaseCandidate kept, List<String> missing, boolean missingEvent) {}

    private static Candidate candidate(SourceText text, JsonNode p, String path) {
        if (!p.isObject()) {
            throw new ResponseShapeException(path + " not an object");
        }
        String label = requiredString(p, "label", path);
        String anchorQuote = requiredString(p, "anchor_quote", path);
        Optional<TextSpan> anchor = QuoteLocator.locate(text, anchorQuote, OptionalInt.empty());
        OptionalInt near = anchor.isPresent() ? OptionalInt.of(anchor.get().start()) : OptionalInt.empty();

        List<String> missing = new ArrayList<>();
        if (anchor.isEmpty()) {
            missing.add("anchor");
        }
        Map<MinimumElement, TextSpan> elements = new EnumMap<>(MinimumElement.class);
        boolean missingEvent = false;
        for (MinimumElement e : MinimumElement.values()) {
            String key = e.quoteKey();
            JsonNode n = p.get(key);
            if (n == null) {
                throw new ResponseShapeException(path + "." + key + " missing");
            }
            if (!n.isNull() && !n.isTextual()) {
                throw new ResponseShapeException(path + "." + key + " not a string or null");
            }
            Optional<TextSpan> span = n.isNull() ? Optional.empty() : QuoteLocator.locate(text, n.asText(), near);
            if (span.isPresent()) {
                elements.put(e, span.get());
            } else {
                missing.add(e.name().toLowerCase(Locale.ROOT).replace('_', ' '));
                missingEvent |= e == MinimumElement.ADVERSE_EVENT;
            }
        }
        CaseCandidate kept = missing.isEmpty() ? new CaseCandidate(label, anchor.get(), elements) : null;
        return new Candidate(label, kept, missing, missingEvent);
    }

    private static DocumentHints hints(SourceText text, JsonNode dc) {
        if (dc == null || !dc.isObject()) {
            throw new ResponseShapeException("date_convention missing or not an object");
        }
        JsonNode value = dc.get("value");
        if (value == null || !value.isTextual()) {
            throw new ResponseShapeException("date_convention.value missing or not a string");
        }
        DateConvention convention;
        try {
            convention = DateConvention.fromJson(value.asText());
        } catch (IllegalArgumentException ex) {
            throw new ResponseShapeException("date_convention.value not one of day_first, month_first, unknown");
        }
        JsonNode ev = dc.get("evidence_quote");
        if (ev != null && !ev.isNull() && !ev.isTextual()) {
            throw new ResponseShapeException("date_convention.evidence_quote not a string or null");
        }
        boolean located = ev != null && ev.isTextual()
                && QuoteLocator.locate(text, ev.asText(), OptionalInt.empty()).isPresent();
        return new DocumentHints(convention, located, false);
    }

    private static String requiredString(JsonNode o, String key, String path) {
        JsonNode n = o.get(key);
        if (n == null || n.isNull()) {
            throw new ResponseShapeException(path + "." + key + " missing");
        }
        if (!n.isTextual()) {
            throw new ResponseShapeException(path + "." + key + " not a string");
        }
        return n.asText();
    }

    private static JsonNode parse(String outputText) {
        JsonNode root;
        try {
            root = JSON.readTree(outputText);
        } catch (Exception ex) {
            throw new ResponseShapeException("response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ResponseShapeException("response is not a JSON object");
        }
        return root;
    }

    private static String truncate(String s, int max) {
        int[] cps = s.codePoints().toArray();
        return cps.length <= max ? s : new String(cps, 0, max);
    }
}
