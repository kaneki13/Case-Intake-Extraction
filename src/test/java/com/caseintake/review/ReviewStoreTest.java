package com.caseintake.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewStoreTest {
    private static final Instant AT = Instant.parse("2026-11-08T10:15:30Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void missingFileLoadsEmptyAndCreatesNothing() {
        Path dir = tmp.resolve("reviews");
        ReviewStore store = new ReviewStore(dir);
        DocumentReviews reviews = store.load("A1");
        assertThat(reviews.documentId()).isEqualTo("A1");
        assertThat(reviews.cases()).isEmpty();
        assertThat(dir).doesNotExist();
    }

    @Test
    void saveIsPrettyDeterministicAndAtomic() throws IOException {
        Path dir = tmp.resolve("reviews");
        ReviewStore store = new ReviewStore(dir);
        Map<String, FieldReview> case10 = new LinkedHashMap<>();
        case10.put("zeta", new FieldReview(ReviewState.ACCEPTED, null, AT));
        case10.put("alpha", new FieldReview(ReviewState.EDITED, TextNode.valueOf("x"), AT));
        Map<Integer, Map<String, FieldReview>> cases = new LinkedHashMap<>();
        cases.put(10, case10);
        cases.put(2, Map.of("beta", new FieldReview(ReviewState.REJECTED, null, AT)));
        store.save("A1", new DocumentReviews("A1", cases));

        String json = Files.readString(dir.resolve("A1.json"));
        assertThat(json).endsWith("}\n").contains("\n  \"document_id\"");
        assertThat(json.indexOf("\"2\"")).isLessThan(json.indexOf("\"10\""));
        assertThat(json.indexOf("\"zeta\"")).isLessThan(json.indexOf("\"alpha\""));
        JsonNode root = MAPPER.readTree(json);
        assertThat(root.path("document_id").asText()).isEqualTo("A1");
        assertThat(root.at("/cases/10/zeta/state").asText()).isEqualTo("accepted");
        assertThat(root.at("/cases/10/zeta").has("value")).isFalse();
        assertThat(root.at("/cases/10/zeta/at").asText()).isEqualTo("2026-11-08T10:15:30Z");
        assertThat(root.at("/cases/10/alpha/state").asText()).isEqualTo("edited");
        assertThat(root.at("/cases/10/alpha/value").asText()).isEqualTo("x");
        assertThat(root.at("/cases/2/beta/state").asText()).isEqualTo("rejected");

        DocumentReviews loaded = store.load("A1");
        assertThat(loaded.cases().keySet()).containsExactly(2, 10);
        assertThat(loaded.cases().get(10).keySet()).containsExactly("zeta", "alpha");
        assertThat(loaded.get(10, "alpha")).isEqualTo(new FieldReview(ReviewState.EDITED, TextNode.valueOf("x"), AT));

        store.save("A1", DocumentReviews.empty("A1"));
        assertThat(store.load("A1").cases()).isEmpty();
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("A1.json");
        }
    }

    @Test
    void corruptFilesFailLoudly() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("reviews"));
        ReviewStore store = new ReviewStore(dir);
        for (String bad : List.of(
                "{not json",
                "{\"document_id\":\"A1\",\"cases\":{\"0\":{\"f\":{\"state\":\"maybe\",\"at\":\"2026-11-08T10:15:30Z\"}}}}",
                "{\"document_id\":\"A1\",\"cases\":{\"0\":{\"f\":{\"state\":\"edited\",\"at\":\"2026-11-08T10:15:30Z\"}}}}",
                "{\"document_id\":\"A1\",\"cases\":{\"x\":{}}}",
                "{\"document_id\":\"B2\",\"cases\":{}}")) {
            Files.writeString(dir.resolve("A1.json"), bad);
            assertThatThrownBy(() -> store.load("A1")).as(bad).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void invalidIdsAreRejected() {
        ReviewStore store = new ReviewStore(tmp.resolve("reviews"));
        assertThatThrownBy(() -> store.load("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("../x", DocumentReviews.empty("../x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gitignoreCoversReviews() throws IOException {
        assertThat(Files.readAllLines(Path.of(".gitignore"))).contains("reviews/");
        assertThat(Files.readString(Path.of("src", "main", "resources", "application.yml")))
                .containsPattern("(?m)^  reviews: reviews\\b");
    }
}
