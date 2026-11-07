package com.caseintake.api;

import com.caseintake.catalog.Catalog;
import com.caseintake.config.AppProperties;
import com.caseintake.output.FieldStatus;
import com.caseintake.pipeline.DocumentSource;
import com.caseintake.pipeline.SourceDocument;
import com.caseintake.pipeline.TranscriptStore;
import com.caseintake.review.FieldReview;
import com.caseintake.review.Progress;
import com.caseintake.review.ReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Serves the committed out/ files and the source text: the document's {@code .txt}, else its transcript in the
 * derived dir, else none, merged with the stored reviews and progress. Reads files on every request; nothing is
 * cached.
 */
@Service
public class DocumentService {
    private final AppProperties props;
    private final Catalog catalog;
    private final ExtractionReader reader;
    private final ReviewService reviews;

    public DocumentService(AppProperties props, Catalog catalog, ExtractionReader reader, ReviewService reviews) {
        this.props = props;
        this.catalog = catalog;
        this.reader = reader;
        this.reviews = reviews;
    }

    public List<DocumentSummary> list() {
        List<DocumentSummary> result = new ArrayList<>();
        for (String id : reader.ids()) {
            JsonNode extraction = reader.read(id);
            Progress progress = reviews.progress(extraction, reviews.current(id, extraction));
            result.add(summarise(id, extraction, TitleExtractor.title(readText(id)), progress));
        }
        return result;
    }

    public DocumentDetail get(String id) {
        JsonNode extraction = reader.read(id);
        String text = readText(id);
        Map<Integer, Map<String, FieldReview>> current = reviews.current(id, extraction);
        return new DocumentDetail(id, TitleExtractor.title(text), text, extraction, current,
                reviews.progress(extraction, current));
    }

    private String readText(String id) {
        Optional<Path> txt;
        try {
            txt = DocumentSource.list(props.documents(), m -> { }).stream()
                    .filter(d -> d.id().equals(id))
                    .findFirst()
                    .flatMap(SourceDocument::txt);
        } catch (IllegalStateException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Source documents unavailable while reading " + id, e);
        }
        if (txt.isPresent()) {
            return readString(txt.get());
        }
        try {
            return new TranscriptStore(props.derived()).read(id).orElse(null);
        } catch (UncheckedIOException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transcript for " + id + " cannot be read", e);
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

    private DocumentSummary summarise(String id, JsonNode extraction, String title, Progress progress) {
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
                    throw ExtractionReader.unreadable(id, "has a field with an unrecognised status", null);
                }
                switch (status) {
                    case CONFLICT -> conflict++;
                    case UNKNOWN -> unknown++;
                    case EXTRACTED -> {
                        String level = field.path("confidence").asText();
                        if (!byConfidence.containsKey(level)) {
                            throw ExtractionReader.unreadable(id, "has an extracted field with unknown confidence '" + level + "'", null);
                        }
                        byConfidence.merge(level, 1, Integer::sum);
                    }
                }
            }
        }
        return new DocumentSummary(id, title, extraction.path("cases").size(), flags,
                new DocumentSummary.ReviewCounts(conflict, unknown, byConfidence), progress);
    }
}
