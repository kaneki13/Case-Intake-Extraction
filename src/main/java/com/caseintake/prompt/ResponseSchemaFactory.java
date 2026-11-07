package com.caseintake.prompt;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.catalog.OptionDef;
import com.caseintake.extraction.DateConvention;
import com.caseintake.extraction.MinimumElement;
import com.caseintake.output.FieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral JSON Schemas (draft-07 style) for the two model stages (LLD 7.2, 7.3).
 *
 * <p>Nullability convention: a typed value that may be null uses a type array, e.g.
 * {@code "type": ["string", "null"]}; an enum that may be null lists {@code null} as its last
 * member, e.g. {@code {"enum": ["high", "medium", "low", null]}}. Provider adapters translate
 * these two forms into their own dialect.
 */
public final class ResponseSchemaFactory {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ResponseSchemaFactory() {}

    /** Stage 1 schema. Fixed: it does not depend on the catalog. */
    public static JsonNode caseFindingSchema() {
        List<String> patientRequired = new ArrayList<>(List.of("label", "anchor_quote"));
        for (MinimumElement e : MinimumElement.values()) {
            patientRequired.add(e.quoteKey());
        }
        ObjectNode patient = object(patientRequired.toArray(String[]::new));
        ObjectNode pp = (ObjectNode) patient.get("properties");
        pp.set("label", type("string"));
        pp.set("anchor_quote", type("string"));
        for (MinimumElement e : MinimumElement.values()) {
            pp.set(e.quoteKey(), nullable("string"));
        }

        ObjectNode convention = object("value");
        ObjectNode cp = (ObjectNode) convention.get("properties");
        ArrayNode values = cp.putObject("value").putArray("enum");
        for (DateConvention c : DateConvention.values()) {
            values.add(c.json());
        }
        cp.set("evidence_quote", nullable("string"));

        ObjectNode root = object("patients", "embedded_instructions", "date_convention");
        ObjectNode props = (ObjectNode) root.get("properties");
        props.set("patients", arrayOf(patient));
        props.set("no_case_reason", nullable("string"));
        props.set("embedded_instructions", arrayOf(type("string")));
        props.set("date_convention", convention);
        return root;
    }

    /** Stage 2 schema, generated from the catalog: one required entry per catalog field, in catalog order. */
    public static JsonNode extractionSchema(Catalog catalog) {
        ObjectNode fields = F.objectNode();
        fields.put("type", "object");
        ArrayNode required = fields.putArray("required");
        ObjectNode props = fields.putObject("properties");
        for (FieldDef def : catalog.fields()) {
            required.add(def.id());
            props.set(def.id(), fieldSchema(def, catalog.confidenceLevels()));
        }
        ObjectNode root = object("fields");
        ((ObjectNode) root.get("properties")).set("fields", fields);
        return root;
    }

    private static ObjectNode fieldSchema(FieldDef def, List<String> confidenceLevels) {
        ObjectNode schema = object("status", "quotes", "reason");
        ObjectNode p = (ObjectNode) schema.get("properties");
        ArrayNode statuses = p.putObject("status").putArray("enum");
        for (FieldStatus s : FieldStatus.values()) {
            statuses.add(s.json());
        }
        p.set("value", valueSchema(def));
        ArrayNode confidence = p.putObject("confidence").putArray("enum");
        confidenceLevels.forEach(confidence::add);
        confidence.addNull();
        p.set("quotes", arrayOf(type("string")));
        p.set("candidates", arrayOf(type("string")));
        p.set("derived", type("boolean"));
        p.set("reason", type("string"));
        return schema;
    }

    private static ObjectNode valueSchema(FieldDef def) {
        return switch (def.type()) {
            case STRING -> withPattern(nullable("string"), def);
            case INTEGER, NUMBER -> {
                ObjectNode s = nullable(def.type() == FieldType.INTEGER ? "integer" : "number");
                def.validation().min().ifPresent(min -> s.put("minimum", min));
                def.validation().max().ifPresent(max -> s.put("maximum", max));
                yield s;
            }
            case DATE -> withPattern(nullable("string").put("format", "date"), def);
            case CODED -> {
                ObjectNode s = F.objectNode();
                codes(s.putArray("enum"), def).addNull();
                yield s;
            }
            case CODED_MULTI -> {
                ObjectNode s = nullable("array");
                codes(s.putObject("items").putArray("enum"), def);
                yield s;
            }
            case STRING_LIST -> {
                ObjectNode s = nullable("array");
                s.set("items", withPattern(type("string"), def));
                yield s;
            }
        };
    }

    /** Adds the catalog pattern, when the field has one, to a string schema. */
    private static ObjectNode withPattern(ObjectNode stringSchema, FieldDef def) {
        def.validation().pattern().ifPresent(pattern -> stringSchema.put("pattern", pattern.pattern()));
        return stringSchema;
    }

    private static ArrayNode codes(ArrayNode target, FieldDef def) {
        for (OptionDef o : def.options()) {
            target.add(o.code());
        }
        return target;
    }

    private static ObjectNode object(String... required) {
        ObjectNode n = F.objectNode();
        n.put("type", "object");
        ArrayNode r = n.putArray("required");
        for (String name : required) {
            r.add(name);
        }
        n.putObject("properties");
        return n;
    }

    private static ObjectNode type(String type) {
        return F.objectNode().put("type", type);
    }

    private static ObjectNode nullable(String type) {
        ObjectNode n = F.objectNode();
        n.putArray("type").add(type).add("null");
        return n;
    }

    private static ObjectNode arrayOf(JsonNode items) {
        ObjectNode n = type("array");
        n.set("items", items);
        return n;
    }
}
