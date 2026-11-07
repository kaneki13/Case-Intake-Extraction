package com.caseintake.prompt;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.OptionDef;
import com.caseintake.catalog.SectionDef;
import com.caseintake.catalog.ValidationRules;
import com.caseintake.extraction.CaseCandidate;
import com.caseintake.extraction.DocumentHints;
import com.caseintake.extraction.MinimumElement;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the two model prompts (LLD 7.2, 7.3). Fixed rule text comes from {@code prompts/*.md} on the
 * classpath; everything about fields comes from the catalog. The document always sits inside a
 * {@link DocumentFence}; document text quoted outside the fence and the model-supplied case labels are
 * JSON-escaped.
 */
public final class PromptBuilder {
    static final String CASE_FINDING_RESOURCE = "prompts/case-finding.md";
    static final String EXTRACTION_RESOURCE = "prompts/extraction.md";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String caseFindingRules;
    private final String extractionRules;

    public PromptBuilder() {
        this.caseFindingRules = resource(CASE_FINDING_RESOURCE);
        this.extractionRules = resource(EXTRACTION_RESOURCE);
    }

    public Prompt caseFindingPrompt(SourceText text, String marker, Optional<String> repairHint) {
        String system = fill(caseFindingRules, Map.of(
                "fence_open", DocumentFence.open(marker),
                "fence_close", DocumentFence.close(marker)));
        String user = "Find the cases in this document.\n\n" + DocumentFence.wrap(text, marker) + repair(repairHint);
        return new Prompt(system, user);
    }

    public Prompt extractionPrompt(Catalog catalog, SourceText text, CaseCandidate target, List<CaseCandidate> others,
                                   DocumentHints hints, String marker, Optional<String> repairHint) {
        List<String> levels = catalog.confidenceLevels();
        String system = fill(extractionRules, Map.of(
                "fence_open", DocumentFence.open(marker),
                "fence_close", DocumentFence.close(marker),
                "confidence_levels", levels.stream().map(l -> "`" + l + "`").collect(Collectors.joining(", ")),
                "confidence_highest", "`" + levels.get(0) + "`",
                "confidence_lowest", "`" + levels.get(levels.size() - 1) + "`"));

        StringBuilder u = new StringBuilder();
        u.append("Target patient: ").append(jsonString(target.label())).append('\n');
        u.append("Anchor quote: ").append(quoted(text, target.anchor())).append('\n');
        if (!target.elements().isEmpty()) {
            u.append("Evidence found for this patient by an earlier reading:\n");
            for (MinimumElement e : MinimumElement.values()) {
                TextSpan span = target.elements().get(e);
                if (span != null) {
                    u.append("- ").append(e.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(": ")
                            .append(quoted(text, span)).append('\n');
                }
            }
        }
        u.append('\n');
        if (others.isEmpty()) {
            u.append("Other patients in this document: none.\n");
        } else {
            u.append("Other patients in this document. Do not extract anything stated only about them:\n");
            for (CaseCandidate other : others) {
                u.append("- ").append(jsonString(other.label())).append(": ").append(quoted(text, other.anchor()))
                        .append('\n');
            }
        }
        u.append('\n').append(dateConvention(hints)).append("\n\n");

        u.append("Fields to return, by section. Each line gives: id — label — type — options — validation — guidance.\n");
        for (SectionDef section : catalog.sections()) {
            u.append("\n## ").append(section.label()).append(" (").append(section.id()).append(")\n");
            for (FieldDef def : catalog.fieldsInSection(section.id())) {
                u.append(fieldLine(def)).append('\n');
            }
        }
        u.append('\n').append(DocumentFence.wrap(text, marker)).append(repair(repairHint));
        return new Prompt(system, u.toString());
    }

    private static String fieldLine(FieldDef def) {
        String options = def.options().isEmpty() ? "none"
                : def.options().stream().map(PromptBuilder::option).collect(Collectors.joining("; "));
        String guidance = def.guidance() == null || def.guidance().isBlank() ? "none" : def.guidance();
        return "- " + def.id() + " — " + def.label() + " — type: " + type(def) + " — options: " + options
                + " — validation: " + validation(def.validation()) + " — guidance: " + guidance;
    }

    private static String option(OptionDef o) {
        return o.code() + ": " + o.label();
    }

    private static String type(FieldDef def) {
        return switch (def.type()) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case DATE -> "date (string, YYYY-MM-DD)";
            case CODED -> "coded (one option code)";
            case CODED_MULTI -> "coded_multi (array of option codes, each at most once)";
            case STRING_LIST -> "string_list (array of strings)";
        };
    }

    private static String validation(ValidationRules rules) {
        List<String> parts = new ArrayList<>();
        rules.pattern().ifPresent(p -> parts.add("the whole value matches the pattern " + p.pattern()));
        rules.min().ifPresent(m -> parts.add("minimum " + m.toPlainString()));
        rules.max().ifPresent(m -> parts.add("maximum " + m.toPlainString()));
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static String dateConvention(DocumentHints hints) {
        if (hints.evidenceLocated()) {
            switch (hints.dateConvention()) {
                case DAY_FIRST -> {
                    return "Date convention: day first (DD/MM/YYYY), found by an earlier reading with evidence in the document.";
                }
                case MONTH_FIRST -> {
                    return "Date convention: month first (MM/DD/YYYY), found by an earlier reading with evidence in the document.";
                }
                case UNKNOWN -> { }
            }
        }
        return "Date convention: not established by an earlier reading. Work it out from the document.";
    }

    private static String quoted(SourceText text, TextSpan span) {
        return jsonString(text.substring(span.start(), span.end()));
    }

    /** JSON string quoting for anything taken from the document or the model (quotes, case labels). */
    private static String jsonString(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String repair(Optional<String> hint) {
        return hint.map(h -> "\n\nYour previous response was rejected: " + h + ". Return JSON that matches the schema.")
                .orElse("");
    }

    private static String fill(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        if (out.contains("{{")) {
            throw new IllegalStateException("unfilled placeholder in prompt template: "
                    + out.substring(out.indexOf("{{"), Math.min(out.length(), out.indexOf("{{") + 40)));
        }
        return out;
    }

    private static String resource(String name) {
        try (InputStream in = PromptBuilder.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + name);
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                throw new IllegalStateException("empty classpath resource " + name);
            }
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read classpath resource " + name, e);
        }
    }
}
