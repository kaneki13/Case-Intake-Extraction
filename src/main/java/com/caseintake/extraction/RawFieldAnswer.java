package com.caseintake.extraction;

import com.caseintake.output.FieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * One field's answer as the model gave it (LLD §7.3), before any checking. value may be null or a NullNode.
 * Shape errors name only the field id and key, never model or document text.
 */
public record RawFieldAnswer(FieldStatus status, JsonNode value, String confidence, List<String> quotes,
                             List<String> candidates, boolean derived, String reason) {

    public static RawFieldAnswer parse(String fieldId, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid(fieldId, null);
        }
        return new RawFieldAnswer(status(fieldId, node.get("status")), node.get("value"),
                optionalText(fieldId, node, "confidence"), stringList(fieldId, node, "quotes"),
                stringList(fieldId, node, "candidates"), derived(fieldId, node.get("derived")),
                optionalText(fieldId, node, "reason"));
    }

    private static FieldStatus status(String fieldId, JsonNode n) {
        if (n != null && n.isTextual()) {
            for (FieldStatus s : FieldStatus.values()) {
                if (s.json().equals(n.asText())) {
                    return s;
                }
            }
        }
        throw invalid(fieldId, "status");
    }

    private static String optionalText(String fieldId, JsonNode node, String key) {
        JsonNode n = node.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw invalid(fieldId, key);
        }
        return n.asText();
    }

    private static List<String> stringList(String fieldId, JsonNode node, String key) {
        JsonNode n = node.get(key);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw invalid(fieldId, key);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual()) {
                throw invalid(fieldId, key);
            }
            out.add(item.asText());
        }
        return List.copyOf(out);
    }

    private static boolean derived(String fieldId, JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (!n.isBoolean()) {
            throw invalid(fieldId, "derived");
        }
        return n.asBoolean();
    }

    private static ResponseShapeException invalid(String fieldId, String key) {
        return new ResponseShapeException("fields." + fieldId + (key == null ? "" : "." + key) + " invalid");
    }
}
