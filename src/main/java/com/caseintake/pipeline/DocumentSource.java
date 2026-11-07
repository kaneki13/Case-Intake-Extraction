package com.caseintake.pipeline;

import com.caseintake.output.DocumentId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Lists input documents: one directory level per configured dir, files grouped by basename, sorted by id. */
public final class DocumentSource {
    private DocumentSource() {}

    public static List<SourceDocument> list(List<Path> dirs) {
        return list(dirs, System.err::println);
    }

    /** As {@link #list(List)}; files whose basename is not a valid id are skipped with a warning. */
    public static List<SourceDocument> list(List<Path> dirs, Consumer<String> warn) {
        Map<String, List<Path>> byId = new TreeMap<>();
        Map<String, Path> dirOf = new TreeMap<>();
        for (Path dir : dirs) {
            for (Path file : files(dir)) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                int dot = name.lastIndexOf('.');
                String id = dot < 0 ? name : name.substring(0, dot);
                if (!DocumentId.isValid(id)) {
                    warn.accept("Skipping " + file + ": document id must match " + DocumentId.PATTERN.pattern());
                    continue;
                }
                Path previous = dirOf.putIfAbsent(id, dir);
                if (previous != null && !previous.equals(dir)) {
                    throw new IllegalStateException("Document id " + id + " appears in both " + previous + " and " + dir);
                }
                byId.computeIfAbsent(id, k -> new ArrayList<>()).add(file);
            }
        }
        List<SourceDocument> docs = new ArrayList<>();
        byId.forEach((id, files) -> {
            Optional<Path> txt = files.stream().filter(DocumentSource::isTxt).findFirst();
            List<Path> others = files.stream().filter(f -> !isTxt(f)).sorted().toList();
            docs.add(new SourceDocument(id, txt, others));
        });
        return docs;
    }

    private static boolean isTxt(Path f) {
        return f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Document directory not found: " + dir);
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
