package com.caseintake.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** One input document: the files sharing a basename. Only the {@code .txt} file is read. */
public record SourceDocument(String id, Optional<Path> txt, List<Path> others) {
    public SourceDocument {
        others = List.copyOf(others);
    }
}
