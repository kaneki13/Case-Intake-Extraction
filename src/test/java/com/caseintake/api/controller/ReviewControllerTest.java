package com.caseintake.api.controller;

import com.caseintake.api.exception.ApiExceptionHandler;
import com.caseintake.api.service.DocumentService;
import com.caseintake.api.service.ExtractionReader;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.config.AppProperties;
import com.caseintake.review.ReviewService;
import com.caseintake.review.ReviewStore;
import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tmp;
    private Path reviews;
    private Catalog catalog;
    private List<String> ids;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        Path out = Files.createDirectories(tmp.resolve("out"));
        reviews = tmp.resolve("reviews");
        catalog = CatalogLoader.load(CatalogFixtures.COMMITTED);
        ids = catalog.fields().stream().map(FieldDef::id).toList();
        writeDoc(out, "A1", 2);
        writeDoc(out, "B2", 0);
        Path docs = Files.createDirectories(tmp.resolve("documents"));
        AppProperties props = new AppProperties(CatalogFixtures.COMMITTED, List.of(docs),
                tmp.resolve("fixtures"), out, tmp.resolve("derived"), reviews);
        ExtractionReader reader = new ExtractionReader(props, MAPPER);
        ReviewService reviewService = new ReviewService(new ReviewStore(reviews), catalog, reader,
                Clock.fixed(Instant.parse("2026-11-08T10:15:30Z"), ZoneOffset.UTC));
        DocumentService documents = new DocumentService(props, catalog, reader, reviewService);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documents, reviewService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void writeDoc(Path out, String id, int caseCount) throws IOException {
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < caseCount; i++) {
            StringBuilder fields = new StringBuilder();
            for (String f : ids) {
                fields.append(fields.isEmpty() ? "" : ",").append('"').append(f).append("\":{\"status\":\"unknown\"}");
            }
            cases.append(i == 0 ? "" : ",").append("{\"case_index\":").append(i).append(",\"fields\":{")
                    .append(fields).append("}}");
        }
        Files.writeString(out.resolve(id + ".json"), "{\"document_id\":\"" + id
                + "\",\"catalog_version\":\"1\",\"flags\":[],\"cases\":[" + cases + "]}");
    }

    private ResultActions patchField(String path, String body) throws Exception {
        return mvc.perform(patch("/api/documents/" + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectProblem(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status));
    }

    private FieldDef ofType(FieldType type) {
        return catalog.fields().stream().filter(f -> f.type() == type).findFirst().orElseThrow();
    }

    @Test
    void acceptRejectEditResetRoundTrip() throws Exception {
        String f = ids.get(0);
        patchField("A1/cases/1/fields/" + f, "{\"action\":\"accept\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.fieldId").value(f))
                .andExpect(jsonPath("$.model.status").value("unknown"))
                .andExpect(jsonPath("$.review.state").value("accepted"))
                .andExpect(jsonPath("$.review.at").value("2026-11-08T10:15:30Z"))
                .andExpect(jsonPath("$.review.value").doesNotExist());
        patchField("A1/cases/1/fields/" + f, "{\"action\":\"reject\"}")
                .andExpect(jsonPath("$.review.state").value("rejected"));
        FieldDef multi = ofType(FieldType.CODED_MULTI);
        String code = multi.options().get(0).code();
        patchField("A1/cases/1/fields/" + multi.id(), "{\"action\":\"edit\",\"value\":[\"" + code + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.state").value("edited"))
                .andExpect(jsonPath("$.review.value[0]").value(code));

        mvc.perform(get("/api/documents/A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews['1']['" + f + "'].state").value("rejected"))
                .andExpect(jsonPath("$.reviews['1']['" + multi.id() + "'].value[0]").value(code))
                .andExpect(jsonPath("$.reviews['0']").doesNotExist())
                .andExpect(jsonPath("$.progress.reviewed").value(2))
                .andExpect(jsonPath("$.progress.total").value(2 * ids.size()))
                .andExpect(jsonPath("$.extraction.document_id").value("A1"));

        patchField("A1/cases/1/fields/" + f, "{\"action\":\"reset\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldId").value(f))
                .andExpect(jsonPath("$.review").value(nullValue()));
        mvc.perform(get("/api/documents"))
                .andExpect(jsonPath("$[0].id").value("A1"))
                .andExpect(jsonPath("$[0].progress.reviewed").value(1))
                .andExpect(jsonPath("$[0].progress.total").value(2 * ids.size()))
                .andExpect(jsonPath("$[1].progress.reviewed").value(0))
                .andExpect(jsonPath("$[1].progress.total").value(0));
    }

    @Test
    void editIsValidatedAgainstCatalog400() throws Exception {
        FieldDef integer = ofType(FieldType.INTEGER);
        String base = "A1/cases/0/fields/" + integer.id();
        expectProblem(patchField(base, "{\"action\":\"edit\",\"value\":\"twelve\"}"), 400);
        patchField(base, "{\"action\":\"edit\",\"value\":\"twelve\"}")
                .andExpect(jsonPath("$.detail").value(containsString("is not an integer")));
        expectProblem(patchField(base, "{\"action\":\"edit\"}"), 400);
        expectProblem(patchField(base, "{\"action\":\"edit\",\"value\":null}"), 400);
        expectProblem(patchField(base, "{\"action\":\"accept\",\"value\":3}"), 400);
        expectProblem(patchField(base, "{\"action\":\"approve\"}"), 400);
        expectProblem(patchField(base, "{}"), 400);
        expectProblem(patchField(base, "not json"), 400);
        expectProblem(patchField("A1/cases/x/fields/" + integer.id(), "{\"action\":\"accept\"}"), 400);
        expectProblem(patchField("A1/cases/0/fields", "{\"action\":\"edit\"}"), 400);
        expectProblem(patchField("A1/cases/0/fields", "{\"action\":\"reset\"}"), 400);
        expectProblem(patchField("..%2Fpom/cases/0/fields/" + integer.id(), "{\"action\":\"accept\"}"), 400);
        mvc.perform(get("/api/documents/A1")).andExpect(jsonPath("$.progress.reviewed").value(0));
        org.assertj.core.api.Assertions.assertThat(reviews).doesNotExist();
    }

    @Test
    void jsonNullValueIsNoValue() throws Exception {
        String base = "A1/cases/0/fields/" + ids.get(0);
        patchField(base, "{\"action\":\"accept\",\"value\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.state").value("accepted"));
        patchField(base, "{\"action\":\"reject\",\"value\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.state").value("rejected"));
        patchField(base, "{\"action\":\"reset\",\"value\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review").value(nullValue()));
        expectProblem(patchField(base, "{\"action\":\"edit\",\"value\":null}"), 400);
    }

    @Test
    void acceptOnAnEditedFieldKeepsTheEdit() throws Exception {
        FieldDef multi = ofType(FieldType.CODED_MULTI);
        String code = multi.options().get(0).code();
        String base = "A1/cases/0/fields/" + multi.id();
        patchField(base, "{\"action\":\"edit\",\"value\":[\"" + code + "\"]}").andExpect(status().isOk());

        patchField(base, "{\"action\":\"accept\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.state").value("edited"))
                .andExpect(jsonPath("$.review.value[0]").value(code));
        patchField(base, "{\"action\":\"reject\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.state").value("rejected"))
                .andExpect(jsonPath("$.review.value").doesNotExist());
    }

    @Test
    void unknownFieldOrCase404() throws Exception {
        String f = ids.get(0);
        expectProblem(patchField("ZZ9/cases/0/fields/" + f, "{\"action\":\"accept\"}"), 404);
        expectProblem(patchField("A1/cases/2/fields/" + f, "{\"action\":\"accept\"}"), 404);
        expectProblem(patchField("B2/cases/0/fields/" + f, "{\"action\":\"accept\"}"), 404);
        expectProblem(patchField("A1/cases/0/fields/no_such_field", "{\"action\":\"accept\"}"), 404);
        expectProblem(patchField("A1/cases/0/fields",
                "{\"action\":\"accept\",\"fieldIds\":[\"" + f + "\",\"no_such_field\"]}"), 404);
        org.assertj.core.api.Assertions.assertThat(reviews).doesNotExist();
    }

    @Test
    void bulkAcceptAppliesToUnreviewedOnly() throws Exception {
        String f0 = ids.get(0);
        patchField("A1/cases/0/fields/" + f0, "{\"action\":\"reject\"}").andExpect(status().isOk());
        patchField("A1/cases/0/fields", "{\"action\":\"accept\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(ids.size() - 1))
                .andExpect(jsonPath("$[0].fieldId").value(ids.get(1)))
                .andExpect(jsonPath("$[0].model.status").value("unknown"))
                .andExpect(jsonPath("$[0].review.state").value("accepted"));
        patchField("A1/cases/1/fields", "{\"action\":\"reject\",\"fieldIds\":[\"" + ids.get(2) + "\"]}")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].review.state").value("rejected"));
        mvc.perform(get("/api/documents/A1"))
                .andExpect(jsonPath("$.reviews['0']['" + f0 + "'].state").value("rejected"))
                .andExpect(jsonPath("$.progress.reviewed").value(ids.size() + 1));
    }
}
