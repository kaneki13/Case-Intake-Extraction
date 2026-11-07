package com.caseintake.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the provider-neutral draft-07 JSON Schema to Gemini's {@code responseSchema} (an OpenAPI subset).
 * This is the only class that knows Gemini's schema dialect.
 *
 * <p>Kept: {@code type} (upper-cased), {@code enum}, {@code properties}, {@code required}, {@code items},
 * {@code description}; added: {@code nullable} and {@code propertyOrdering} (source order).
 * Dropped: {@code pattern}, {@code format}, {@code minimum}, {@code maximum}, {@code uniqueItems},
 * {@code $schema}, {@code additionalProperties} and any unknown keyword. Dropping is safe because the
 * Java side validates every model output against the catalog (pattern, range, options) afterwards.
 *
 * <p>Pure: the input is never mutated.
 */
public final class GeminiSchemaTranslator {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private GeminiSchemaTranslator() {}

    public static JsonNode translate(JsonNode schema) {
        ObjectNode out = F.objectNode();
        if (schema == null || !schema.isObject()) {
            return out;
        }
        boolean nullable = false;
        String type = null;
        JsonNode t = schema.get("type");
        if (t != null && t.isTextual()) {
            type = t.asText();
        } else if (t != null && t.isArray()) {
            for (JsonNode n : t) {
                if ("null".equals(n.asText())) {
                    nullable = true;
                } else if (type == null) {
                    type = n.asText();
                }
            }
        }
        JsonNode en = schema.get("enum");
        if (en != null && en.isArray()) {
            ArrayNode values = F.arrayNode();
            for (JsonNode n : en) {
                if (n.isNull()) {
                    nullable = true;
                } else {
                    values.add(n);
                }
            }
            if (type == null) {
                type = "string";
            }
            out.put("type", type.toUpperCase(Locale.ROOT));
            out.set("enum", values);
        } else if (type != null) {
            out.put("type", type.toUpperCase(Locale.ROOT));
        }
        if (nullable) {
            out.put("nullable", true);
        }
        if (schema.has("description")) {
            out.set("description", schema.get("description").deepCopy());
        }
        if (schema.has("required")) {
            out.set("required", schema.get("required").deepCopy());
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            ObjectNode po = F.objectNode();
            ArrayNode order = F.arrayNode();
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                po.set(e.getKey(), translate(e.getValue()));
                order.add(e.getKey());
            }
            out.set("properties", po);
            out.set("propertyOrdering", order);
        }
        if (schema.has("items")) {
            out.set("items", translate(schema.get("items")));
        }
        return out;
    }
}
