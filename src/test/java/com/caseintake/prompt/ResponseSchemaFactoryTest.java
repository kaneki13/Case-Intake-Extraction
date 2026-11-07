package com.caseintake.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.catalog.OptionDef;
import com.caseintake.extraction.CaseCandidate;
import com.caseintake.extraction.DateConvention;
import com.caseintake.extraction.DocumentHints;
import com.caseintake.extraction.MinimumElement;
import com.caseintake.support.CatalogFixtures;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponseSchemaFactoryTest {
    private static final ObjectMapper M = new ObjectMapper();
    private final Catalog catalog = CatalogLoader.load(CatalogFixtures.COMMITTED);

    @TempDir Path dir;

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? null : n.asText()));
        return out;
    }

    private static JsonSchema compile(JsonNode schema) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema);
    }

    private JsonNode fieldSchema(String id) {
        return ResponseSchemaFactory.extractionSchema(catalog).at("/properties/fields/properties/" + id);
    }

    /** A response in which every field is unknown. */
    private ObjectNode allUnknown(Catalog c) {
        ObjectNode root = M.createObjectNode();
        ObjectNode fields = root.putObject("fields");
        for (FieldDef def : c.fields()) {
            ObjectNode f = fields.putObject(def.id());
            f.put("status", "unknown");
            f.putNull("value");
            f.putNull("confidence");
            f.putArray("quotes");
            f.put("reason", "not stated");
        }
        return root;
    }

    @Test
    void everyCatalogFieldIsRequired() {
        JsonNode schema = ResponseSchemaFactory.extractionSchema(catalog);

        assertThat(texts(schema.get("required"))).containsExactly("fields");
        JsonNode fields = schema.at("/properties/fields");
        assertThat(texts(fields.get("required")))
                .containsExactlyElementsOf(catalog.fields().stream().map(FieldDef::id).toList());
        for (FieldDef def : catalog.fields()) {
            JsonNode f = fields.at("/properties/" + def.id());
            assertThat(texts(f.get("required"))).as(def.id()).containsExactly("status", "quotes", "reason");
            assertThat(texts(f.at("/properties/status/enum"))).containsExactly("extracted", "unknown", "conflict");
            List<String> confidence = new ArrayList<>(catalog.confidenceLevels());
            confidence.add(null);
            assertThat(texts(f.at("/properties/confidence/enum"))).containsExactlyElementsOf(confidence);
            assertThat(f.at("/properties/derived/type").asText()).isEqualTo("boolean");
            assertThat(f.at("/properties/candidates/items/type").asText()).isEqualTo("string");
        }
    }

    @Test
    void codedValueIsEnumOfCatalogCodes() {
        int checked = 0;
        for (FieldDef def : catalog.fields()) {
            List<String> codes = def.options().stream().map(OptionDef::code).toList();
            JsonNode value = fieldSchema(def.id()).at("/properties/value");
            if (def.type() == FieldType.CODED) {
                List<String> expected = new ArrayList<>(codes);
                expected.add(null);
                assertThat(texts(value.get("enum"))).as(def.id()).containsExactlyElementsOf(expected);
                checked++;
            } else if (def.type() == FieldType.CODED_MULTI) {
                assertThat(texts(value.get("type"))).containsExactly("array", "null");
                assertThat(texts(value.at("/items/enum"))).as(def.id()).containsExactlyElementsOf(codes);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(1);
    }

    @Test
    void valueSchemaCarriesCatalogValidation() {
        for (FieldDef def : catalog.fields()) {
            JsonNode value = fieldSchema(def.id()).at("/properties/value");
            switch (def.type()) {
                case STRING -> {
                    assertThat(texts(value.get("type"))).containsExactly("string", "null");
                    def.validation().pattern().ifPresentOrElse(
                            p -> assertThat(value.get("pattern").asText()).isEqualTo(p.pattern()),
                            () -> assertThat(value.has("pattern")).isFalse());
                }
                case INTEGER, NUMBER -> {
                    String t = def.type() == FieldType.INTEGER ? "integer" : "number";
                    assertThat(texts(value.get("type"))).containsExactly(t, "null");
                    def.validation().min().ifPresent(
                            m -> assertThat(value.get("minimum").decimalValue()).isEqualByComparingTo(m));
                    def.validation().max().ifPresent(
                            m -> assertThat(value.get("maximum").decimalValue()).isEqualByComparingTo(m));
                }
                case DATE -> {
                    assertThat(texts(value.get("type"))).containsExactly("string", "null");
                    assertThat(value.get("format").asText()).isEqualTo("date");
                }
                case STRING_LIST -> {
                    assertThat(texts(value.get("type"))).containsExactly("array", "null");
                    assertThat(value.at("/items/type").asText()).isEqualTo("string");
                }
                case CODED, CODED_MULTI -> { }
            }
        }
    }

    @Test
    void extractionSchemaAcceptsWellFormedResponseAndRejectsBadCode() {
        JsonSchema schema = compile(ResponseSchemaFactory.extractionSchema(catalog));
        ObjectNode response = allUnknown(catalog);
        assertThat(schema.validate(response)).isEmpty();

        FieldDef coded = catalog.fields().stream().filter(f -> f.type() == FieldType.CODED).findFirst().orElseThrow();
        ObjectNode f = (ObjectNode) response.at("/fields/" + coded.id());
        f.put("status", "extracted").put("confidence", "high").put("value", coded.options().get(0).code());
        assertThat(schema.validate(response)).isEmpty();

        f.put("value", "not-a-catalog-code");
        assertThat(schema.validate(response)).isNotEmpty();

        ((ObjectNode) response.get("fields")).remove(catalog.fields().get(0).id());
        f.put("value", coded.options().get(0).code());
        assertThat(schema.validate(response)).isNotEmpty();
    }

    @Test
    void addedCatalogFieldAppearsInSchemaAndPrompt() {
        Path p = CatalogFixtures.mutated(dir, root -> {
            String section = root.get("sections").get(0).get("id").asText();
            ObjectNode added = ((ArrayNode) root.get("fields")).addObject();
            added.put("id", "zz_test_field");
            added.put("label", "Test field label");
            added.put("section", section);
            added.put("type", "coded");
            ArrayNode options = added.putArray("options");
            options.addObject().put("code", "zz_yes").put("label", "Yes indeed");
            options.addObject().put("code", "zz_no").put("label", "No indeed");
            added.put("guidance", "Guidance for the test field.");
        });
        Catalog mutated = CatalogLoader.load(p);

        JsonNode schema = ResponseSchemaFactory.extractionSchema(mutated);
        assertThat(texts(schema.at("/properties/fields/required"))).contains("zz_test_field");
        assertThat(texts(schema.at("/properties/fields/properties/zz_test_field/properties/value/enum")))
                .containsExactly("zz_yes", "zz_no", null);
        assertThat(compile(schema).validate(allUnknown(mutated))).isEmpty();

        SourceText text = SourceText.of("t", "A 40-year-old man developed a rash after drug X.");
        CaseCandidate target = new CaseCandidate("patient 1", new TextSpan(0, 16), Map.of());
        Prompt prompt = new PromptBuilder().extractionPrompt(mutated, text, target, List.of(),
                new DocumentHints(DateConvention.UNKNOWN, false), DocumentFence.newMarker(), Optional.empty());
        assertThat(prompt.user()).contains("zz_test_field", "Test field label", "zz_yes: Yes indeed",
                "zz_no: No indeed", "Guidance for the test field.");
    }

    @Test
    void caseFindingSchemaHasStageOneShape() {
        JsonNode schema = ResponseSchemaFactory.caseFindingSchema();
        assertThat(texts(schema.get("required"))).containsExactly("patients", "embedded_instructions", "date_convention");
        assertThat(texts(schema.at("/properties/patients/items/required"))).containsExactly(
                "label", "anchor_quote", "patient_quote", "reporter_quote", "suspect_drug_quote", "adverse_event_quote");
        assertThat(texts(schema.at("/properties/patients/items/properties/patient_quote/type")))
                .containsExactly("string", "null");
        assertThat(texts(schema.at("/properties/date_convention/properties/value/enum")))
                .containsExactly("day_first", "month_first", "unknown");

        JsonSchema compiled = compile(schema);
        ObjectNode ok = M.createObjectNode();
        ok.putArray("patients").addObject().put("label", "patient 1").put("anchor_quote", "a woman")
                .putNull("patient_quote").put("reporter_quote", "r").put("suspect_drug_quote", "d")
                .put("adverse_event_quote", "e");
        ok.putNull("no_case_reason");
        ok.putArray("embedded_instructions");
        ok.putObject("date_convention").put("value", "day_first").putNull("evidence_quote");
        assertThat(compiled.validate(ok)).isEmpty();

        ((ObjectNode) ok.get("date_convention")).put("value", "sideways");
        assertThat(compiled.validate(ok)).isNotEmpty();
    }

    @Test
    void schemasAreFreshCopies() {
        ObjectNode a = (ObjectNode) ResponseSchemaFactory.caseFindingSchema();
        a.removeAll();
        assertThat(ResponseSchemaFactory.caseFindingSchema().has("required")).isTrue();
    }

    @Test
    void datePatternAndStringListItemPatternAreCarried() {
        Path p = CatalogFixtures.mutated(dir, r -> {
            for (JsonNode f : r.get("fields")) {
                String type = f.get("type").asText();
                if (type.equals("date")) {
                    ((ObjectNode) f).putObject("validation").put("pattern", "^20\\d{2}-.+$");
                } else if (type.equals("string_list")) {
                    ((ObjectNode) f).putObject("validation").put("pattern", "^[^;]+$");
                }
            }
        });
        Catalog c = CatalogLoader.load(p);
        JsonNode schema = ResponseSchemaFactory.extractionSchema(c);
        int dates = 0;
        int lists = 0;
        for (FieldDef def : c.fields()) {
            JsonNode value = schema.at("/properties/fields/properties/" + def.id() + "/properties/value");
            if (def.type() == FieldType.DATE) {
                assertThat(value.path("pattern").asText()).as(def.id()).isEqualTo("^20\\d{2}-.+$");
                assertThat(value.path("format").asText()).as(def.id()).isEqualTo("date");
                dates++;
            } else if (def.type() == FieldType.STRING_LIST) {
                assertThat(value.at("/items/pattern").asText()).as(def.id()).isEqualTo("^[^;]+$");
                assertThat(value.at("/items/type").asText()).as(def.id()).isEqualTo("string");
                lists++;
            }
        }
        assertThat(dates).isPositive();
        assertThat(lists).isPositive();
    }

    @Test
    void dateAndStringListWithoutPatternHaveNone() {
        for (FieldDef def : catalog.fields()) {
            if (def.type() == FieldType.DATE || def.type() == FieldType.STRING_LIST) {
                JsonNode value = fieldSchema(def.id()).at("/properties/value");
                assertThat(value.has("pattern")).as(def.id()).isFalse();
                assertThat(value.path("items").has("pattern")).as(def.id()).isFalse();
            }
        }
    }

    @Test
    void caseFindingQuoteKeysComeFromMinimumElement() {
        assertThat(MinimumElement.SUSPECT_DRUG.quoteKey()).isEqualTo("suspect_drug_quote");
        JsonNode patient = ResponseSchemaFactory.caseFindingSchema().at("/properties/patients/items");
        List<String> expected = new ArrayList<>(List.of("label", "anchor_quote"));
        for (MinimumElement e : MinimumElement.values()) {
            expected.add(e.quoteKey());
        }
        assertThat(texts(patient.get("required"))).containsExactlyElementsOf(expected);
        assertThat(texts(patient.at("/properties/" + MinimumElement.PATIENT.quoteKey() + "/type")))
                .containsExactly("string", "null");
    }
}
