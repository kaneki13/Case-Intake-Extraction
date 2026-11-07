package com.caseintake.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Serialises {@link ExtractionOutput} as out/&lt;documentId&gt;.json. Deterministic: same input, same bytes. */
public final class OutputWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private OutputWriter() {}

    public static String toJson(ExtractionOutput output) {
        List<String> flags = output.flags() == null ? null : output.flags().stream().sorted().toList();
        ExtractionOutput sorted = new ExtractionOutput(
                output.documentId(), output.catalogVersion(), output.model(), flags, output.cases());
        try {
            return MAPPER.writeValueAsString(sorted) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path write(Path dir, ExtractionOutput output) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(output.documentId() + ".json");
            Files.writeString(file, toJson(output), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ExtractionOutput read(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), ExtractionOutput.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Binds an already parsed out/ file; throws IllegalArgumentException when it does not fit the output shape. */
    public static ExtractionOutput fromTree(JsonNode tree) {
        try {
            return MAPPER.treeToValue(tree, ExtractionOutput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not an extraction output", e);
        }
    }
}
