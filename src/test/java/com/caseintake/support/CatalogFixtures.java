package com.caseintake.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Helpers that write mutated copies of the real catalog to a temp dir. */
public final class CatalogFixtures {
    public static final Path COMMITTED = Path.of("catalog", "field_catalog.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogFixtures() {}

    public static ObjectNode readCommitted() {
        try {
            return (ObjectNode) MAPPER.readTree(COMMITTED.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a copy of the committed catalog, after applying the mutation, into dir. */
    public static Path mutated(Path dir, Consumer<ObjectNode> mutation) {
        try {
            ObjectNode root = readCommitted();
            mutation.accept(root);
            Path out = dir.resolve("catalog.json");
            Files.writeString(out, MAPPER.writeValueAsString(root));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode field(ObjectNode root, int index) {
        return (ObjectNode) ((ArrayNode) root.get("fields")).get(index);
    }
}
