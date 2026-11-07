package com.caseintake.pipeline;

import com.caseintake.catalog.Catalog;
import com.caseintake.extraction.CaseCandidate;
import com.caseintake.extraction.CaseFinder;
import com.caseintake.extraction.CaseFinding;
import com.caseintake.extraction.DocumentFlag;
import com.caseintake.extraction.DocumentHints;
import com.caseintake.extraction.FieldAssembler;
import com.caseintake.extraction.ResponseShapeException;
import com.caseintake.fixtures.Exchange;
import com.caseintake.fixtures.ExchangeKey;
import com.caseintake.fixtures.ExchangeSource;
import com.caseintake.llm.DailyQuotaExhaustedException;
import com.caseintake.llm.LlmAttachment;
import com.caseintake.llm.LlmRequest;
import com.caseintake.output.CaseOutput;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.FieldResult;
import com.caseintake.output.OutputWriter;
import com.caseintake.prompt.DocumentFence;
import com.caseintake.prompt.Prompt;
import com.caseintake.prompt.PromptBuilder;
import com.caseintake.prompt.ResponseSchemaFactory;
import com.caseintake.text.SourceText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runs one document through transcription (PDF-only documents), case finding and per-case extraction (LLD §11).
 * Every model exchange comes from the given {@link ExchangeSource}, so the same code serves extract (recording) and
 * replay (fixtures only). Request suppliers are evaluated only when a live call is made, so replay never needs model
 * ids, a key or the PDF bytes.
 */
public class DocumentPipeline {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PDF_MIME_TYPE = "application/pdf";

    private final PromptBuilder prompts;
    private final String caseFindingModel;
    private final String extractionModel;
    private final String transcriptionModel;
    private final TranscriptStore transcripts;
    private final Consumer<String> log;
    private final Supplier<String> markers;

    public DocumentPipeline(PromptBuilder prompts, String caseFindingModel, String extractionModel,
                            String transcriptionModel, TranscriptStore transcripts, Consumer<String> log) {
        this(prompts, caseFindingModel, extractionModel, transcriptionModel, transcripts, log,
                DocumentFence::newMarker);
    }

    /** As the public constructor, with the fence markers taken from {@code markers} (tests fix the marker). */
    DocumentPipeline(PromptBuilder prompts, String caseFindingModel, String extractionModel,
                     String transcriptionModel, TranscriptStore transcripts, Consumer<String> log,
                     Supplier<String> markers) {
        this.prompts = prompts;
        this.caseFindingModel = caseFindingModel;
        this.extractionModel = extractionModel;
        this.transcriptionModel = transcriptionModel;
        this.transcripts = transcripts;
        this.log = log;
        this.markers = markers;
    }

