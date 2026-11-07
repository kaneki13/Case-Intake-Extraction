package com.caseintake.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptStoreTest {
    @TempDir Path tmp;

    @Test
    void writesExactlyTheTranscriptAsUtf8AndReadsItBack() throws Exception {
        Path derived = tmp.resolve("nested/derived");
        TranscriptStore store = new TranscriptStore(derived);
        String text = "Ünïcode 𝔁 line\r\nno trailing newline";

        store.write("S03", text);

        assertThat(Files.readAllBytes(derived.resolve("S03.txt"))).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        assertThat(store.read("S03")).contains(text);
        try (var files = Files.list(derived)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("S03.txt");
        }
    }

    @Test
    void rewriteReplacesThePreviousTranscript() {
        TranscriptStore store = new TranscriptStore(tmp);
        store.write("A", "first, longer transcript");
        store.write("A", "second");
        assertThat(store.read("A")).contains("second");
    }

    @Test
    void missingTranscriptIsEmpty() {
        assertThat(new TranscriptStore(tmp.resolve("absent")).read("A")).isEmpty();
    }

    @Test
    void deleteRemovesTheTranscriptAndIgnoresAMissingOne() {
        TranscriptStore store = new TranscriptStore(tmp);
        store.write("A", "text");

        store.delete("A");
        store.delete("A");
        new TranscriptStore(tmp.resolve("absent")).delete("A");

        assertThat(store.read("A")).isEmpty();
        assertThat(tmp.resolve("absent")).doesNotExist();
        assertThatThrownBy(() -> store.delete("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidIdIsRejected() {
        TranscriptStore store = new TranscriptStore(tmp);
        assertThatThrownBy(() -> store.write("../x", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("a/b")).isInstanceOf(IllegalArgumentException.class);
    }
}
