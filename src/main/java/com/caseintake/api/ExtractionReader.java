package com.caseintake.api;

import com.caseintake.config.AppProperties;
import com.caseintake.output.DocumentId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the committed out/ files for the API. Never writes. Failures are {@link DocumentApiException}s:
 * 400 for an id that breaks the {@link DocumentId} rule, 404 for no such output, 500 for an unreadable one.
 */
@Component
public class ExtractionReader {
    private static final String DUPLICATES = "duplicates";

    private final AppProperties props;
    private final ObjectMapper mapper;

    public ExtractionReader(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** The document ids with an output, sorted; {@code duplicates.json} is not a document. */
    public List<String> ids() {
        if (!Files.isDirectory(props.out())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(props.out())) {
            return files.filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .filter(n -> DocumentId.isValid(n) && !n.equals(DUPLICATES))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot list document outputs", e);
        }
    }

    /** The out/&lt;id&gt;.json content, checked to be an object with a {@code cases} array. */
    public JsonNode read(String id) {
        if (!DocumentId.isValid(id)) {
            throw new DocumentApiException(HttpStatus.BAD_REQUEST,
                    "Document id must match " + DocumentId.PATTERN.pattern());
        }
        Path file = props.out().resolve(id + ".json");
        if (id.equals(DUPLICATES) || !Files.isRegularFile(file)) {
            throw new DocumentApiException(HttpStatus.NOT_FOUND, "No document with id " + id);
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw unreadable(id, "cannot be read as JSON", e);
        }
        if (root == null || !root.isObject() || !root.path("cases").isArray()) {
            throw unreadable(id, "is not an extraction output (no cases array)", null);
        }
        return root;
    }

    static DocumentApiException unreadable(String id, String why, Throwable cause) {
        return new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Output for document " + id + " " + why, cause);
    }
}
