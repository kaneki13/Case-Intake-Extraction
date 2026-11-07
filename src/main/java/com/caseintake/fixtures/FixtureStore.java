package com.caseintake.fixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads and writes {@code <root>/<DOC>/<stage>.attempt-<n>.json}. */
public class FixtureStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path root;

    public FixtureStore(Path root) {
        this.root = root;
    }

    /** Throws IllegalStateException, writing nothing, if the serialized record still contains the key. */
    public Path write(ExchangeKey key, int attempt, FixtureRecord record, String redactionKey) {
        if (redactionKey == null || redactionKey.isBlank()) {
            throw new IllegalArgumentException("A non-blank redaction key is required to write fixtures");
        }
        try {
            String text = MAPPER.writeValueAsString(record) + "\n";
            if (text.contains(redactionKey)) {
                throw new IllegalStateException("Refusing to write fixture: API key still present for "
                        + key.documentId() + "/" + key.stage());
            }
            Path file = path(key, attempt);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads exactly the given attempt's file. */
    public FixtureRecord read(ExchangeKey key, int attempt) {
        try {
            return MAPPER.readValue(Files.readString(path(key, attempt), StandardCharsets.UTF_8), FixtureRecord.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<FixtureRecord> latest(ExchangeKey key) {
        int max = maxAttempt(key);
        if (max == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(Files.readString(path(key, max), StandardCharsets.UTF_8),
                    FixtureRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int nextAttempt(ExchangeKey key) {
        return maxAttempt(key) + 1;
    }

    private Path path(ExchangeKey key, int attempt) {
        return root.resolve(key.documentId()).resolve(key.stage() + ".attempt-" + attempt + ".json");
    }

    private int maxAttempt(ExchangeKey key) {
        Path dir = root.resolve(key.documentId());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Pattern p = Pattern.compile(Pattern.quote(key.stage()) + "\\.attempt-(\\d+)\\.json");
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> p.matcher(f.getFileName().toString())).filter(Matcher::matches)
                    .mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
