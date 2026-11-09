package com.caseintake;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.catalog.FieldDef;
import com.caseintake.extraction.CaseCandidate;
import com.caseintake.extraction.DateConvention;
import com.caseintake.extraction.DocumentHints;
import com.caseintake.llm.gemini.GeminiSchemaTranslator;
import com.caseintake.prompt.PromptBuilder;
import com.caseintake.prompt.ResponseSchemaFactory;
import com.caseintake.support.CatalogFixtures;
import com.caseintake.text.SourceText;
import com.caseintake.text.TextSpan;
import com.caseintake.validation.FieldValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A field of any catalog type added to the real catalog flows through every catalog-driven component with no code
 * change: the extraction schema, the Gemini schema translation, the extraction prompt and the validator.
 */
class CatalogGeneralityTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String ID = "generality_added_field";
    private static final String LABEL = "Field added by the generality test";
    private static final String DOC = "Patient A had a rash on drug X.";

    @TempDir Path dir;

    /**
     * type, validation JSON (or null), option codes, value schema JSON, Gemini value type, accepted value,
     * rejected value, prompt type text, prompt validation text.
     */
    static Stream<Arguments> types() {
        return Stream.of(
                Arguments.of("string", "{\"pattern\":\"^[A-Z]{2,3}$\"}", List.of(),
                        "{\"type\":[\"string\",\"null\"],\"pattern\":\"^[A-Z]{2,3}$\"}", "STRING",
                        "\"ABC\"", "\"abc\"", "type: string —",
                        "validation: the whole value matches the pattern ^[A-Z]{2,3}$ —"),
                Arguments.of("integer", "{\"min\":1,\"max\":9}", List.of(),
                        "{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":9}", "INTEGER",
                        "5", "10", "type: integer —", "validation: minimum 1, maximum 9 —"),
                Arguments.of("number", "{\"min\":0.5,\"max\":2.5}", List.of(),
                        "{\"type\":[\"number\",\"null\"],\"minimum\":0.5,\"maximum\":2.5}", "NUMBER",
                        "1.25", "2.75", "type: number —", "validation: minimum 0.5, maximum 2.5 —"),
                Arguments.of("date", "{\"pattern\":\"^20\\\\d{2}-\\\\d{2}-\\\\d{2}$\"}", List.of(),
                        "{\"type\":[\"string\",\"null\"],\"format\":\"date\",\"pattern\":\"^20\\\\d{2}-\\\\d{2}-\\\\d{2}$\"}",
                        "STRING", "\"2026-01-31\"", "\"1999-01-31\"", "type: date (string, YYYY-MM-DD) —",
                        "validation: the whole value matches the pattern ^20\\d{2}-\\d{2}-\\d{2}$ —"),
                Arguments.of("coded", null, List.of("alpha", "beta"),
                        "{\"enum\":[\"alpha\",\"beta\",null]}", "STRING",
                        "\"beta\"", "\"gamma\"", "type: coded (one option code) —", "validation: none —"),
                Arguments.of("coded_multi", null, List.of("alpha", "beta"),
                        "{\"type\":[\"array\",\"null\"],\"items\":{\"enum\":[\"alpha\",\"beta\"]}}", "ARRAY",
                        "[\"alpha\",\"beta\"]", "[\"alpha\",\"alpha\"]",
                        "type: coded_multi (array of option codes, each at most once) —", "validation: none —"),
                Arguments.of("string_list", "{\"pattern\":\"^[a-z ]+$\"}", List.of(),
                        "{\"type\":[\"array\",\"null\"],\"items\":{\"type\":\"string\",\"pattern\":\"^[a-z ]+$\"}}",
                        "ARRAY", "[\"one\",\"two words\"]", "[\"One\"]", "type: string_list (array of strings) —",
                        "validation: the whole value matches the pattern ^[a-z ]+$ —"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void addedFieldOfEveryTypeFlowsThroughTheCatalogDrivenComponents(
            String type, String validation, List<String> codes, String valueSchema, String geminiType,
            String accepted, String rejected, String promptType, String promptValidation) throws Exception {
        Path path = CatalogFixtures.mutated(dir, root -> {
            ObjectNode f = ((ArrayNode) root.get("fields")).addObject();
            f.put("id", ID).put("label", LABEL).put("section", root.get("sections").get(0).get("id").asText())
                    .put("type", type).put("guidance", "Added by a test.");
            if (!codes.isEmpty()) {
                ArrayNode options = f.putArray("options");
                codes.forEach(c -> options.addObject().put("code", c).put("label", "Label " + c));
            }
            if (validation != null) {
                f.set("validation", read(validation));
            }
        });
        Catalog catalog = CatalogLoader.load(path);
        FieldDef def = catalog.field(ID).orElseThrow();

        // extraction schema: present, required, with the right value shape
        JsonNode schema = ResponseSchemaFactory.extractionSchema(catalog);
        assertThat(texts(schema.at("/properties/fields/required"))).contains(ID);
        assertThat(schema.at("/properties/fields/properties/" + ID + "/properties/value").toString())
                .isEqualTo(read(valueSchema).toString());

        // Gemini translation keeps the field, upper-cases its type and keeps the option enum
        JsonNode gemini = GeminiSchemaTranslator.translate(schema);
        assertThat(texts(gemini.at("/properties/fields/required"))).contains(ID);
        assertThat(texts(gemini.at("/properties/fields/propertyOrdering"))).contains(ID);
        JsonNode geminiValue = gemini.at("/properties/fields/properties/" + ID + "/properties/value");
        assertThat(geminiValue.path("type").asText()).isEqualTo(geminiType);
        assertThat(geminiValue.path("nullable").asBoolean()).isTrue();
        if (type.equals("coded")) {
            assertThat(texts(geminiValue.get("enum"))).containsExactlyElementsOf(codes);
        } else if (type.equals("coded_multi")) {
            assertThat(texts(geminiValue.at("/items/enum"))).containsExactlyElementsOf(codes);
        }

        // extraction prompt: one line with label, type, options and validation
        SourceText text = SourceText.of("t", DOC);
        CaseCandidate target = new CaseCandidate("patient A", new TextSpan(0, 9), Map.of());
        String user = new PromptBuilder().extractionPrompt(catalog, text, target, List.of(),
                new DocumentHints(DateConvention.UNKNOWN, false, false), "0123456789abcdef", Optional.empty()).user();
        int at = user.indexOf("\n- " + ID + " — " + LABEL + " — ");
        assertThat(at).as("field line").isPositive();
        String line = user.substring(at + 1, user.indexOf('\n', at + 1));
        String options = codes.isEmpty() ? "options: none —"
                : "options: " + String.join("; ", codes.stream().map(c -> c + ": Label " + c).toList()) + " —";
        assertThat(line).contains(promptType, options, promptValidation, "guidance: Added by a test.");

        // validator: accepts a valid value, rejects an invalid one
        assertThat(FieldValidator.validate(def, read(accepted)).ok()).as("accepts " + accepted).isTrue();
        assertThat(FieldValidator.validate(def, read(rejected)).ok()).as("rejects " + rejected).isFalse();
    }

    private static JsonNode read(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
