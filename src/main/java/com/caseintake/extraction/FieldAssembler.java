package com.caseintake.extraction;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.output.FieldResult;
import com.caseintake.output.FieldStatus;
import com.caseintake.output.Provenance;
import com.caseintake.text.QuoteLocator;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.caseintake.validation.FieldValidator;
import com.caseintake.validation.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Turns the model's per-field answers into validated results (LLD §9.2). Iterates the catalog, never the response:
 * every catalog field gets a result, and response keys outside the catalog are ignored. A value that fails
 * validation or cannot be tied to a located quote becomes unknown; confidence is only ever lowered.
 */
public final class FieldAssembler {
    static final String NOT_COVERED = "Not covered by the recorded model response.";
    static final String CONFLICT_WITHOUT_CANDIDATES = "Model reported conflict without two candidates.";

    private FieldAssembler() {}

    public static LinkedHashMap<String, FieldResult> assemble(Catalog catalog, JsonNode fieldsNode, SourceText text,
                                                              OptionalInt anchorCp, DocumentHints hints) {
        if (fieldsNode == null || !fieldsNode.isObject()) {
            throw new ResponseShapeException("fields missing");
        }
        LinkedHashMap<String, FieldResult> out = new LinkedHashMap<>();
        for (FieldDef def : catalog.fields()) {
            JsonNode node = fieldsNode.get(def.id());
            out.put(def.id(), node == null
                    ? unknown(NOT_COVERED)
                    : assembleOne(catalog, def, RawFieldAnswer.parse(def.id(), node), text, anchorCp, hints));
        }
        return out;
    }

    private static FieldResult assembleOne(Catalog catalog, FieldDef def, RawFieldAnswer a, SourceText text,
                                           OptionalInt anchorCp, DocumentHints hints) {
        return switch (a.status()) {
            case UNKNOWN -> unknown(a.reason() == null || a.reason().isBlank() ? null : a.reason().trim());
            case CONFLICT -> conflict(a.candidates());
            case EXTRACTED -> extracted(catalog, def, a, text, anchorCp, hints);
        };
    }

    private static FieldResult conflict(List<String> candidates) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String c : candidates) {
            String t = c.trim();
            if (!t.isEmpty()) {
                distinct.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
            }
        }
        if (distinct.size() < 2) {
            return unknown(CONFLICT_WITHOUT_CANDIDATES);
        }
        return new FieldResult(FieldStatus.CONFLICT, null, null, List.of(),
                "Candidates: " + String.join("; ", distinct.values()));
    }

    private static FieldResult extracted(Catalog catalog, FieldDef def, RawFieldAnswer a, SourceText text,
                                         OptionalInt anchorCp, DocumentHints hints) {
        String shown = a.value() == null ? "null" : a.value().toString();
        ValidationResult valid = FieldValidator.validate(def, a.value());
        if (!valid.ok()) {
            return unknown("Model value " + shown + " rejected: " + valid.error() + ".");
        }
        Set<TextSpan> located = new LinkedHashSet<>();
        for (String q : a.quotes()) {
            QuoteLocator.locate(text, q, anchorCp).ifPresent(located::add);
        }
        if (located.isEmpty()) {
            return unknown("Model value " + shown + " rejected: quote not found in document.");
        }

        List<String> notes = new ArrayList<>();
        List<String> levels = catalog.confidenceLevels();
        String lowest = levels.get(levels.size() - 1);
        String start = a.confidence();
        if (start == null || start.isBlank()) {
            notes.add("Model gave no confidence; treated as " + lowest + ".");
            start = lowest;
        } else if (!levels.contains(start)) {
            notes.add("Model confidence " + start + " not in catalog; treated as " + lowest + ".");
            start = lowest;
        }
        List<TextSpan> spans = List.copyOf(located);
        ConfidencePolicy.Capped capped =
                ConfidencePolicy.apply(def, a.value(), a.derived(), spans, text, hints, start, catalog);
        if (!capped.level().equals(start)) {
            notes.add("Confidence lowered: " + String.join(", ", capped.reasons()) + ".");
        }

        List<Provenance> provenance = spans.stream()
                .map(s -> new Provenance(text.substring(s.start(), s.end()), s.start(), s.end()))
                .toList();
        return new FieldResult(FieldStatus.EXTRACTED, a.value(), capped.level(), provenance,
                notes.isEmpty() ? null : String.join(" ", notes));
    }

    private static FieldResult unknown(String note) {
        return new FieldResult(FieldStatus.UNKNOWN, null, null, List.of(), note);
    }
}
