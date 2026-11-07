package com.caseintake.pipeline;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.config.AppProperties;
import com.caseintake.fixtures.FixtureExchangeSource;
import com.caseintake.fixtures.FixtureStore;
import com.caseintake.prompt.PromptBuilder;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Replay mode: rebuilds outputs, and the transcripts of image-only documents, from the recorded fixtures, the
 * documents and the given catalog. It never creates or needs an LlmClient, a model id or an API key.
 */
@Component
public class ReplayCommand {
    static final String RESUME_COMMAND = "java -jar target/case-intake.jar replay";

    private final AppProperties app;
    private final PrintStream err;

    @Autowired
    public ReplayCommand(AppProperties app) {
        this(app, System.err);
    }

    ReplayCommand(AppProperties app, PrintStream err) {
        this.app = app;
        this.err = err;
    }

    public int execute(ApplicationArguments args) {
        RunReport report;
        try {
            Path outDir = CommandOptions.single(args, "out").map(Path::of).orElse(app.out());
            Path derivedDir = CommandOptions.single(args, "derived").map(Path::of).orElse(app.derived());
            report = run(app.catalog(), outDir, derivedDir, CommandOptions.only(args));
        } catch (RuntimeException e) {
            // per-document failures are caught inside the run, so anything here is an option, the catalog or the
            // document dirs
            err.println("Configuration error: " + e.getMessage());
            return 2;
        }
        report.print(err, RESUME_COMMAND);
        return report.exitCode();
    }

    /**
     * Writes outputs to outDir and transcripts to derivedDir. Throws if the catalog or the document directories cannot
     * be read; per-document failures are reported.
     */
    public RunReport run(Path catalogPath, Path outDir, Path derivedDir, Set<String> only) {
        Catalog catalog = CatalogLoader.load(catalogPath);
        List<SourceDocument> docs = DocumentSource.list(app.documents());
        DocumentPipeline pipeline = new DocumentPipeline(new PromptBuilder(), null, null, null,
                new TranscriptStore(derivedDir), err::println);
        return pipeline.run(docs, only, catalog, new FixtureExchangeSource(new FixtureStore(app.fixtures())), outDir);
    }
}
