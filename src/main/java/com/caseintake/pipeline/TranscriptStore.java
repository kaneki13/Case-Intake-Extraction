package com.caseintake.pipeline;

import com.caseintake.output.DocumentId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Transcripts of image-only documents: {@code <derivedDir>/<ID>.txt}, UTF-8, exactly the transcript (no newline is
 * added). A transcript is the source text of its document, so its offsets are the provenance offsets of the output.
 */
public final class TranscriptStore {
    private final Path derivedDir;

    public TranscriptStore(Path derivedDir) {
        this.derivedDir = derivedDir;
    }

    /** Writes (or replaces) the transcript of {@code id}; the file is replaced atomically where the file system can. */
    public void write(String id, String text) {
        Path file = file(id);
        try {
            Files.createDirectories(derivedDir);
            Path tmp = Files.createTempFile(derivedDir, "." + id + "-", ".tmp");
            try {
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write transcript " + file, e);
        }
    }

    /** The transcript of {@code id}, or empty when none has been written. */
    public Optional<String> read(String id) {
        Path file = file(id);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read transcript " + file, e);
        }
    }

    private Path file(String id) {
        if (!DocumentId.isValid(id)) {
            throw new IllegalArgumentException("document id must match " + DocumentId.PATTERN.pattern() + ": " + id);
        }
        return derivedDir.resolve(id + ".txt");
    }
}
