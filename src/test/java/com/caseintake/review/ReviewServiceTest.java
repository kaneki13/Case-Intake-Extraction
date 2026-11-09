package com.caseintake.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.api.exception.DocumentApiException;
import com.caseintake.api.service.ExtractionReader;
import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.config.AppProperties;
import com.caseintake.support.CatalogFixtures;
import com.caseintake.validation.FieldValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class ReviewServiceTest {
    static final Instant NOW = Instant.parse("2026-11-08T10:15:30Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tmp;
    private Path out;
    private Path reviewsDir;
    private Catalog catalog;
    private ReviewStore store;
    private ReviewService service;
    private List<String> ids;

    @BeforeEach
    void setUp() throws IOException {
        out = Files.createDirectories(tmp.resolve("out"));
        reviewsDir = tmp.resolve("reviews");
        catalog = CatalogLoader.load(CatalogFixtures.COMMITTED);
        ids = catalog.fields().stream().map(FieldDef::id).toList();
        writeDoc(out, catalog, "A1", 2);
        store = new ReviewStore(reviewsDir);
        service = newService(out, store);
    }

    private ReviewService newService(Path outDir, ReviewStore reviewStore) {
        AppProperties props = new AppProperties(CatalogFixtures.COMMITTED, List.of(tmp.resolve("documents")),
                tmp.resolve("fixtures"), outDir, tmp.resolve("derived"), reviewsDir);
        return new ReviewService(reviewStore, catalog, new ExtractionReader(props, MAPPER),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Writes out/&lt;id&gt;.json with the given number of cases; the first catalog field is extracted, the rest unknown. */
    static ObjectNode writeDoc(Path outDir, Catalog catalog, String id, int caseCount) throws IOException {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("document_id", id).put("catalog_version", "1");
        root.putArray("flags");
        ArrayNode cases = root.putArray("cases");
        for (int i = 0; i < caseCount; i++) {
            ObjectNode c = cases.addObject().put("case_index", i);
            ObjectNode fields = c.putObject("fields");
            for (FieldDef f : catalog.fields()) {
                fields.putObject(f.id()).put("status", "unknown");
            }
            fields.putObject(catalog.fields().get(0).id())
                    .put("status", "extracted").put("value", "model-" + i).put("confidence", "high");
        }
        Files.writeString(outDir.resolve(id + ".json"), MAPPER.writeValueAsString(root));
        return root;
    }

    private FieldDef ofType(FieldType type) {
        return catalog.fields().stream().filter(f -> f.type() == type).findFirst().orElseThrow();
    }

    private static ReviewRequest req(String action, JsonNode value) {
        return new ReviewRequest(action, value);
    }

    private static void assertStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DocumentApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void acceptRejectEditResetRoundTrip() throws IOException {
        String f = ids.get(0);
        JsonNode model = MAPPER.readTree(out.resolve("A1.json").toFile()).at("/cases/1/fields/" + f);

        MergedField accepted = service.apply("A1", 1, f, req("accept", null));
        assertThat(accepted.fieldId()).isEqualTo(f);
        assertThat(accepted.model()).isEqualTo(model);
        assertThat(accepted.review()).isEqualTo(new FieldReview(ReviewState.ACCEPTED, null, NOW));
        assertThat(store.load("A1").get(1, f)).isEqualTo(accepted.review());

        MergedField rejected = service.apply("A1", 1, f, req("reject", null));
        assertThat(rejected.review().state()).isEqualTo(ReviewState.REJECTED);
        assertThat(store.load("A1").get(1, f).state()).isEqualTo(ReviewState.REJECTED);

        FieldDef coded = ofType(FieldType.CODED);
        TextNode code = TextNode.valueOf(coded.options().get(0).code());
        MergedField edited = service.apply("A1", 1, coded.id(), req("edit", code));
        assertThat(edited.review()).isEqualTo(new FieldReview(ReviewState.EDITED, code, NOW));
        assertThat(edited.model().path("status").asText()).isEqualTo("unknown");

        JsonNode disk = MAPPER.readTree(reviewsDir.resolve("A1.json").toFile());
        assertThat(disk.path("document_id").asText()).isEqualTo("A1");
        assertThat(disk.at("/cases/1/" + f + "/state").asText()).isEqualTo("rejected");
        assertThat(disk.at("/cases/1/" + f + "/at").asText()).isEqualTo("2026-11-08T10:15:30Z");
        assertThat(disk.at("/cases/1/" + coded.id() + "/value")).isEqualTo(code);
        List<String> order = new ArrayList<>();
        disk.at("/cases/1").fieldNames().forEachRemaining(order::add);
        assertThat(order).containsExactly(f, coded.id());

        MergedField reset = service.apply("A1", 1, f, req("reset", null));
        assertThat(reset.review()).isNull();
        assertThat(reset.model()).isEqualTo(model);
        assertThat(store.load("A1").get(1, f)).isNull();
        service.apply("A1", 1, coded.id(), req("reset", null));
        assertThat(store.load("A1").cases()).isEmpty();
        assertThat(reviewsDir.resolve("A1.json")).exists();
    }

    /** A clock the test moves forward. */
    static final class StepClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private ReviewService serviceWith(Clock clock) {
        AppProperties props = new AppProperties(CatalogFixtures.COMMITTED, List.of(tmp.resolve("documents")),
                tmp.resolve("fixtures"), out, tmp.resolve("derived"), reviewsDir);
        return new ReviewService(store, catalog, new ExtractionReader(props, MAPPER), clock);
    }

    @Test
    void jsonNullValueIsNoValue() {
        String f = ids.get(0);
        JsonNode jsonNull = JsonNodeFactory.instance.nullNode();

        assertThat(service.apply("A1", 0, f, req("accept", jsonNull)).review().state())
                .isEqualTo(ReviewState.ACCEPTED);
        assertThat(service.apply("A1", 0, f, req("reject", jsonNull)).review().state())
                .isEqualTo(ReviewState.REJECTED);
        assertThat(service.apply("A1", 0, f, req("reset", jsonNull)).review()).isNull();
        assertStatus(() -> service.apply("A1", 0, f, req("edit", jsonNull)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void acceptOnAnEditedFieldKeepsTheEditAndWritesNothing() throws Exception {
        StepClock clock = new StepClock();
        ReviewService stepping = serviceWith(clock);
        FieldDef coded = ofType(FieldType.CODED);
        TextNode code = TextNode.valueOf(coded.options().get(0).code());
        FieldReview edit = stepping.apply("A1", 0, coded.id(), req("edit", code)).review();
        byte[] before = Files.readAllBytes(reviewsDir.resolve("A1.json"));
        clock.now = NOW.plusSeconds(60);

        MergedField accepted = stepping.apply("A1", 0, coded.id(), req("accept", null));

        assertThat(accepted.review()).isEqualTo(edit);
        assertThat(accepted.model().path("status").asText()).isEqualTo("unknown");
        assertThat(store.load("A1").get(0, coded.id())).isEqualTo(edit);
        assertThat(Files.readAllBytes(reviewsDir.resolve("A1.json"))).isEqualTo(before);
    }

    @Test
    void rejectOnAnEditedFieldRejectsIt() {
        FieldDef coded = ofType(FieldType.CODED);
        service.apply("A1", 0, coded.id(), req("edit", TextNode.valueOf(coded.options().get(0).code())));

        MergedField rejected = service.apply("A1", 0, coded.id(), req("reject", null));

        assertThat(rejected.review()).isEqualTo(new FieldReview(ReviewState.REJECTED, null, NOW));
        assertThat(store.load("A1").get(0, coded.id())).isEqualTo(rejected.review());
    }

    @Test
    void unchangedStateIsNotSaved() throws Exception {
        String f = ids.get(0);
        // reset on an unreviewed field writes no file at all
        assertThat(service.apply("A1", 0, f, req("reset", null)).review()).isNull();
        assertThat(reviewsDir).doesNotExist();

        StepClock clock = new StepClock();
        ReviewService stepping = serviceWith(clock);
        FieldReview accepted = stepping.apply("A1", 0, f, req("accept", null)).review();
        byte[] before = Files.readAllBytes(reviewsDir.resolve("A1.json"));
        clock.now = NOW.plusSeconds(60);

        // accepting again is an idempotent confirm: the first decision and its time stay
        assertThat(stepping.apply("A1", 0, f, req("accept", null)).review()).isEqualTo(accepted);
        assertThat(stepping.apply("A1", 1, f, req("reset", null)).review()).isNull();
        assertThat(Files.readAllBytes(reviewsDir.resolve("A1.json"))).isEqualTo(before);
        // a real change is still saved, with the new time
        assertThat(stepping.apply("A1", 0, f, req("reject", null)).review())
                .isEqualTo(new FieldReview(ReviewState.REJECTED, null, NOW.plusSeconds(60)));
    }

    @Test
    void editIsValidatedAgainstCatalog400() {
        FieldDef integer = ofType(FieldType.INTEGER);
        IntNode tooBig = IntNode.valueOf(1_000_000);
        String error = FieldValidator.validate(integer, tooBig).error();
        assertThat(error).isNotBlank();
        assertThatThrownBy(() -> service.apply("A1", 0, integer.id(), req("edit", tooBig)))
                .isInstanceOfSatisfying(DocumentApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains(error);
                });
        FieldDef coded = ofType(FieldType.CODED);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req("edit", TextNode.valueOf("no-such-code"))),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req("edit", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(),
                req("edit", JsonNodeFactory.instance.nullNode())), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req("accept", TextNode.valueOf("x"))),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req("reset", TextNode.valueOf("x"))),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req("approve", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req(null, null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.apply("A1", 0, coded.id(), req("ACCEPT", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.applyBulk("A1", 0, new BulkReviewRequest("edit", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.applyBulk("A1", 0, new BulkReviewRequest("reset", null)), HttpStatus.BAD_REQUEST);
        assertThat(reviewsDir).doesNotExist();
    }

    @Test
    void unknownFieldOrCase404() {
        String f = ids.get(0);
        assertStatus(() -> service.apply("A1", 2, f, req("accept", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.apply("A1", -1, f, req("accept", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.apply("A1", 0, "no_such_field", req("accept", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.apply("ZZ9", 0, f, req("accept", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.apply("duplicates", 0, f, req("accept", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.apply("../x", 0, f, req("accept", null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.applyBulk("A1", 2, new BulkReviewRequest("accept", null)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.applyBulk("A1", 0, new BulkReviewRequest("accept", List.of(f, "no_such_field"))),
                HttpStatus.NOT_FOUND);
        assertThat(reviewsDir).doesNotExist();
    }

    @Test
    void bulkAcceptAppliesToUnreviewedOnly() {
        String f0 = ids.get(0);
        service.apply("A1", 0, f0, req("reject", null));

        List<MergedField> accepted = service.applyBulk("A1", 0, new BulkReviewRequest("accept", null));
        assertThat(accepted).extracting(MergedField::fieldId).containsExactlyElementsOf(ids.subList(1, ids.size()));
        assertThat(accepted).allSatisfy(m -> assertThat(m.review())
                .isEqualTo(new FieldReview(ReviewState.ACCEPTED, null, NOW)));
        assertThat(accepted.get(0).model().path("status").asText()).isEqualTo("unknown");
        assertThat(store.load("A1").get(0, f0).state()).isEqualTo(ReviewState.REJECTED);
        assertThat(service.applyBulk("A1", 0, new BulkReviewRequest("accept", null))).isEmpty();

        service.apply("A1", 1, f0, req("accept", null));
        List<MergedField> rejected = service.applyBulk("A1", 1,
                new BulkReviewRequest("reject", List.of(ids.get(2), f0, ids.get(1), ids.get(2))));
        assertThat(rejected).extracting(MergedField::fieldId).containsExactly(ids.get(1), ids.get(2));
        assertThat(rejected).allSatisfy(m -> assertThat(m.review().state()).isEqualTo(ReviewState.REJECTED));
        DocumentReviews stored = store.load("A1");
        assertThat(stored.get(1, f0).state()).isEqualTo(ReviewState.ACCEPTED);
        assertThat(stored.cases().get(1)).hasSize(3);
        assertThat(service.applyBulk("A1", 1, new BulkReviewRequest("reject", List.of()))).isEmpty();
    }

    @Test
    void bulkDoesNotOverwriteEdits() {
        FieldDef coded = ofType(FieldType.CODED);
        TextNode code = TextNode.valueOf(coded.options().get(0).code());
        service.apply("A1", 0, coded.id(), req("edit", code));

        List<MergedField> accepted = service.applyBulk("A1", 0, new BulkReviewRequest("accept", null));
        assertThat(accepted).extracting(MergedField::fieldId).doesNotContain(coded.id()).hasSize(ids.size() - 1);
        assertThat(service.applyBulk("A1", 0, new BulkReviewRequest("reject", List.of(coded.id())))).isEmpty();
        assertThat(store.load("A1").get(0, coded.id())).isEqualTo(new FieldReview(ReviewState.EDITED, code, NOW));
    }

    @Test
    void concurrentPatchesKeepBothReviews() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                CountDownLatch start = new CountDownLatch(1);
                int caseIndex = round % 2;
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    int parity = t;
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int i = parity; i < ids.size(); i += 2) {
                            service.apply("A1", caseIndex, ids.get(i), req(parity == 0 ? "accept" : "reject", null));
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                JsonNode disk = MAPPER.readTree(reviewsDir.resolve("A1.json").toFile());
                for (int i = 0; i < ids.size(); i++) {
                    assertThat(disk.at("/cases/" + caseIndex + "/" + ids.get(i) + "/state").asText())
                            .as("round %d field %s", round, ids.get(i))
                            .isEqualTo(i % 2 == 0 ? "accepted" : "rejected");
                }
                service.applyBulk("A1", caseIndex, new BulkReviewRequest("accept", null));
                for (String id : ids) {
                    service.apply("A1", caseIndex, id, req("reset", null));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        try (Stream<Path> files = Files.list(reviewsDir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("A1.json");
        }
    }

    @Test
    void staleReviewsAreIgnored() throws IOException {
        String f0 = ids.get(0);
        String f1 = ids.get(1);
        Files.createDirectories(reviewsDir);
        String stale = "{\"document_id\":\"A1\",\"cases\":{"
                + "\"0\":{\"retired_field\":{\"state\":\"accepted\",\"at\":\"2026-01-01T00:00:00Z\"},"
                + "\"" + f0 + "\":{\"state\":\"rejected\",\"at\":\"2026-01-01T00:00:00Z\"}},"
                + "\"7\":{\"" + f1 + "\":{\"state\":\"accepted\",\"at\":\"2026-01-01T00:00:00Z\"}}}}";
        Files.writeString(reviewsDir.resolve("A1.json"), stale);
        JsonNode extraction = MAPPER.readTree(out.resolve("A1.json").toFile());

        Map<Integer, Map<String, FieldReview>> current = service.current("A1", extraction);
        assertThat(current.keySet()).containsExactly(0);
        assertThat(current.get(0).keySet()).containsExactly(f0);
        assertThat(service.progress("A1")).isEqualTo(new Progress(1, 2 * ids.size()));
        assertThat(service.progress(extraction, current)).isEqualTo(new Progress(1, 2 * ids.size()));

        List<MergedField> bulk = service.applyBulk("A1", 0, new BulkReviewRequest("accept", null));
        assertThat(bulk).extracting(MergedField::fieldId).containsExactlyElementsOf(ids.subList(1, ids.size()));
        service.apply("A1", 1, f1, req("accept", null));
        assertThat(service.progress("A1")).isEqualTo(new Progress(ids.size() + 1, 2 * ids.size()));

        DocumentReviews stored = store.load("A1");
        assertThat(stored.get(0, "retired_field")).isNotNull();
        assertThat(stored.get(7, f1)).isNotNull();
    }

    @Test
    void outDirectoryIsNeverModified() throws Exception {
        Path realOut = Files.createDirectories(tmp.resolve("real-out"));
        try (Stream<Path> committed = Files.list(Path.of("out"))) {
            for (Path p : committed.toList()) {
                Files.copy(p, realOut.resolve(p.getFileName()));
            }
        }
        String id = "D01";
        ReviewService onCommitted = newService(realOut, new ReviewStore(reviewsDir));
        Map<String, String> before = hashes(realOut);
        int caseCount = MAPPER.readTree(realOut.resolve(id + ".json").toFile()).path("cases").size();
        assertThat(caseCount).isPositive();

        FieldDef coded = ofType(FieldType.CODED);
        onCommitted.apply(id, 0, ids.get(0), req("accept", null));
        onCommitted.apply(id, 0, coded.id(), req("edit", TextNode.valueOf(coded.options().get(0).code())));
        onCommitted.applyBulk(id, 0, new BulkReviewRequest("reject", null));
        onCommitted.apply(id, 0, ids.get(0), req("reset", null));
        onCommitted.progress(id);

        assertThat(hashes(realOut)).isEqualTo(before);
        assertThat(reviewsDir.resolve(id + ".json")).exists();
    }

    @Test
    void progressCountsAllCasesAndCatalogFields() throws IOException {
        assertThat(service.progress("A1")).isEqualTo(new Progress(0, 2 * ids.size()));
        service.apply("A1", 0, ids.get(3), req("accept", null));
        service.applyBulk("A1", 1, new BulkReviewRequest("reject", null));
        assertThat(service.progress("A1")).isEqualTo(new Progress(1 + ids.size(), 2 * ids.size()));
        service.apply("A1", 1, ids.get(0), req("reset", null));
        assertThat(service.progress("A1")).isEqualTo(new Progress(ids.size(), 2 * ids.size()));

        writeDoc(out, catalog, "E0", 0);
        assertThat(service.progress("E0")).isEqualTo(new Progress(0, 0));
    }

    @Test
    void reviewTimeIsTruncatedToMillis() {
        ReviewService precise = new ReviewService(store, catalog,
                new ExtractionReader(new AppProperties(CatalogFixtures.COMMITTED, List.of(), tmp, out, tmp, reviewsDir),
                        MAPPER),
                Clock.fixed(Instant.parse("2026-11-08T10:15:30.123456789Z"), ZoneOffset.UTC));
        assertThat(precise.apply("A1", 0, ids.get(0), req("accept", null)).review().at())
                .isEqualTo(Instant.parse("2026-11-08T10:15:30.123Z"));
    }

    private static Map<String, String> hashes(Path dir) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
                result.put(dir.relativize(p).toString(), HexFormat.of().formatHex(digest));
            }
        }
        return result;
    }
}
