package com.caseintake.api.controller;

import com.caseintake.api.exception.ApiExceptionHandler;
import com.caseintake.api.service.DocumentService;
import com.caseintake.api.service.ExtractionReader;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.config.AppProperties;
import com.caseintake.pipeline.TranscriptStore;
import com.caseintake.review.ReviewService;
import com.caseintake.review.ReviewStore;
import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentControllerTest {
    @TempDir Path tmp;
    private Path out;
    private Path docs;
    private Path derived;
    private Catalog catalog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        out = Files.createDirectories(tmp.resolve("out"));
        docs = Files.createDirectories(tmp.resolve("documents"));
        catalog = CatalogLoader.load(CatalogFixtures.COMMITTED);
        derived = tmp.resolve("derived");
        AppProperties props = new AppProperties(CatalogFixtures.COMMITTED, List.of(docs), tmp.resolve("fixtures"), out,
                derived, tmp.resolve("reviews"));
        ExtractionReader reader = new ExtractionReader(props, new ObjectMapper());
        ReviewService reviews = new ReviewService(new ReviewStore(tmp.resolve("reviews")), catalog, reader,
                Clock.systemUTC());
        DocumentService service = new DocumentService(props, catalog, reader, reviews);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(service, reviews), new CatalogController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void writeOut(String id, String json) throws IOException {
        Files.writeString(out.resolve(id + ".json"), json);
    }

    private static String caseJson(String... fields) {
        return "{\"case_index\":0,\"fields\":{" + String.join(",", fields) + "}}";
    }

    private static String extracted(String conf) {
        return "{\"status\":\"extracted\",\"value\":\"v\",\"confidence\":\"" + conf + "\",\"provenance\":[]}";
    }

    private static String doc(String id, String flags, String... cases) {
        return "{\"document_id\":\"" + id + "\",\"catalog_version\":\"1\",\"flags\":" + flags
                + ",\"future_prop\":7,\"cases\":[" + String.join(",", cases) + "]}";
    }

    @Test
    void listsDocumentsSortedWithCaseCountAndFlags() throws Exception {
        writeOut("B2", doc("B2", "[\"unsupported_format\"]"));
        writeOut("A1", doc("A1", "[]", caseJson(), caseJson()));
        writeOut("duplicates", "[]");
        writeOut("bad name", "{}");
        Files.writeString(docs.resolve("A1.txt"), "Subject: Hello\nbody");
        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("A1"))
                .andExpect(jsonPath("$[0].title").value("Hello"))
                .andExpect(jsonPath("$[0].caseCount").value(2))
                .andExpect(jsonPath("$[0].flags.length()").value(0))
                .andExpect(jsonPath("$[1].id").value("B2"))
                .andExpect(jsonPath("$[1].title").value("(no text layer)"))
                .andExpect(jsonPath("$[1].caseCount").value(0))
                .andExpect(jsonPath("$[1].flags[0]").value("unsupported_format"));
    }

    @Test
    void reviewCountsHasEveryCatalogLevel() throws Exception {
        String conflict = "{\"status\":\"conflict\",\"note\":\"n\"}";
        String unknown = "{\"status\":\"unknown\"}";
        writeOut("A1", doc("A1", "[]",
                caseJson("\"f1\":" + extracted("high"), "\"f2\":" + conflict, "\"f3\":" + unknown),
                caseJson("\"f1\":" + extracted("high"), "\"f2\":" + extracted("low"), "\"f3\":" + unknown)));
        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewCounts.conflict").value(1))
                .andExpect(jsonPath("$[0].reviewCounts.unknown").value(2))
                .andExpect(jsonPath("$[0].reviewCounts.byConfidence.high").value(2))
                .andExpect(jsonPath("$[0].reviewCounts.byConfidence.medium").value(0))
                .andExpect(jsonPath("$[0].reviewCounts.byConfidence.low").value(1))
                .andExpect(jsonPath("$[0].reviewCounts.byConfidence.length()")
                        .value(catalog.confidenceLevels().size()));
    }

    @Test
    void detailReturnsTextAndExtraction() throws Exception {
        writeOut("A1", doc("A1", "[]", caseJson()));
        String text = "Subject: Hi\r\nünï  text";
        Files.writeString(docs.resolve("A1.txt"), text);
        mvc.perform(get("/api/documents/A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("A1"))
                .andExpect(jsonPath("$.title").value("Hi"))
                .andExpect(jsonPath("$.text").value(text))
                .andExpect(jsonPath("$.extraction.document_id").value("A1"))
                .andExpect(jsonPath("$.extraction.catalog_version").value("1"))
                .andExpect(jsonPath("$.extraction.future_prop").value(7))
                .andExpect(jsonPath("$.extraction.cases[0].case_index").value(0));
    }

    @Test
    void detailTextIsNullWithoutTxt() throws Exception {
        writeOut("A1", doc("A1", "[]"));
        mvc.perform(get("/api/documents/A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.title").value("(no text layer)"));
    }

    @Test
    void detailServesDerivedTextWhenNoTxt() throws Exception {
        writeOut("S3", doc("S3", "[\"transcribed_from_image\"]"));
        Files.writeString(docs.resolve("S3.pdf"), "%PDF");
        String transcript = "Subject: Scanned\n[illegible] ünï\n";
        new TranscriptStore(derived).write("S3", transcript);

        mvc.perform(get("/api/documents/S3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(transcript))
                .andExpect(jsonPath("$.title").value("Scanned"));
        mvc.perform(get("/api/documents"))
                .andExpect(jsonPath("$[0].title").value("Scanned"));
    }

    @Test
    void txtWinsOverDerivedText() throws Exception {
        writeOut("A1", doc("A1", "[]"));
        Files.writeString(docs.resolve("A1.txt"), "from txt");
        new TranscriptStore(derived).write("A1", "from transcript");

        mvc.perform(get("/api/documents/A1"))
                .andExpect(jsonPath("$.text").value("from txt"));
    }

    @Test
    void unknownIdIs404ProblemJson() throws Exception {
        mvc.perform(get("/api/documents/ZZ9"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void pathTraversalIdIs400() throws Exception {
        mvc.perform(get("/api/documents/..%2Fpom"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/documents/" + "a".repeat(33))).andExpect(status().isBadRequest());
    }

    @Test
    void unrecognisedFieldStatusIs500() throws Exception {
        for (String status : new String[] {"bogus", "EXTRACTED", ""}) {
            writeOut("A1", doc("A1", "[]", caseJson("\"f1\":{\"status\":\"" + status + "\"}")));
            mvc.perform(get("/api/documents"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("has a field with an unrecognised status")));
        }
    }

    @Test
    void corruptOutFileIs500WithoutStackTrace() throws Exception {
        writeOut("A1", "{not json");
        mvc.perform(get("/api/documents/A1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("A1")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("at com."))))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(tmp.toString()))));
        mvc.perform(get("/api/documents")).andExpect(status().isInternalServerError());
    }

    @Test
    void listingFailureIs500WithoutPathOrStackTrace() throws Exception {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> none = java.util.Set.of();
        Files.setPosixFilePermissions(out, none);
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(out), "running with permission bypass");
        try {
            mvc.perform(get("/api/documents"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(tmp.toString()))))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("at com."))));
        } finally {
            Files.setPosixFilePermissions(out, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void duplicatesIdIs404() throws Exception {
        writeOut("duplicates", "[]");
        mvc.perform(get("/api/documents/duplicates")).andExpect(status().isNotFound());
    }

    @Test
    void catalogIsServedUnchanged() throws Exception {
        String expected = new ObjectMapper().writeValueAsString(catalog.raw());
        mvc.perform(get("/api/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().json(expected, true));
    }
}
