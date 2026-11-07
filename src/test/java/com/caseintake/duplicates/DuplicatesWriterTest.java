package com.caseintake.duplicates;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuplicatesWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path tmp;

    private static JsonSchema schema() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(JSON.readTree(Files.readString(Path.of("schema", "duplicates.schema.json"))));
    }

    @Test
    void outputValidatesAgainstDuplicatesSchema() throws Exception {
        var report = new DuplicateReport(
                List.of(new DuplicateGroup(List.of("A", "B"), "high", "Same patient and event: x")), List.of());

        Path file = DuplicatesWriter.write(tmp, report);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode root = JSON.readTree(text);
        assertThat(schema().validate(root)).isEmpty();
        assertThat(text).endsWith("}\n");
        assertThat(root.fieldNames()).toIterable().containsExactly("groups");
        assertThat(root.get("groups").get(0).fieldNames()).toIterable()
                .containsExactly("document_ids", "confidence", "reason");
        assertThat(DuplicatesWriter.toJson(report)).isEqualTo(text);
    }

    @Test
    void emptyGroupsAreStillWritten() throws Exception {
        Path file = DuplicatesWriter.write(tmp.resolve("new-dir"), new DuplicateReport(List.of(), List.of()));

        JsonNode root = JSON.readTree(Files.readString(file));
        assertThat(root.get("groups").isArray()).isTrue();
        assertThat(root.get("groups")).isEmpty();
        assertThat(schema().validate(root)).isEmpty();
    }
}
