package com.caseintake.pipeline;

import static com.caseintake.pipeline.DocumentPipelineTest.EMPTY_FIELDS;
import static com.caseintake.pipeline.DocumentPipelineTest.NO_CASE;
import static com.caseintake.pipeline.DocumentPipelineTest.ONE_CASE;
import static com.caseintake.pipeline.DocumentPipelineTest.TEXT;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.CaseIntakeApplication;
import com.caseintake.config.AppProperties;
import com.caseintake.output.OutputWriter;
import com.caseintake.support.CatalogFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.DefaultApplicationArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayCommandTest {
    @TempDir Path root;
    Path docs;
    Path fixtures;
    Path out;
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @BeforeEach
    void recordWithExtract() throws Exception {
        docs = Files.createDirectories(root.resolve("docs"));
        fixtures = root.resolve("fixtures");
        out = root.resolve("out");
        Files.writeString(docs.resolve("R1.txt"), TEXT);
        Files.writeString(docs.resolve("R2.txt"), TEXT);
        Files.writeString(docs.resolve("R3.pdf"), "%PDF");
        var client = new ExtractCommandTest.FakeClient("not json", ONE_CASE, EMPTY_FIELDS, NO_CASE);
        int code = new ExtractCommand(app(CatalogFixtures.COMMITTED), ExtractCommandTest.llm(),
                () -> Optional.of(client), ExtractCommandTest.CLOCK, err).run(Set.of(), Optional.empty(), false);
        assertThat(code).isZero();
        assertThat(fixtures.resolve("R1/cases.attempt-2.json")).exists();
    }

    /** Runs the application entry point with System.err captured into {@link #errBytes}. */
    int execute(String... args) {
        PrintStream original = System.err;
        System.setErr(err);
        try {
            return CaseIntakeApplication.execute(args);
        } finally {
            System.setErr(original);
        }
    }

    String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    AppProperties app(Path catalog) {
        return new AppProperties(catalog, List.of(docs), fixtures, out);
    }

    @Test
    void replayReproducesExtractOutputExactly() throws Exception {
        Path replayed = root.resolve("replayed");

        RunReport report = new ReplayCommand(app(CatalogFixtures.COMMITTED), err)
                .run(CatalogFixtures.COMMITTED, replayed, Set.of());

        assertThat(report.failed()).isEmpty();
        assertThat(report.written()).containsExactly("R1", "R2", "R3");
        assertThat(report.exitCode()).isZero();
        for (String id : List.of("R1", "R2", "R3")) {
            assertThat(Files.readString(replayed.resolve(id + ".json")))
                    .isEqualTo(Files.readString(out.resolve(id + ".json")));
        }
    }

    @Test
    void changedCatalogReplaysFromTheSameRecordings() {
        Path changed = CatalogFixtures.mutated(root, c -> c.put("catalog_version", "9.9-test"));
        Path replayed = root.resolve("replayed");

        RunReport report = new ReplayCommand(app(changed), err).run(changed, replayed, Set.of("R1", "R2"));

        assertThat(report.written()).containsExactly("R1", "R2");
        assertThat(OutputWriter.read(replayed.resolve("R1.json")).catalogVersion()).isEqualTo("9.9-test");
        assertThat(OutputWriter.read(replayed.resolve("R1.json")).cases()).hasSize(1);
        assertThat(replayed.resolve("R3.json")).doesNotExist();
    }

    @Test
    void missingFixtureFailsOnlyThatDocument() throws Exception {
        Files.writeString(docs.resolve("R0.txt"), TEXT);
        Path replayed = root.resolve("replayed");

        RunReport report = new ReplayCommand(app(CatalogFixtures.COMMITTED), err)
                .run(CatalogFixtures.COMMITTED, replayed, Set.of());

        assertThat(report.failed()).containsOnlyKeys("R0");
        assertThat(report.failed().get("R0")).contains("No fixture recorded for R0/cases");
        assertThat(report.written()).containsExactly("R1", "R2", "R3");
        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(replayed.resolve("R0.json")).doesNotExist();
    }

    @Test
    void replayRunsThroughTheApplicationWithoutProviderOrKey() throws Exception {
        Path replayed = root.resolve("app-out");

        int code = execute("replay", "--llm.provider=",
                "--llm.gemini.api-key=", "--app.documents=" + docs, "--app.fixtures=" + fixtures,
                "--out=" + replayed, "--only=R1,R3");

        assertThat(code).isZero();
        assertThat(Files.readString(replayed.resolve("R1.json"))).isEqualTo(Files.readString(out.resolve("R1.json")));
        assertThat(replayed.resolve("R2.json")).doesNotExist();
        assertThat(stderr()).contains("Run report: 2 written, 0 failed");
    }

    @Test
    void badCatalogIsAConfigurationError() throws Exception {
        Path bad = Files.writeString(root.resolve("bad.json"), "{");

        int code = execute("replay", "--app.catalog=" + bad,
                "--app.documents=" + docs, "--app.fixtures=" + fixtures, "--out=" + root.resolve("x"));

        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("Configuration error: cannot read catalog");
    }

    @Test
    void extractThroughTheApplicationWithoutKeyExitsTwo() {
        int code = execute("extract", "--llm.gemini.api-key=",
                "--app.documents=" + docs, "--app.fixtures=" + root.resolve("f2"), "--app.out=" + root.resolve("o2"));

        assertThat(code).isEqualTo(2);
        assertThat(root.resolve("o2")).doesNotExist();
        assertThat(stderr()).contains("GEMINI_API_KEY");
    }

    @Test
    void reportListsWrittenAndFailed() {
        RunReport r = new RunReport(List.of("A"), Map.of("B", "boom"));
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(new RunReport(List.of("A"), Map.of()).exitCode()).isZero();
    }

    @Test
    void blankOutOptionExitsTwoAndWritesNothing() throws Exception {
        errBytes.reset();   // drop the extract run report printed by the setup
        for (String arg : List.of("--out=", "--out", "--out=  ")) {
            int code = new ReplayCommand(app(CatalogFixtures.COMMITTED), err)
                    .execute(new DefaultApplicationArguments(arg));
            assertThat(code).as(arg).isEqualTo(2);
        }
        assertThat(stderr()).contains("Configuration error: --out needs a value").doesNotContain("Run report");
    }
}
