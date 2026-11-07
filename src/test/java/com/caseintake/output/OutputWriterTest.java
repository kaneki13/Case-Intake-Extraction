package com.caseintake.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.support.SchemaAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EXAMPLE = Path.of("schema", "EXAMPLE-output-D01.json");

    @TempDir Path dir;

    @Test
    void exampleRoundTripsAsJsonTree() throws Exception {
        ExtractionOutput parsed = OutputWriter.read(EXAMPLE);
        OutputWriter.write(dir, parsed);
        JsonNode written = MAPPER.readTree(Files.readString(dir.resolve(parsed.documentId() + ".json")));
        assertThat(written).isEqualTo(MAPPER.readTree(Files.readString(EXAMPLE)));
    }

    @Test
    void unknownFieldWithoutQuotesOmitsProvenanceAndIsSchemaValid() throws Exception {
        LinkedHashMap<String, FieldResult> fields = new LinkedHashMap<>();
        fields.put("f", new FieldResult(FieldStatus.UNKNOWN, null, null, List.of(), "Not stated."));
        ExtractionOutput out = new ExtractionOutput("X2", "1.0", null, List.of(),
                List.of(new CaseOutput(0, null, fields)));

        Path file = OutputWriter.write(dir, out);

        assertThat(MAPPER.readTree(file.toFile()).at("/cases/0/fields/f").has("provenance")).isFalse();
        SchemaAssertions.assertValid(file);
    }

    @Test
    void omitsNullsAndWritesFieldsInGivenOrder() throws Exception {
        LinkedHashMap<String, FieldResult> fields = new LinkedHashMap<>();
        fields.put("zeta", new FieldResult(FieldStatus.UNKNOWN, null, null, null, null));
        fields.put("alpha", new FieldResult(FieldStatus.EXTRACTED, JsonNodeFactory.instance.arrayNode(), "high",
                List.of(new Provenance("q", 0, 1)), null));
        ExtractionOutput out = new ExtractionOutput("X1", "1.0", null, List.of("b", "a"),
                List.of(new CaseOutput(0, null, fields)));

        String json = OutputWriter.toJson(out);
        assertThat(json).endsWith("}\n");
        assertThat(json).doesNotContain("null").doesNotContain("\"model\"").doesNotContain("\"label\"");
        assertThat(json.indexOf("\"zeta\"")).isLessThan(json.indexOf("\"alpha\""));
        assertThat(json).contains("\"value\" : [ ]").contains("\"status\" : \"unknown\"");
        JsonNode tree = MAPPER.readTree(json);
        assertThat(tree.get("flags").get(0).asText()).isEqualTo("a");
        assertThat(tree.get("cases").get(0).has("case_index")).isTrue();
    }

    @Test
    void unsupportedFormatOutputIsSchemaValid() throws Exception {
        ExtractionOutput out = new ExtractionOutput("D99", "1.0", null, List.of("unsupported_format"), List.of());
        OutputWriter.write(dir, out);
        SchemaAssertions.assertValid(dir.resolve("D99.json"));
    }
}
