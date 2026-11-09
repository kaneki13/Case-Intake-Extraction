package com.caseintake.api.controller;

import com.caseintake.api.exception.ApiExceptionHandler;
import com.caseintake.api.service.ExtractionReader;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.catalog.FieldDef;
import com.caseintake.config.AppProperties;
import com.caseintake.duplicates.DuplicateDetector;
import com.caseintake.duplicates.DuplicateProperties;
import com.caseintake.duplicates.DuplicateReport;
import com.caseintake.duplicates.DuplicatesWriter;
import com.caseintake.output.CaseOutput;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.FieldResult;
import com.caseintake.output.FieldStatus;
import com.caseintake.output.OutputWriter;
import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Synthetic outputs built from the real catalog by section and type; no field id is written here. */
class DuplicatesControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Catalog CATALOG = CatalogLoader.load(CatalogFixtures.COMMITTED);
    private static final List<String> REQUIRED = List.of("patient", "drug", "event");
    private static final Set<String> ALL = Set.copyOf(REQUIRED);

    @TempDir Path tmp;
    private Path out;
    private AppProperties app;

    @BeforeEach
    void setUp() throws IOException {
        out = Files.createDirectories(tmp.resolve("out"));
        app = new AppProperties(CatalogFixtures.COMMITTED, List.of(tmp.resolve("documents")),
                tmp.resolve("fixtures"), out, tmp.resolve("derived"), tmp.resolve("reviews"));
    }

    private static DuplicateProperties props() {
        return new DuplicateProperties("patient", REQUIRED, 5, 8, 1, 4);
    }

    private MockMvc mvc(DuplicateProperties props) {
        return MockMvcBuilders.standaloneSetup(
                        new DuplicatesController(new ExtractionReader(app, MAPPER), CATALOG, props))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static JsonNode value(FieldDef f, int seed) {
        return switch (f.type()) {
            case STRING -> J.textNode("text" + seed);
            case INTEGER -> J.numberNode(40 + seed * 10);
            case NUMBER -> J.numberNode(seed * 100 + 0.5);
            case DATE -> J.textNode("2026-01-0" + seed);
            case CODED -> J.textNode(f.options().get(seed % f.options().size()).code());
            case CODED_MULTI -> J.arrayNode().add(f.options().get(seed % f.options().size()).code());
            case STRING_LIST -> J.arrayNode().add("item" + seed);
        };
    }

    private static LinkedHashMap<String, FieldResult> fields(int seed) {
        LinkedHashMap<String, FieldResult> map = new LinkedHashMap<>();
        for (FieldDef f : CATALOG.fields()) {
            map.put(f.id(), ALL.contains(f.sectionId())
                    ? new FieldResult(FieldStatus.EXTRACTED, value(f, seed), "high", List.of(), null)
                    : new FieldResult(FieldStatus.UNKNOWN, null, "low", List.of(), null));
        }
        return map;
    }

    /** A document with one case per seed. */
    private void doc(String id, int... seeds) {
        List<CaseOutput> cases = new ArrayList<>();
        for (int i = 0; i < seeds.length; i++) {
            cases.add(new CaseOutput(i, "case " + i, fields(seeds[i])));
        }
        OutputWriter.write(out, new ExtractionOutput(id, CATALOG.version(), "m", List.of(), cases));
    }

    @Test
    void listsGroupsWithMatchedCasePair() throws Exception {
        doc("A1", 1);
        doc("B2", 2, 1);
        doc("C3", 3);

        mvc(props()).perform(get("/api/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integerTolerance").value(1))
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].documentIds[0]").value("A1"))
                .andExpect(jsonPath("$.groups[0].documentIds[1]").value("B2"))
                .andExpect(jsonPath("$.groups[0].confidence").value("high"))
                .andExpect(jsonPath("$.groups[0].reason").value(containsString("Same patient and event: ")))
                .andExpect(jsonPath("$.groups[0].pairs.length()").value(1))
                .andExpect(jsonPath("$.groups[0].pairs[0].a.id").value("A1"))
                .andExpect(jsonPath("$.groups[0].pairs[0].a.caseIndex").value(0))
                .andExpect(jsonPath("$.groups[0].pairs[0].b.id").value("B2"))
                .andExpect(jsonPath("$.groups[0].pairs[0].b.caseIndex").value(1))
                .andExpect(jsonPath("$.groups[0].pairs[0].agreements").isNumber());
    }

    @Test
    void groupOfThreeListsEveryAcceptedPair() throws Exception {
        doc("A1", 1);
        doc("B2", 1);
        doc("C3", 1);

        mvc(props()).perform(get("/api/duplicates"))
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].documentIds.length()").value(3))
                .andExpect(jsonPath("$.groups[0].pairs.length()").value(3))
                .andExpect(jsonPath("$.groups[0].pairs[0].a.id").value("A1"))
                .andExpect(jsonPath("$.groups[0].pairs[0].b.id").value("B2"))
                .andExpect(jsonPath("$.groups[0].pairs[2].a.id").value("B2"))
                .andExpect(jsonPath("$.groups[0].pairs[2].b.id").value("C3"));
    }

    @Test
    void emptyWhenNoDuplicates() throws Exception {
        doc("A1", 1);
        doc("B2", 2);

        mvc(props()).perform(get("/api/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0));
    }

    @Test
    void integerToleranceComesFromTheSettings() throws Exception {
        DuplicateProperties wide = new DuplicateProperties("patient", REQUIRED, 5, 8, 7, 4);

        mvc(wide).perform(get("/api/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integerTolerance").value(7))
                .andExpect(jsonPath("$.groups.length()").value(0));
    }

    @Test
    void emptyWhenOutDirIsMissing() throws Exception {
        Files.delete(out);

        mvc(props()).perform(get("/api/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integerTolerance").value(1))
                .andExpect(jsonPath("$.groups.length()").value(0));
    }

    @Test
    void agreesWithCommittedDuplicatesJson() throws Exception {
        doc("A1", 1);
        doc("B2", 2, 1);
        doc("C3", 3);
        doc("D4", 3);
        DuplicateReport report = DuplicatesWriter.detectAndWrite(out, CATALOG, props());
        assertThat(report.groups()).hasSize(2);

        JsonNode file = MAPPER.readTree(Files.readString(out.resolve(DuplicatesWriter.FILE_NAME)));
        String body = mvc(props()).perform(get("/api/duplicates")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        JsonNode api = MAPPER.readTree(body).path("groups");

        JsonNode groups = file.path("groups");
        assertThat(api.size()).isEqualTo(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            assertThat(api.get(i).path("documentIds")).isEqualTo(groups.get(i).path("document_ids"));
            assertThat(api.get(i).path("confidence")).isEqualTo(groups.get(i).path("confidence"));
            assertThat(api.get(i).path("reason")).isEqualTo(groups.get(i).path("reason"));
        }
        assertThat(DuplicateDetector.detect(List.of(), CATALOG, props()).groups()).isEmpty();
    }

    @Test
    void corruptOutputIsAPathFree500() throws Exception {
        doc("A1", 1);
        Files.writeString(out.resolve("B2.json"), "{not json");

        mvc(props()).perform(get("/api/duplicates"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value(containsString("B2")))
                .andExpect(jsonPath("$.detail").value(not(containsString(tmp.toString()))));
    }

    @Test
    void invalidDuplicatesConfigIsAClearPathFree500() throws Exception {
        doc("A1", 1);
        DuplicateProperties bad = new DuplicateProperties("nope", REQUIRED, 5, 8, 1, 4);

        mvc(bad).perform(get("/api/duplicates"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value(containsString("duplicates.identity-section")))
                .andExpect(jsonPath("$.detail").value(not(containsString(tmp.toString()))));
    }
}
