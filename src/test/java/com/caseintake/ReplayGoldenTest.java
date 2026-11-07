package com.caseintake;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.config.AppProperties;
import com.caseintake.pipeline.ReplayCommand;
import com.caseintake.pipeline.RunReport;
import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replaying the committed fixtures reproduces the committed out/ exactly (LLD §14). Offline. */
class ReplayGoldenTest {
    static final Path COMMITTED_OUT = Path.of("out");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;

    /**
     * Replays every committed document with the given catalog into outDir; fails on any failed document. The
     * command's progress log (written to System.err at construction) is captured and shown only on failure.
     */
    static RunReport replay(Path catalog, Path outDir) {
        var log = new ByteArrayOutputStream();
        var app = new AppProperties(catalog, List.of(Path.of("documents")), Path.of("fixtures"), COMMITTED_OUT);
        PrintStream original = System.err;
        ReplayCommand command;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            command = new ReplayCommand(app);
        } finally {
            System.setErr(original);
        }
        RunReport report = command.run(catalog, outDir, Set.of());
        assertThat(report.failed()).as("documents that failed to replay; log:%n%s",
                log.toString(StandardCharsets.UTF_8)).isEmpty();
        return report;
    }

    static List<String> jsonFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void replayReproducesCommittedOut() throws Exception {
        Path replayed = tmp.resolve("out");

        replay(CatalogFixtures.COMMITTED, replayed);

        List<String> committed = jsonFiles(COMMITTED_OUT);
        assertThat(committed).hasSize(8);
        assertThat(jsonFiles(replayed)).containsExactlyElementsOf(committed);
        for (String name : committed) {
            assertThat(JSON.readTree(replayed.resolve(name).toFile()))
                    .as(name)
                    .isEqualTo(JSON.readTree(COMMITTED_OUT.resolve(name).toFile()));
        }
    }
}
