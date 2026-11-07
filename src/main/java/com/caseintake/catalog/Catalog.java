package com.caseintake.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The single source of field metadata. confidenceLevels is ordered highest first. */
public record Catalog(String version, List<SectionDef> sections, List<FieldDef> fields,
                      List<String> confidenceLevels, Map<String, String> statuses, JsonNode raw) {

    public Optional<FieldDef> field(String id) {
        return fields.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    public List<FieldDef> fieldsInSection(String sectionId) {
        return fields.stream().filter(f -> f.sectionId().equals(sectionId)).toList();
    }

    /** Moves the level down by steps, clamping at the lowest level. */
    public String lowerConfidence(String level, int steps) {
        int idx = rank(level);
        return confidenceLevels.get(Math.min(idx + Math.max(steps, 0), confidenceLevels.size() - 1));
    }

    /** The lower-ranked of the two levels. */
    public String minConfidence(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private int rank(String level) {
        int idx = confidenceLevels.indexOf(level);
        if (idx < 0) {
            throw new IllegalArgumentException("unknown confidence level '" + level + "'");
        }
        return idx;
    }
}
