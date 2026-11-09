package com.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A changed catalog replays from the same recordings with no code change (LLD §14, §9.2): an added field and a
 * tightened pattern both turn into {@code unknown} with the exact notes of the field assembler.
 */
class CatalogChangeReplayTest {
    static final String ADDED_ID = "replay_test_added_field";
    static final String NOT_COVERED = "Not covered by the recorded model response.";
    static final String UNSATISFIABLE = "^(?!)$";   // a pattern that matches nothing

    static final Path JAR = Path.of("target", "case-intake.jar");

    @TempDir Path tmp;

    static Consumer<ObjectNode> addStringField() {
        return root -> {
            String section = root.get("sections").get(0).get("id").asText();
            ((ArrayNode) root.get("fields")).addObject()
                    .put("id", ADDED_ID)
                    .put("label", "Field added after recording")
                    .put("section", section)
                    .put("type", "string")
                    .put("guidance", "Added by a test after the fixtures were recorded.");
        };
    }

    @Test
    void addedFieldIsUnknownWithNote() throws Exception {
        Path catalog = CatalogFixtures.mutated(tmp, addStringField());
        Path replayed = tmp.resolve("out");

        ReplayGoldenTest.replay(catalog, replayed, tmp.resolve("derived"));

        int cases = 0;
        for (String name : ReplayGoldenTest.jsonFiles(ReplayGoldenTest.COMMITTED_OUT)) {
            JsonNode committed = ReplayGoldenTest.JSON.readTree(ReplayGoldenTest.COMMITTED_OUT.resolve(name).toFile());
            JsonNode now = ReplayGoldenTest.JSON.readTree(replayed.resolve(name).toFile());
            assertThat(now.get("cases").size()).as(name).isEqualTo(committed.get("cases").size());
            for (int i = 0; i < now.get("cases").size(); i++) {
                ObjectNode fields = (ObjectNode) now.get("cases").get(i).get("fields").deepCopy();
                JsonNode added = fields.remove(ADDED_ID);
                assertThat(added).as(name + " case " + i).isNotNull();
                assertThat(added.get("status").asText()).isEqualTo("unknown");
                assertThat(added.get("note").asText()).isEqualTo(NOT_COVERED);
                assertThat(fields).as(name + " case " + i + " other fields")
                        .isEqualTo(committed.get("cases").get(i).get("fields"));
                cases++;
            }
        }
        assertThat(cases).as("cases replayed").isPositive();
    }

    @Test
    void tightenedPatternTurnsValueUnknownWithNote() throws Exception {
        String[] patterned = new String[1];
        Path catalog = CatalogFixtures.mutated(tmp, root -> {
            for (JsonNode f : root.get("fields")) {
                JsonNode validation = f.get("validation");
                if (validation != null && validation.has("pattern")) {
                    patterned[0] = f.get("id").asText();
                    ((ObjectNode) validation).put("pattern", UNSATISFIABLE);
                    return;
                }
            }
        });
        assertThat(patterned[0]).as("a catalog field with a pattern").isNotNull();
        Path replayed = tmp.resolve("out");

        ReplayGoldenTest.replay(catalog, replayed, tmp.resolve("derived"));

        int turned = 0;
        for (String name : ReplayGoldenTest.jsonFiles(ReplayGoldenTest.COMMITTED_OUT)) {
            JsonNode committed = ReplayGoldenTest.JSON.readTree(ReplayGoldenTest.COMMITTED_OUT.resolve(name).toFile());
            JsonNode now = ReplayGoldenTest.JSON.readTree(replayed.resolve(name).toFile());
            for (int i = 0; i < committed.get("cases").size(); i++) {
                JsonNode before = committed.get("cases").get(i).get("fields").get(patterned[0]);
                JsonNode after = now.get("cases").get(i).get("fields").get(patterned[0]);
                assertThat(after.get("status").asText()).as(name + " case " + i).isEqualTo("unknown");
                assertThat(after.has("value") && !after.get("value").isNull()).isFalse();
                if (before.get("status").asText().equals("extracted")) {
                    // LLD §9.2: "Model value <v> rejected: <validator error>." and the error names the pattern
                    assertThat(after.get("note").asText()).as(name + " case " + i)
                            .matches("Model value .+ rejected: .+\\.")
                            .contains(UNSATISFIABLE);
                    turned++;
                }
            }
        }
        assertThat(turned).as("extracted values of " + patterned[0] + " in the committed out/").isPositive();
    }

    /**
     * Why replay.sh would rebuild the jar (its own rule: the jar is missing or a file under src/ or pom.xml is newer),
     * or empty when the jar is current. The test is skipped then, so surefire never starts a nested Maven build.
     */
    static Optional<String> staleJar() throws IOException {
        if (!Files.isRegularFile(JAR)) {
            return Optional.of(JAR + " is missing");
        }
        FileTime built = Files.getLastModifiedTime(JAR);
        if (Files.getLastModifiedTime(Path.of("pom.xml")).compareTo(built) > 0) {
            return Optional.of(JAR + " is older than pom.xml");
        }
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> {
                        try {
                            return Files.getLastModifiedTime(f).compareTo(built) > 0;
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .findFirst()
                    .map(f -> JAR + " is older than " + f);
        }
    }

    @Test
    void replayShAcceptsCatalogArgument() throws Exception {
        Optional<String> stale = staleJar();
        assumeTrue(stale.isEmpty(), () -> "Skipping replay.sh test: " + stale.orElse("")
                + "; run ./mvnw package (or ./replay.sh) first so the script does not start a nested Maven build");
        Path catalog = CatalogFixtures.mutated(tmp, addStringField());
        Path replayed = tmp.resolve("script-out");
        Path log = tmp.resolve("replay.log");

        ProcessBuilder pb = new ProcessBuilder("./replay.sh", catalog.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String, String> env = pb.environment();
        env.put("REPLAY_OUT", replayed.toString());
        env.put("REPLAY_DERIVED", tmp.resolve("script-derived").toString());
        String javaHome = System.getenv("JAVA_HOME");
        env.put("JAVA_HOME", javaHome != null ? javaHome : System.getProperty("java.home"));
        Process process = pb.start();
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }

        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(finished).as("replay.sh finished; output:%n%s", output).isTrue();
        assertThat(process.exitValue()).as("exit code; output:%n%s", output).isZero();
        assertThat(ReplayGoldenTest.jsonFiles(replayed))
                .containsExactlyElementsOf(ReplayGoldenTest.jsonFiles(ReplayGoldenTest.COMMITTED_OUT));
        for (String name : ReplayGoldenTest.jsonFiles(replayed)) {
            for (JsonNode c : ReplayGoldenTest.JSON.readTree(replayed.resolve(name).toFile()).get("cases")) {
                assertThat(c.get("fields").get(ADDED_ID).get("note").asText()).isEqualTo(NOT_COVERED);
            }
        }
    }
}
