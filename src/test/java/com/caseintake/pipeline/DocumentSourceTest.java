package com.caseintake.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.output.DocumentId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentSourceTest {
    @TempDir Path dir;

    private Path touch(Path d, String name) throws Exception {
        Files.createDirectories(d);
        return Files.writeString(d.resolve(name), "x");
    }

    @Test
    void documentSourceGroupsByBasenameAndSorts() throws Exception {
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        Path d02txt = touch(a, "D02.txt");
        Path d02pdf = touch(a, "D02.pdf");
        Path d01pdf = touch(b, "D01.pdf");
        Path d10txt = touch(b, "D10.txt");
        touch(a.resolve("nested"), "D99.txt");   // one level only
        touch(a, "bad id!.txt");                 // id outside ^[A-Za-z0-9_-]+$ is skipped

        List<String> warnings = new ArrayList<>();
        List<SourceDocument> docs = DocumentSource.list(List.of(a, b), warnings::add);

        assertThat(docs).extracting(SourceDocument::id).containsExactly("D01", "D02", "D10");
        assertThat(docs.get(0).txt()).isEmpty();
        assertThat(docs.get(0).others()).containsExactly(d01pdf);
        assertThat(docs.get(1).txt()).isEqualTo(Optional.of(d02txt));
        assertThat(docs.get(1).others()).containsExactly(d02pdf);
        assertThat(docs.get(2).txt()).isEqualTo(Optional.of(d10txt));
        assertThat(docs.get(2).others()).isEmpty();
        assertThat(warnings).singleElement().asString().contains("bad id!.txt");
    }

    @Test
    void realDocumentsDirectoryListsEveryTopLevelDocument() {
        List<SourceDocument> docs = DocumentSource.list(List.of(Path.of("documents")));
        assertThat(docs).extracting(SourceDocument::id)
                .containsExactly("D01", "D02", "D03", "D04", "D05", "D06", "D07", "D08");
        assertThat(docs).allSatisfy(d -> assertThat(d.txt()).isPresent());
    }

    @Test
    void idsFollowTheSharedDocumentIdPattern() throws Exception {
        touch(dir, "a".repeat(32) + ".txt");
        touch(dir, "a".repeat(33) + ".txt");

        List<String> warnings = new ArrayList<>();
        List<SourceDocument> docs = DocumentSource.list(List.of(dir), warnings::add);

        assertThat(docs).extracting(SourceDocument::id).containsExactly("a".repeat(32));
        assertThat(warnings).singleElement().asString().contains(DocumentId.PATTERN.pattern());
    }
}
