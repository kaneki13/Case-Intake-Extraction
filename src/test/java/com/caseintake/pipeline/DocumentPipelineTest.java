package com.caseintake.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.fixtures.Exchange;
import com.caseintake.fixtures.ExchangeKey;
import com.caseintake.fixtures.ExchangeSource;
import com.caseintake.llm.LlmRequest;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.FieldStatus;
import com.caseintake.prompt.PromptBuilder;
import com.caseintake.support.CatalogFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentPipelineTest {
    static final Catalog CATALOG = CatalogLoader.load(CatalogFixtures.COMMITTED);
    static final String TEXT = "Patient JS, a 45-year-old man, was reported by Dr Lee Moran. "
            + "He took Drugzol 20 mg and developed a severe rash.\n";
    static final String ONE_CASE = "{\"patients\":[{\"label\":\"JS\",\"anchor_quote\":\"Patient JS\","
            + "\"patient_quote\":\"a 45-year-old man\",\"reporter_quote\":\"Dr Lee Moran\","
            + "\"suspect_drug_quote\":\"Drugzol 20 mg\",\"adverse_event_quote\":\"developed a severe rash\"}],"
            + "\"embedded_instructions\":[],\"date_convention\":{\"value\":\"unknown\",\"evidence_quote\":null}}";
    static final String NO_CASE = "{\"patients\":[],\"no_case_reason\":\"No adverse event described\","
            + "\"embedded_instructions\":[],\"date_convention\":{\"value\":\"unknown\",\"evidence_quote\":null}}";
    static final String EMPTY_FIELDS = "{\"fields\":{}}";

    @TempDir Path dir;
    final List<String> log = new ArrayList<>();
    final PromptBuilder prompts = new PromptBuilder();
    final DocumentPipeline pipeline = new DocumentPipeline(prompts, "case-model", "extract-model", log::add);

    /** Records each call and answers from a script; the request supplier is always evaluated. */
    static final class ScriptedSource implements ExchangeSource {
        record Call(String kind, ExchangeKey key, LlmRequest request) {}

        final Deque<String> outputs;
        final List<Call> calls = new ArrayList<>();

        ScriptedSource(String... outputs) {
            this.outputs = new ArrayDeque<>(List.of(outputs));
        }

        @Override
        public Exchange obtain(ExchangeKey key, Supplier<LlmRequest> request) {
            return answer("obtain", key, request, 1);
        }

        @Override
        public Exchange retry(ExchangeKey key, Supplier<LlmRequest> request) {
            return answer("retry", key, request, 2);
        }

        private Exchange answer(String kind, ExchangeKey key, Supplier<LlmRequest> request, int attempt) {
            LlmRequest r = request.get();
            calls.add(new Call(kind, key, r));
            return new Exchange(key, attempt, "fake", r.model(), outputs.removeFirst());
        }
    }

    SourceDocument textDoc(String id) throws Exception {
        Path txt = Files.writeString(dir.resolve(id + ".txt"), TEXT);
        return new SourceDocument(id, Optional.of(txt), List.of());
    }

    @Test
    void pdfOnlyDocumentIsUnsupportedFormat() throws Exception {
        Path pdf = Files.writeString(dir.resolve("X.pdf"), "%PDF");
        SourceDocument doc = DocumentSource.list(List.of(dir)).get(0);
        assertThat(doc).isEqualTo(new SourceDocument("X", Optional.empty(), List.of(pdf)));
        ScriptedSource source = new ScriptedSource();

        ExtractionOutput out = pipeline.process(doc, CATALOG, source);

        assertThat(out.documentId()).isEqualTo("X");
        assertThat(out.catalogVersion()).isEqualTo(CATALOG.version());
        assertThat(out.flags()).containsExactly("unsupported_format");
        assertThat(out.cases()).isEmpty();
        assertThat(out.model()).isNull();
        assertThat(source.calls).isEmpty();
    }

    @Test
    void oneCaseUsesStageModelsAndKeys() throws Exception {
        ScriptedSource source = new ScriptedSource(ONE_CASE, EMPTY_FIELDS);

        ExtractionOutput out = pipeline.process(textDoc("A1"), CATALOG, source);

        assertThat(source.calls).extracting(c -> c.key().stage()).containsExactly("cases", "extract-case-0");
        assertThat(source.calls).extracting(c -> c.request().model()).containsExactly("case-model", "extract-model");
        assertThat(source.calls).extracting(ScriptedSource.Call::kind).containsOnly("obtain");
        assertThat(out.model()).isEqualTo("fake/extract-model");
        assertThat(out.flags()).isEmpty();
        assertThat(out.cases()).hasSize(1);
        assertThat(out.cases().get(0).caseIndex()).isZero();
        assertThat(out.cases().get(0).label()).isEqualTo("JS");
        assertThat(out.cases().get(0).fields().keySet())
                .containsExactlyElementsOf(CATALOG.fields().stream().map(f -> f.id()).toList());
        assertThat(out.cases().get(0).fields().values()).allMatch(f -> f.status() == FieldStatus.UNKNOWN);
    }

    @Test
    void noCaseTakesCaseFindingModelAndLogsNotes() throws Exception {
        ScriptedSource source = new ScriptedSource(NO_CASE);

        ExtractionOutput out = pipeline.process(textDoc("A2"), CATALOG, source);

        assertThat(out.model()).isEqualTo("fake/case-model");
        assertThat(out.flags()).containsExactly("no_adverse_event");
        assertThat(out.cases()).isEmpty();
        assertThat(log).anyMatch(l -> l.contains("A2") && l.contains("No adverse event described"));
    }

    @Test
    void repairRetryRecordedAsSecondAttempt() throws Exception {
        ScriptedSource source = new ScriptedSource("not json {", ONE_CASE, "{\"fields\":7}", EMPTY_FIELDS);

        ExtractionOutput out = pipeline.process(textDoc("A3"), CATALOG, source);

        assertThat(source.calls).extracting(c -> c.kind() + ":" + c.key().stage())
                .containsExactly("obtain:cases", "retry:cases", "obtain:extract-case-0", "retry:extract-case-0");
        assertThat(source.calls.get(0).request().userContent()).doesNotContain("previous response was rejected");
        assertThat(source.calls.get(1).request().userContent())
                .contains("Your previous response was rejected: response is not valid JSON");
        assertThat(source.calls.get(3).request().userContent())
                .contains("Your previous response was rejected: fields missing");
        assertThat(out.cases()).hasSize(1);
    }

    @Test
    void secondInvalidResponseFailsDocument() throws Exception {
        ScriptedSource source = new ScriptedSource("not json", "{\"patients\":3}");
        SourceDocument doc = textDoc("A4");

        assertThatThrownBy(() -> pipeline.process(doc, CATALOG, source))
                .isInstanceOf(DocumentFailedException.class)
                .hasMessageContaining("cases")
                .hasMessageContaining("patients missing or not an array");
        assertThat(source.calls).hasSize(2);
    }
}
