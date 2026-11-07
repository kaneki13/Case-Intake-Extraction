package com.caseintake.catalog;

import com.caseintake.output.FieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class CatalogLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STATUS_NAMES = Arrays.stream(FieldStatus.values())
            .map(FieldStatus::json)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> VALIDATION_KEYS = Set.of("pattern", "min", "max");

    private CatalogLoader() {}

    public static Catalog load(Path path) {
        JsonNode root;
        try {
            root = MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new CatalogException("cannot read catalog " + path + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new CatalogException("catalog " + path + " must be a JSON object");
        }

        Map<String, String> statuses = new LinkedHashMap<>();
        requireObject(root, "statuses").fields().forEachRemaining(e -> statuses.put(e.getKey(), e.getValue().asText()));
        if (!statuses.keySet().equals(STATUS_NAMES)) {
            throw new CatalogException("statuses keys " + statuses.keySet() + " must equal " + STATUS_NAMES);
        }

        String version = root.path("catalog_version").isTextual() ? root.get("catalog_version").asText() : "";
        if (version.isBlank()) {
            throw new CatalogException("catalog 'catalog_version' must be a non-blank string");
        }

        List<String> levels = new ArrayList<>();
        for (JsonNode n : requireArray(root, "confidence_levels")) {
            if (!n.isTextual() || n.asText().isBlank()) {
                throw new CatalogException("confidence level " + n + " must be a non-blank string");
            }
            if (levels.contains(n.asText())) {
                throw new CatalogException("duplicate confidence level '" + n.asText() + "'");
            }
            levels.add(n.asText());
        }
        if (levels.isEmpty()) {
            throw new CatalogException("confidence_levels must not be empty");
        }

        List<SectionDef> sections = new ArrayList<>();
        Set<String> sectionIds = new LinkedHashSet<>();
        for (JsonNode s : requireArray(root, "sections")) {
            SectionDef def = new SectionDef(text(s, "id", "section"), text(s, "label", "section"));
            if (!sectionIds.add(def.id())) {
                throw new CatalogException("duplicate section id '" + def.id() + "'");
            }
            sections.add(def);
        }

        List<FieldDef> fields = new ArrayList<>();
        Set<String> fieldIds = new LinkedHashSet<>();
        for (JsonNode f : requireArray(root, "fields")) {
            FieldDef def = parseField(f, sectionIds);
            if (!fieldIds.add(def.id())) {
                throw new CatalogException("duplicate field id '" + def.id() + "'");
            }
            fields.add(def);
        }

        return new Catalog(version, List.copyOf(sections), List.copyOf(fields),
                List.copyOf(levels), Map.copyOf(statuses), root);
    }

    private static FieldDef parseField(JsonNode f, Set<String> sectionIds) {
        String id = text(f, "id", "field");
        String where = "field '" + id + "'";
        FieldType type;
        try {
            type = FieldType.fromJson(text(f, "type", where));
        } catch (CatalogException e) {
            throw new CatalogException(where + ": " + e.getMessage());
        }
        String section = text(f, "section", where);
        if (!sectionIds.contains(section)) {
            throw new CatalogException(where + ": section '" + section + "' is not in sections " + sectionIds);
        }
        List<OptionDef> options = new ArrayList<>();
        for (JsonNode o : f.path("options")) {
            options.add(new OptionDef(text(o, "code", where + " option"), text(o, "label", where + " option")));
        }
        if ((type == FieldType.CODED || type == FieldType.CODED_MULTI) && options.isEmpty()) {
            throw new CatalogException(where + ": " + type.json() + " field has no options");
        }
        return new FieldDef(id, text(f, "label", where), section, type, List.copyOf(options),
                parseValidation(f.get("validation"), where), f.path("guidance").asText(""));
    }

    private static ValidationRules parseValidation(JsonNode v, String where) {
        if (v == null || v.isNull()) {
            return ValidationRules.NONE;
        }
        if (!v.isObject()) {
            throw new CatalogException(where + ": validation must be an object");
        }
        v.fieldNames().forEachRemaining(k -> {
            if (!VALIDATION_KEYS.contains(k)) {
                throw new CatalogException(where + ": unknown validation key '" + k + "' (allowed " + VALIDATION_KEYS + ")");
            }
        });
        Optional<Pattern> pattern = Optional.empty();
        if (v.has("pattern")) {
            if (!v.get("pattern").isTextual()) {
                throw new CatalogException(where + ": validation pattern must be a string");
            }
            try {
                pattern = Optional.of(Pattern.compile(v.get("pattern").asText()));
            } catch (PatternSyntaxException e) {
                throw new CatalogException(where + ": invalid validation pattern: " + e.getMessage());
            }
        }
        return new ValidationRules(pattern, number(v, "min", where), number(v, "max", where));
    }

    private static Optional<BigDecimal> number(JsonNode v, String key, String where) {
        if (!v.has(key)) {
            return Optional.empty();
        }
        if (!v.get(key).isNumber()) {
            throw new CatalogException(where + ": validation " + key + " must be a number");
        }
        return Optional.of(v.get(key).decimalValue());
    }

    private static String text(JsonNode n, String key, String where) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new CatalogException(where + ": missing or non-text '" + key + "'");
        }
        return v.asText();
    }

    private static JsonNode requireObject(JsonNode root, String key) {
        JsonNode n = root.get(key);
        if (n == null || !n.isObject()) {
            throw new CatalogException("catalog '" + key + "' must be an object");
        }
        return n;
    }

    private static JsonNode requireArray(JsonNode root, String key) {
        JsonNode n = root.get(key);
        if (n == null || !n.isArray()) {
            throw new CatalogException("catalog '" + key + "' must be an array");
        }
        return n;
    }
}
