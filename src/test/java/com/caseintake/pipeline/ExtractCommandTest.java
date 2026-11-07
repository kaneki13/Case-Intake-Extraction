package com.caseintake.pipeline;

import static com.caseintake.pipeline.DocumentPipelineTest.EMPTY_FIELDS;
import static com.caseintake.pipeline.DocumentPipelineTest.NO_CASE;
import static com.caseintake.pipeline.DocumentPipelineTest.ONE_CASE;
import static com.caseintake.pipeline.DocumentPipelineTest.PDF_BYTES;
import static com.caseintake.pipeline.DocumentPipelineTest.TRANSCRIBED;
import static com.caseintake.pipeline.DocumentPipelineTest.TRANSCRIPT;
import static com.caseintake.pipeline.DocumentPipelineTest.TEXT;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.config.AppProperties;
import com.caseintake.llm.DailyQuotaExhaustedException;
import com.caseintake.llm.LlmClient;
import com.caseintake.llm.LlmCredential;
import com.caseintake.llm.LlmProperties;
import com.caseintake.llm.LlmRequest;
import com.caseintake.llm.LlmResponse;
import com.caseintake.output.OutputWriter;
import com.caseintake.support.CatalogFixtures;
import com.caseintake.support.DuplicateFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.DefaultApplicationArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractCommandTest {
    static final String KEY = "test-key-123";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);

    @TempDir Path root;
    Path docs;
    Path fixtures;
    Path out;
    Path derived;
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    /** Answers from a script; a "QUOTA" entry throws daily-quota exhaustion. Its credential is {@link #KEY}. */
    static final class FakeClient implements LlmClient {
        final Deque<String> outputs = new ArrayDeque<>();
        final List<LlmRequest> requests = new ArrayList<>();
        String secret = KEY;

        FakeClient(String... outputs) {
            this.outputs.addAll(List.of(outputs));
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            requests.add(request);
            String next = outputs.removeFirst();
            if (next.equals("QUOTA")) {
                throw new DailyQuotaExhaustedException("per-day quota spent");
            }
            return new LlmResponse(request.model(), 200, "{\"x-fake-auth\":\"" + secret + "\"}", "{}",
                    "https://example.test/generate", next);
        }

        @Override
        public String providerId() {
            return "fake";
        }

        @Override
        public LlmCredential credential() {
            return new LlmCredential(secret, "FAKE_API_KEY", Set.of("x-fake-auth"));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        docs = Files.createDirectories(root.resolve("docs"));
        fixtures = root.resolve("fixtures");
        out = root.resolve("out");
        derived = root.resolve("derived");
        for (String id : List.of("E1", "E2", "E3")) {
            Files.writeString(docs.resolve(id + ".txt"), TEXT);
        }
    }

    AppProperties app() {
        return new AppProperties(CatalogFixtures.COMMITTED, List.of(docs), fixtures, out, derived,
                out.resolveSibling("reviews"));
    }

    static LlmProperties llm() {
        return new LlmProperties("fake", new LlmProperties.Models("case-model", "extract-model", "transcribe-model"), null, null, null);
    }

    /** The command with the given client, whose credential secret is set to {@code key}. */
    ExtractCommand command(String key, LlmClient client) {
        if (client instanceof FakeClient fake) {
            fake.secret = key;
        }
        return new ExtractCommand(app(), llm(), DuplicateFixtures.standard(), () -> Optional.ofNullable(client), CLOCK, err);
    }

    String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void extractWithoutKeyExitsTwo() {
        FakeClient client = new FakeClient(NO_CASE);

        int code = command("  ", client).run(Set.of(), Optional.empty(), false);

        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("Configuration error: FAKE_API_KEY is not set; extract needs it");
        assertThat(client.requests).isEmpty();
        assertThat(out).doesNotExist();
        assertThat(fixtures).doesNotExist();
    }

    @Test
    void extractWithoutLlmClientExitsTwo() {
        int code = command(KEY, null).run(Set.of(), Optional.empty(), false);

        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("llm.provider");
        assertThat(out).doesNotExist();
    }

    @Test
    void unknownStageExitsTwo() {
        int code = command(KEY, new FakeClient()).run(Set.of(), Optional.of("bogus"), false);

        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("--stage");
    }

    @Test
    void quotaExhaustionStopsRunAndKeepsFinishedDocuments() {
        FakeClient client = new FakeClient(NO_CASE, "QUOTA", NO_CASE);

        int code = command(KEY, client).run(Set.of(), Optional.empty(), false);

        assertThat(code).isEqualTo(1);
        assertThat(client.requests).hasSize(2);
        assertThat(out.resolve("E1.json")).exists();
        assertThat(out.resolve("E2.json")).doesNotExist();
        assertThat(out.resolve("E3.json")).doesNotExist();
        assertThat(stderr()).contains("--only=E2,E3").contains("quota");
    }

    @Test
    void extractRecordsFixturesAndWritesOutputs() throws Exception {
        FakeClient client = new FakeClient(ONE_CASE, EMPTY_FIELDS);

        int code = command(KEY, client).run(Set.of("E2"), Optional.empty(), false);

        assertThat(code).isZero();
        assertThat(fixtures.resolve("E2/cases.attempt-1.json")).exists();
        assertThat(fixtures.resolve("E2/extract-case-0.attempt-1.json")).exists();
        assertThat(Files.readString(fixtures.resolve("E2/cases.attempt-1.json"))).doesNotContain(KEY)
                .doesNotContain("x-fake-auth");
        assertThat(OutputWriter.read(out.resolve("E2.json")).model()).isEqualTo("fake/extract-model");
        assertThat(out.resolve("E1.json")).doesNotExist();
        assertThat(stderr()).doesNotContain(KEY);
    }

    @Test
    void secondRunReusesFixturesWithoutCallingTheModel() {
        command(KEY, new FakeClient(ONE_CASE, EMPTY_FIELDS)).run(Set.of("E1"), Optional.empty(), false);
        FakeClient again = new FakeClient();

        int code = command(KEY, again).run(Set.of("E1"), Optional.empty(), false);

        assertThat(code).isZero();
        assertThat(again.requests).isEmpty();
    }

    @Test
    void forceReRecordsEveryStage() {
        command(KEY, new FakeClient(ONE_CASE, EMPTY_FIELDS)).run(Set.of("E1"), Optional.empty(), false);
        FakeClient again = new FakeClient(ONE_CASE, EMPTY_FIELDS);

        int code = command(KEY, again).run(Set.of("E1"), Optional.empty(), true);

        assertThat(code).isZero();
        assertThat(again.requests).hasSize(2);
        assertThat(fixtures.resolve("E1/cases.attempt-2.json")).exists();
        assertThat(fixtures.resolve("E1/extract-case-0.attempt-2.json")).exists();
    }

    @Test
    void stageLimitsForceToThatStage() {
        command(KEY, new FakeClient(ONE_CASE, EMPTY_FIELDS)).run(Set.of("E1"), Optional.empty(), false);
        FakeClient casesOnly = new FakeClient(ONE_CASE);

        assertThat(command(KEY, casesOnly).run(Set.of("E1"), Optional.of("cases"), true)).isZero();
        assertThat(casesOnly.requests).extracting(LlmRequest::model).containsExactly("case-model");
        assertThat(fixtures.resolve("E1/cases.attempt-2.json")).exists();
        assertThat(fixtures.resolve("E1/extract-case-0.attempt-2.json")).doesNotExist();

        FakeClient extractOnly = new FakeClient(EMPTY_FIELDS);
        assertThat(command(KEY, extractOnly).run(Set.of("E1"), Optional.of("extract"), false)).isZero();  // --stage implies re-recording that stage
        assertThat(extractOnly.requests).extracting(LlmRequest::model).containsExactly("extract-model");
        assertThat(fixtures.resolve("E1/cases.attempt-3.json")).doesNotExist();
        assertThat(fixtures.resolve("E1/extract-case-0.attempt-2.json")).exists();
    }

    @Test
    void extractCommandAndRedactorAreProviderNeutral() throws Exception {
        for (String source : List.of("pipeline/ExtractCommand.java", "fixtures/Redactor.java")) {
            String code = Files.readString(Path.of("src/main/java/com/caseintake", source));
            assertThat(code).as(source).doesNotContainIgnoringCase("gemini").doesNotContainIgnoringCase("goog");
        }
    }

    @Test
    void unknownOnlyIdFailsTheRun() {
        int code = command(KEY, new FakeClient()).run(Set.of("NOPE"), Optional.empty(), false);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains("NOPE");
    }

    @Test
    void blankModelIdExitsTwo() {
        FakeClient client = new FakeClient(ONE_CASE, EMPTY_FIELDS);
        LlmProperties blank = new LlmProperties("fake", new LlmProperties.Models("case-model", " ", "transcribe-model"), null, null, null);

        int code = new ExtractCommand(app(), blank, DuplicateFixtures.standard(), () -> Optional.of(client), CLOCK, err)
                .run(Set.of(), Optional.empty(), false);

        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("Configuration error: llm.models.extraction is not set");
        assertThat(client.requests).isEmpty();
        assertThat(out).doesNotExist();

        LlmProperties none = new LlmProperties("fake", null, null, null, null);
        assertThat(new ExtractCommand(app(), none, DuplicateFixtures.standard(), () -> Optional.of(client), CLOCK, err)
                .run(Set.of(), Optional.empty(), false)).isEqualTo(2);
        assertThat(stderr()).contains("llm.models.case-finding is not set");

        LlmProperties noTranscription = new LlmProperties("fake",
                new LlmProperties.Models("case-model", "extract-model", ""), null, null, null);
        assertThat(new ExtractCommand(app(), noTranscription, DuplicateFixtures.standard(), () -> Optional.of(client), CLOCK, err)
                .run(Set.of(), Optional.empty(), false)).isEqualTo(2);
        assertThat(stderr()).contains("Configuration error: llm.models.transcription is not set");
        assertThat(client.requests).isEmpty();
    }

    @Test
    void pdfOnlyDocumentIsTranscribedAndItsTranscriptWrittenToDerived() throws Exception {
        Files.write(docs.resolve("P1.pdf"), PDF_BYTES);
        FakeClient client = new FakeClient(TRANSCRIBED, NO_CASE);

        int code = command(KEY, client).run(Set.of("P1"), Optional.empty(), false);

        assertThat(code).isZero();
        assertThat(client.requests).extracting(LlmRequest::model).containsExactly("transcribe-model", "case-model");
        assertThat(fixtures.resolve("P1/transcribe.attempt-1.json")).exists();
        assertThat(Files.readString(derived.resolve("P1.txt"))).isEqualTo(TRANSCRIPT);
        assertThat(OutputWriter.read(out.resolve("P1.json")).flags())
                .containsExactly("no_adverse_event", "transcribed_from_image");
    }

    @Test
    void stageTranscribeAlsoReRecordsThatDocumentsLaterStages() throws Exception {
        Files.write(docs.resolve("P1.pdf"), PDF_BYTES);
        command(KEY, new FakeClient(NO_CASE, TRANSCRIBED, NO_CASE)).run(Set.of("E1", "P1"), Optional.empty(), false);
        String changed = "Scanned again\n" + DocumentPipelineTest.TEXT;
        FakeClient again = new FakeClient(DocumentPipelineTest.transcription(changed, 0), ONE_CASE, EMPTY_FIELDS);

        int code = command(KEY, again).run(Set.of("E1", "P1"), Optional.of("transcribe"), false);

        assertThat(code).isZero();
        assertThat(again.requests).extracting(LlmRequest::model)
                .containsExactly("transcribe-model", "case-model", "extract-model");
        assertThat(fixtures.resolve("E1/cases.attempt-2.json")).doesNotExist();
        assertThat(fixtures.resolve("P1/transcribe.attempt-2.json")).exists();
        assertThat(fixtures.resolve("P1/cases.attempt-2.json")).exists();
        assertThat(Files.readString(derived.resolve("P1.txt"))).isEqualTo(changed);
    }

    @Test
    void duplicateDetectionFailureAfterTheRunExitsOneNotTwo() throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("ZZ.json"), "{not json");
        FakeClient client = new FakeClient(NO_CASE);

        int code = command(KEY, client).run(Set.of("E1"), Optional.empty(), false);

        assertThat(code).isEqualTo(1);
        assertThat(out.resolve("E1.json")).exists();
        assertThat(stderr()).contains("duplicate detection failed: ").doesNotContain("Configuration error");
    }

    @Test
    void blankStageOptionExitsTwo() {
        FakeClient client = new FakeClient(ONE_CASE, EMPTY_FIELDS);
        for (String arg : List.of("--stage=", "--stage", "--stage= ")) {
            int code = command(KEY, client).execute(new DefaultApplicationArguments(arg));
            assertThat(code).as(arg).isEqualTo(2);
        }
        assertThat(stderr()).contains("Configuration error: --stage needs a value");
        assertThat(client.requests).isEmpty();
        assertThat(out).doesNotExist();
    }
}
