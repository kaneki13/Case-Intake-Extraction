package com.caseintake.pipeline;

import com.caseintake.catalog.Catalog;
import com.caseintake.extraction.CaseCandidate;
import com.caseintake.extraction.CaseFinder;
import com.caseintake.extraction.CaseFinding;
import com.caseintake.extraction.DocumentFlag;
import com.caseintake.extraction.FieldAssembler;
import com.caseintake.extraction.ResponseShapeException;
import com.caseintake.fixtures.Exchange;
import com.caseintake.fixtures.ExchangeKey;
import com.caseintake.fixtures.ExchangeSource;
import com.caseintake.llm.DailyQuotaExhaustedException;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs one document through case finding and per-case extraction (LLD §11). Every model exchange comes from the
 * given {@link ExchangeSource}, so the same code serves extract (recording) and replay (fixtures only). Request
 * suppliers are evaluated only when a live call is made, so replay never needs model ids or a key.
 */
public class DocumentPipeline {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PromptBuilder prompts;
    private final String caseFindingModel;
    private final String extractionModel;
    private final Consumer<String> log;

    public DocumentPipeline(PromptBuilder prompts, String caseFindingModel, String extractionModel,
                            Consumer<String> log) {
        this.prompts = prompts;
        this.caseFindingModel = caseFindingModel;
        this.extractionModel = extractionModel;
        this.log = log;
    }

    public ExtractionOutput process(SourceDocument doc, Catalog catalog, ExchangeSource source) {
        if (doc.txt().isEmpty()) {
            return new ExtractionOutput(doc.id(), catalog.version(), null,
                    List.of(DocumentFlag.UNSUPPORTED_FORMAT.json()), List.of());
        }
        SourceText text = SourceText.read(doc.id(), doc.txt().get());
        String marker = DocumentFence.newMarker();

        Parsed<CaseFinding> found = withRepair(source, new ExchangeKey(doc.id(), Stage.CASES.fixtureKey()),
                hint -> request(caseFindingModel, prompts.caseFindingPrompt(text, marker, hint),
                        ResponseSchemaFactory.caseFindingSchema()),
                output -> CaseFinder.find(text, output));
        CaseFinding finding = found.value();
        finding.notes().forEach(note -> log.accept(doc.id() + ": note: " + note));

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
                            finding.hints(), marker, hint), ResponseSchemaFactory.extractionSchema(catalog)),
                    output -> FieldAssembler.assemble(catalog, jsonObject(output).get("fields"), text,
                            OptionalInt.of(target.anchor().start()), finding.hints()));
            if (i == 0) {
                model = qualifiedModel(fields.exchange());
            }
            cases.add(new CaseOutput(i, target.label(), fields.value()));
        }
        List<String> flags = finding.flags().stream().map(DocumentFlag::json).sorted().toList();
        return new ExtractionOutput(doc.id(), catalog.version(), model, flags, cases);
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
