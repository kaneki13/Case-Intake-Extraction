package com.caseintake.pipeline;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.config.AppProperties;
import com.caseintake.fixtures.Exchange;
import com.caseintake.fixtures.ExchangeKey;
import com.caseintake.fixtures.ExchangeSource;
import com.caseintake.fixtures.FixtureStore;
import com.caseintake.fixtures.RecordingExchangeSource;
import com.caseintake.llm.LlmClient;
import com.caseintake.llm.LlmCredential;
import com.caseintake.llm.LlmProperties;
import com.caseintake.llm.LlmRequest;
import com.caseintake.prompt.PromptBuilder;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Extract mode: calls the model through {@link RecordingExchangeSource}, recording every exchange under the
 * fixtures dir, and writes the outputs and transcripts. Existing fixtures are reused unless {@code --force};
 * {@code --stage} re-records only that stage, except that a re-recorded transcript also re-records the later stages
 * of its document, since they read the transcript.
 */
@Component
public class ExtractCommand {
    static final String RESUME_COMMAND = "java -jar target/case-intake.jar extract";
    private static final String STAGE_VALUES =
            Arrays.stream(Stage.values()).map(Stage::cliValue).collect(Collectors.joining(" or "));

    private final AppProperties app;
    private final LlmProperties llm;
    private final Supplier<Optional<LlmClient>> client;
    private final Clock clock;
    private final PrintStream err;

    @Autowired
    public ExtractCommand(AppProperties app, LlmProperties llm, ObjectProvider<LlmClient> clients, Clock clock) {
        this(app, llm, () -> Optional.ofNullable(clients.getIfAvailable()), clock, System.err);
    }

    ExtractCommand(AppProperties app, LlmProperties llm, Supplier<Optional<LlmClient>> client, Clock clock,
                   PrintStream err) {
        this.app = app;
        this.llm = llm;
        this.client = client;
        this.clock = clock;
        this.err = err;
    }

    public int execute(ApplicationArguments args) {
        Optional<String> stage;
        try {
            stage = CommandOptions.single(args, "stage");
        } catch (IllegalArgumentException e) {
            return configError(e.getMessage());
        }
        return run(CommandOptions.only(args), stage, args.containsOption("force"));
    }

    /** Returns the exit code of LLD 1.1. {@code stage} implies re-recording that stage, with or without force. */
    public int run(Set<String> only, Optional<String> stage, boolean force) {
        Predicate<ExchangeKey> forced;
        if (stage.isEmpty()) {
            forced = key -> force;
        } else {
            Optional<Stage> selected = Stage.fromCli(stage.get());
            if (selected.isEmpty()) {
                return configError("--stage must be " + STAGE_VALUES + ", got: " + stage.get());
            }
            forced = key -> selected.get().owns(key.stage());
        }
        Optional<LlmClient> llmClient = client.get();
        if (llmClient.isEmpty()) {
            return configError("no LlmClient is available for llm.provider=" + llm.provider() + ".");
        }
        LlmCredential credential = llmClient.get().credential();
        if (!credential.isPresent()) {
            return configError(credential.settingName() + " is not set; extract needs it to call the model "
                    + "(replay runs without it).");
        }
        LlmProperties.Models models = llm.models();
        String caseFindingModel = models == null ? null : models.caseFinding();
        String extractionModel = models == null ? null : models.extraction();
        String transcriptionModel = models == null ? null : models.transcription();
        if (caseFindingModel == null || caseFindingModel.isBlank()) {
            return configError("llm.models.case-finding is not set; extract needs a model id for case finding.");
        }
        if (extractionModel == null || extractionModel.isBlank()) {
            return configError("llm.models.extraction is not set; extract needs a model id for extraction.");
        }
        if (transcriptionModel == null || transcriptionModel.isBlank()) {
            return configError("llm.models.transcription is not set; extract needs a model id for transcription.");
        }
        Catalog catalog;
        List<SourceDocument> docs;
        try {
            catalog = CatalogLoader.load(app.catalog());
            docs = DocumentSource.list(app.documents());
        } catch (RuntimeException e) {
            return configError(e.getMessage());
        }

        FixtureStore store = new FixtureStore(app.fixtures());
        ExchangeSource reuse = new RecordingExchangeSource(llmClient.get(), store, catalog.version(), false, clock);
        ExchangeSource rerecord = new RecordingExchangeSource(llmClient.get(), store, catalog.version(), true, clock);
        ExchangeSource source = new StageRouting(forced, rerecord, reuse);
        DocumentPipeline pipeline = new DocumentPipeline(new PromptBuilder(), caseFindingModel, extractionModel,
                transcriptionModel, new TranscriptStore(app.derived()), err::println);
        RunReport report = pipeline.run(docs, only, catalog, source, app.out());
        report.print(err, RESUME_COMMAND);
        return report.exitCode();
    }

    private int configError(String message) {
        err.println("Configuration error: " + message);
        return 2;
    }

    /**
     * Sends each exchange key to the re-recording or the reusing source. Once a document's transcript is re-recorded,
     * every later exchange of that document is re-recorded too: a new transcript invalidates its case finding and
     * extraction.
     */
    private static final class StageRouting implements ExchangeSource {
        private final Predicate<ExchangeKey> forced;
        private final ExchangeSource rerecord;
        private final ExchangeSource reuse;
        private final Set<String> retranscribed = new HashSet<>();

        StageRouting(Predicate<ExchangeKey> forced, ExchangeSource rerecord, ExchangeSource reuse) {
            this.forced = forced;
            this.rerecord = rerecord;
            this.reuse = reuse;
        }

        @Override
        public Exchange obtain(ExchangeKey key, Supplier<LlmRequest> request) {
            boolean force = isForced(key);
            if (force && Stage.TRANSCRIBE.owns(key.stage())) {
                retranscribed.add(key.documentId());
            }
            return (force ? rerecord : reuse).obtain(key, request);
        }

        @Override
        public Exchange retry(ExchangeKey key, Supplier<LlmRequest> request) {
            return (isForced(key) ? rerecord : reuse).retry(key, request);
        }

        private boolean isForced(ExchangeKey key) {
            return forced.test(key) || retranscribed.contains(key.documentId());
        }
    }
}