    /**
     * A document with a {@code .txt} is read as is. Without one, a document with exactly one {@code .pdf} is
     * transcribed first: the transcript is written to the derived dir and becomes the source text. Anything else,
     * or a blank transcript, is {@code unsupported_format}.
     */
    public ExtractionOutput process(SourceDocument doc, Catalog catalog, ExchangeSource source) {
        SourceText text;
        boolean transcribed = doc.txt().isEmpty();
        if (!transcribed) {
            text = SourceText.read(doc.id(), doc.txt().get());
        } else {
            List<Path> pdfs = doc.others().stream().filter(DocumentPipeline::isPdf).toList();
            if (pdfs.size() != 1) {
                if (pdfs.size() > 1) {
                    log.accept(doc.id() + ": " + pdfs.size() + " PDF files and no .txt; transcription needs exactly "
                            + "one, so the document is unsupported");
                }
                return unsupported(doc, catalog);
            }
            String transcript = transcribe(doc.id(), pdfs.get(0), source);
            if (transcript.isBlank()) {
                log.accept(doc.id() + ": blank transcript; the document is unsupported");
                return unsupported(doc, catalog);
            }
            transcripts.write(doc.id(), transcript);
            text = SourceText.of(doc.id(), transcript);
        }
        String marker = markers.get();

        Parsed<CaseFinding> found = withRepair(source, new ExchangeKey(doc.id(), Stage.CASES.fixtureKey()),
                hint -> request(caseFindingModel, prompts.caseFindingPrompt(text, marker, hint),
                        ResponseSchemaFactory.caseFindingSchema()),
                output -> CaseFinder.find(text, output));
        CaseFinding finding = found.value();
        finding.notes().forEach(note -> log.accept(doc.id() + ": note: " + note));
        DocumentHints hints = transcribed ? finding.hints().transcribed() : finding.hints();

        String model = qualifiedModel(found.exchange());
        List<CaseCandidate> candidates = finding.cases();
        List<CaseOutput> cases = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            CaseCandidate target = candidates.get(i);
            List<CaseCandidate> others = new ArrayList<>(candidates);
            others.remove(i);
            Parsed<LinkedHashMap<String, FieldResult>> fields = withRepair(source,
                    new ExchangeKey(doc.id(), Stage.EXTRACT.fixtureKey(i)),
                    hint -> request(extractionModel, prompts.extractionPrompt(catalog, text, target, others,
                            hints, marker, hint), ResponseSchemaFactory.extractionSchema(catalog)),
                    output -> FieldAssembler.assemble(catalog, jsonObject(output).get("fields"), text,
                            OptionalInt.of(target.anchor().start()), hints));
            if (i == 0) {
                model = qualifiedModel(fields.exchange());
            }
            cases.add(new CaseOutput(i, target.label(), fields.value()));
        }
        EnumSet<DocumentFlag> flagSet = EnumSet.copyOf(finding.flags());
        if (transcribed) {
            flagSet.add(DocumentFlag.TRANSCRIBED_FROM_IMAGE);
        }
        List<String> flags = flagSet.stream().map(DocumentFlag::json).sorted().toList();
        return new ExtractionOutput(doc.id(), catalog.version(), model, flags, cases);
    }

    private static ExtractionOutput unsupported(SourceDocument doc, Catalog catalog) {
        return new ExtractionOutput(doc.id(), catalog.version(), null,
                List.of(DocumentFlag.UNSUPPORTED_FORMAT.json()), List.of());
    }

    private static boolean isPdf(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** The transcript of the PDF, from the {@code transcribe} exchange with the repair retry. */
    private String transcribe(String id, Path pdf, ExchangeSource source) {
        Parsed<Transcription> parsed = withRepair(source, new ExchangeKey(id, Stage.TRANSCRIBE.fixtureKey()),
                hint -> {
                    Prompt prompt = prompts.transcriptionPrompt(hint);
                    return new LlmRequest(transcriptionModel, prompt.system(), prompt.user(),
                            ResponseSchemaFactory.transcriptionSchema(),
                            List.of(new LlmAttachment(PDF_MIME_TYPE, readBytes(pdf))));
                },
                DocumentPipeline::transcription);
        log.accept(id + ": transcribed " + pdf.getFileName() + " (" + parsed.value().illegibleCount()
                + " illegible span(s))");
        return parsed.value().transcript();
    }

    private record Transcription(String transcript, int illegibleCount) {}

    private static Transcription transcription(String output) {
        JsonNode root = jsonObject(output);
        JsonNode transcript = root.get("transcript");
        if (transcript == null || !transcript.isTextual()) {
            throw new ResponseShapeException("transcript missing or not a string");
        }
        JsonNode count = root.get("illegible_count");
        if (count == null || !count.canConvertToInt() || !count.isIntegralNumber() || count.intValue() < 0) {
            throw new ResponseShapeException("illegible_count missing or not a non-negative integer");
        }
        return new Transcription(transcript.asText(), count.intValue());
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Processes the selected documents in id order and writes each output. A failing document is reported and the
     * run continues; daily-quota exhaustion stops the run at once, keeping the outputs already written.
     */
    public RunReport run(List<SourceDocument> docs, Set<String> only, Catalog catalog, ExchangeSource source,
                         Path outDir) {
        List<String> written = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        Set<String> known = docs.stream().map(SourceDocument::id).collect(Collectors.toSet());
        for (String id : only) {
            if (!known.contains(id)) {
                failed.put(id, "not found in the document directories");
            }
        }
        List<SourceDocument> selected = docs.stream().filter(d -> only.isEmpty() || only.contains(d.id())).toList();
        for (int i = 0; i < selected.size(); i++) {
            SourceDocument doc = selected.get(i);
            try {
                ExtractionOutput output = process(doc, catalog, source);
                Path file = OutputWriter.write(outDir, output);
                written.add(doc.id());
                log.accept(doc.id() + ": wrote " + file + " (" + output.cases().size() + " case(s), flags "
                        + output.flags() + ")");
            } catch (DailyQuotaExhaustedException e) {
                failed.put(doc.id(), "daily quota exhausted: " + e.getMessage());
                List<String> remaining = selected.subList(i, selected.size()).stream().map(SourceDocument::id).toList();
                log.accept(doc.id() + ": daily quota exhausted; stopping the run");
                return new RunReport(written, failed, remaining);
            } catch (RuntimeException e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failed.put(doc.id(), reason);
                log.accept(doc.id() + ": FAILED: " + reason);
            }
        }
        return new RunReport(written, failed);
    }

    private record Parsed<T>(Exchange exchange, T value) {}

    /**
     * Parses the latest exchange; on a shape error asks once for a repair whose hint is the error message only
     * (machine-generated, never document text). A second shape error fails the document.
     */
    private <T> Parsed<T> withRepair(ExchangeSource source, ExchangeKey key,
                                     Function<Optional<String>, LlmRequest> request, Function<String, T> parse) {
        Exchange first = source.obtain(key, () -> request.apply(Optional.empty()));
        try {
            return new Parsed<>(first, parse.apply(first.outputText()));
        } catch (ResponseShapeException e) {
            log.accept(key.documentId() + ": " + key.stage() + " attempt " + first.attempt() + " rejected ("
                    + e.getMessage() + "); asking for a repair");
            Exchange second = source.retry(key, () -> request.apply(Optional.of(e.getMessage())));
            try {
                return new Parsed<>(second, parse.apply(second.outputText()));
            } catch (ResponseShapeException again) {
                throw new DocumentFailedException(key.stage() + ": response rejected after repair attempt "
                        + second.attempt() + ": " + again.getMessage());
            }
        }
    }

    private static LlmRequest request(String model, Prompt prompt, JsonNode schema) {
        return new LlmRequest(model, prompt.system(), prompt.user(), schema, List.of());
    }

    private static JsonNode jsonObject(String output) {
        JsonNode root;
        try {
            root = output == null ? null : JSON.readTree(output);
        } catch (Exception e) {
            throw new ResponseShapeException("response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ResponseShapeException("response is not a JSON object");
        }
        return root;
    }

    private static String qualifiedModel(Exchange exchange) {
        return exchange.provider() + "/" + exchange.model();
    }
}
