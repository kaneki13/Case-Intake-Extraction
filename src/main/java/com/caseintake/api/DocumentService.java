package com.caseintake.api;

import com.caseintake.catalog.Catalog;
import com.caseintake.config.AppProperties;
import com.caseintake.output.DocumentId;
import com.caseintake.output.FieldStatus;
import com.caseintake.pipeline.DocumentSource;
import com.caseintake.pipeline.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Serves the committed out/ files and the source text. Reads files on every request; nothing is cached. */
@Service
public class DocumentService {
    private static final String DUPLICATES = "duplicates";

    private final AppProperties props;
    private final Catalog catalog;
    private final ObjectMapper mapper;

    public DocumentService(AppProperties props, Catalog catalog, ObjectMapper mapper) {
        this.props = props;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    public List<DocumentSummary> list() {
        List<DocumentSummary> result = new ArrayList<>();
        for (String id : documentIds()) {
            JsonNode extraction = readExtraction(id);
            result.add(summarise(id, extraction, TitleExtractor.title(readText(id))));
        }
        return result;
    }

    public DocumentDetail get(String id) {
        if (!DocumentId.isValid(id)) {
            throw new DocumentApiException(HttpStatus.BAD_REQUEST,
                    "Document id must match " + DocumentId.PATTERN.pattern());
        }
        if (id.equals(DUPLICATES) || !Files.isRegularFile(outFile(id))) {
            throw new DocumentApiException(HttpStatus.NOT_FOUND, "No document with id " + id);
        }
        JsonNode extraction = readExtraction(id);
        String text = readText(id);
        return new DocumentDetail(id, TitleExtractor.title(text), text, extraction);
    }

    private Path outFile(String id) {
        return props.out().resolve(id + ".json");
    }

    private List<String> documentIds() {
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

    private JsonNode readExtraction(String id) {
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(outFile(id), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw unreadable(id, "cannot be read as JSON", e);
        }
        if (root == null || !root.isObject() || !root.path("cases").isArray()) {
            throw unreadable(id, "is not an extraction output (no cases array)", null);
        }
        return root;
    }

    private String readText(String id) {
        try {
            return DocumentSource.list(props.documents(), m -> { }).stream()
                    .filter(d -> d.id().equals(id))
                    .findFirst()
                    .flatMap(SourceDocument::txt)
                    .map(this::readString)
                    .orElse(null);
        } catch (IllegalStateException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Source documents unavailable while reading " + id, e);
        }
    }

    private String readString(Path txt) {
        try {
            return Files.readString(txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Source text for " + txt.getFileName() + " cannot be read", e);
        }
    }

    private DocumentApiException unreadable(String id, String why, Throwable cause) {
        return new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Output for document " + id + " " + why, cause);
    }

    private DocumentSummary summarise(String id, JsonNode extraction, String title) {
        List<String> flags = new ArrayList<>();
        extraction.path("flags").forEach(f -> flags.add(f.asText()));
        Map<String, Integer> byConfidence = new LinkedHashMap<>();
        catalog.confidenceLevels().forEach(level -> byConfidence.put(level, 0));
        int conflict = 0;
        int unknown = 0;
        for (JsonNode c : extraction.path("cases")) {
            for (JsonNode field : c.path("fields")) {
                FieldStatus status;
                try {
                    status = FieldStatus.fromJson(field.path("status").asText());
                } catch (IllegalArgumentException e) {
                    throw unreadable(id, "has a field with an unrecognised status", null);
                }
                switch (status) {
                    case CONFLICT -> conflict++;
                    case UNKNOWN -> unknown++;
                    case EXTRACTED -> {
                        String level = field.path("confidence").asText();
                        if (!byConfidence.containsKey(level)) {
                            throw unreadable(id, "has an extracted field with unknown confidence '" + level + "'", null);
                        }
                        byConfidence.merge(level, 1, Integer::sum);
                    }
                }
            }
        }
        return new DocumentSummary(id, title, extraction.path("cases").size(), flags,
                new DocumentSummary.ReviewCounts(conflict, unknown, byConfidence));
    }
}
