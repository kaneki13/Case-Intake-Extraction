package com.caseintake.duplicates;

import com.caseintake.catalog.Catalog;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.OutputWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Writes out/duplicates.json: {@code {"groups":[{document_ids, confidence, reason}]}}, always present. */
public final class DuplicatesWriter {
    public static final String FILE_NAME = "duplicates.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DuplicatesWriter() {}

    public static String toJson(DuplicateReport report) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode groups = root.putArray("groups");
        for (DuplicateGroup g : report.groups()) {
            ObjectNode node = groups.addObject();
            ArrayNode ids = node.putArray("document_ids");
            g.documentIds().forEach(ids::add);
            node.put("confidence", g.confidence());
            node.put("reason", g.reason());
        }
        try {
            return MAPPER.writeValueAsString(root) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path write(Path outDir, DuplicateReport report) {
        try {
            Files.createDirectories(outDir);
            Path file = outDir.resolve(FILE_NAME);
            Files.writeString(file, toJson(report), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Detects duplicates among every output present in outDir (so a partial run still reflects all committed
     * outputs) and writes duplicates.json there.
     */
    public static DuplicateReport detectAndWrite(Path outDir, Catalog catalog, DuplicateProperties props) {
        List<ExtractionOutput> outputs;
        try (Stream<Path> files = Files.isDirectory(outDir) ? Files.list(outDir) : Stream.<Path>empty()) {
            outputs = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .filter(f -> !f.getFileName().toString().equals(FILE_NAME))
                    .sorted()
                    .map(OutputWriter::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DuplicateReport report = DuplicateDetector.detect(outputs, catalog, props);
        write(outDir, report);
        return report;
    }
}
