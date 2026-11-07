package com.caseintake.review;

import com.caseintake.output.DocumentId;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Reads and writes {@code <reviewsDir>/<ID>.json}. A missing file means no reviews; an unreadable or malformed file
 * fails loudly. Saves are atomic (temp file in the same directory, then an atomic rename) and, like any
 * load-modify-save done through {@link #locked}, serialised per document. The directory is created on first save.
 */
public final class ReviewStore {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Path dir;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReviewStore(Path reviewsDir) {
        this.dir = Objects.requireNonNull(reviewsDir, "reviews directory (app.reviews) must be set");
    }

    public DocumentReviews load(String id) {
        Path file = file(id);
        if (!Files.exists(file)) {
            return DocumentReviews.empty(id);
        }
        DocumentReviews reviews;
        try {
            reviews = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), DocumentReviews.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reviews of " + id + " cannot be read", e);
        }
        if (reviews == null || !id.equals(reviews.documentId())) {
            throw new IllegalStateException("reviews file of " + id + " names another document");
        }
        return reviews;
    }

    public void save(String id, DocumentReviews reviews) {
        Path file = file(id);
        if (!id.equals(reviews.documentId())) {
            throw new IllegalArgumentException("reviews of " + reviews.documentId() + " cannot be saved as " + id);
        }
        locked(id, () -> {
            Path tmp = null;
            try {
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, "." + id + "-", ".tmp");
                Files.writeString(tmp, MAPPER.writeValueAsString(reviews) + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
            } catch (IOException e) {
                throw new UncheckedIOException("reviews of " + id + " cannot be written", e);
            } finally {
                deleteQuietly(tmp);
            }
            return null;
        });
    }

    /** Runs the action holding this document's lock; reentrant, so the action may load and save. */
    public <T> T locked(String id, Supplier<T> action) {
        checkId(id);
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private Path file(String id) {
        checkId(id);
        return dir.resolve(id + ".json");
    }

    private static void checkId(String id) {
        if (!DocumentId.isValid(id)) {
            throw new IllegalArgumentException("document id must match " + DocumentId.PATTERN.pattern());
        }
    }

    private static void deleteQuietly(Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // the write already failed and that failure is being reported; a stray temp file is harmless
        }
    }
}
