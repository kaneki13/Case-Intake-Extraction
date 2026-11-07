package com.caseintake.review;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * All reviews of one document: case index → field id → review. Cases are kept in ascending order; the field order
 * within a case is whatever the writer chose (the service uses catalog order). Immutable.
 */
public record DocumentReviews(
        @JsonProperty("document_id") String documentId,
        Map<Integer, Map<String, FieldReview>> cases) {

    public DocumentReviews {
        Objects.requireNonNull(documentId, "document_id");
        Objects.requireNonNull(cases, "cases");
        TreeMap<Integer, Map<String, FieldReview>> sorted = new TreeMap<>();
        cases.forEach((index, fields) -> {
            Objects.requireNonNull(index, "case index");
            Objects.requireNonNull(fields, "fields of case " + index);
            fields.forEach((fieldId, review) -> {
                Objects.requireNonNull(fieldId, "field id");
                Objects.requireNonNull(review, "review of " + fieldId);
            });
            sorted.put(index, Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
        });
        cases = Collections.unmodifiableMap(sorted);
    }

    public static DocumentReviews empty(String documentId) {
        return new DocumentReviews(documentId, Map.of());
    }

    /** The review of one field, or null. */
    public FieldReview get(int caseIndex, String fieldId) {
        Map<String, FieldReview> fields = cases.get(caseIndex);
        return fields == null ? null : fields.get(fieldId);
    }

    /**
     * A copy with the changes applied to one case; a null review removes that field's review, and a case left
     * without reviews is dropped. Fields in {@code fieldOrder} come first, in that order; any others (no longer in
     * the catalog) follow in their existing order, untouched.
     */
    public DocumentReviews with(int caseIndex, Map<String, FieldReview> changes, List<String> fieldOrder) {
        Map<String, FieldReview> merged = new LinkedHashMap<>(cases.getOrDefault(caseIndex, Map.of()));
        changes.forEach((fieldId, review) -> {
            if (review == null) {
                merged.remove(fieldId);
            } else {
                merged.put(fieldId, review);
            }
        });
        Map<String, FieldReview> ordered = new LinkedHashMap<>();
        for (String fieldId : fieldOrder) {
            if (merged.containsKey(fieldId)) {
                ordered.put(fieldId, merged.get(fieldId));
            }
        }
        List<String> rest = new ArrayList<>(merged.keySet());
        rest.removeAll(ordered.keySet());
        rest.forEach(fieldId -> ordered.put(fieldId, merged.get(fieldId)));

        Map<Integer, Map<String, FieldReview>> next = new TreeMap<>(cases);
        if (ordered.isEmpty()) {
            next.remove(caseIndex);
        } else {
            next.put(caseIndex, ordered);
        }
        return new DocumentReviews(documentId, next);
    }
}
