package com.caseintake.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates output JSON against schema/extraction.schema.json (draft-07). */
public final class SchemaAssertions {
    private static final Path SCHEMA = Path.of("schema", "extraction.schema.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaAssertions() {}

    public static void assertValid(Path json) {
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                    .getSchema(MAPPER.readTree(Files.readString(SCHEMA)));
            JsonNode doc = MAPPER.readTree(Files.readString(json));
            Set<ValidationMessage> errors = schema.validate(doc);
            if (!errors.isEmpty()) {
                throw new AssertionError("Schema validation failed for " + json + ": "
                        + errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.toList()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
