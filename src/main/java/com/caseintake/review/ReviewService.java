package com.caseintake.review;

import com.caseintake.api.DocumentApiException;
import com.caseintake.api.ExtractionReader;
import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.config.AppProperties;
import com.caseintake.validation.FieldValidator;
import com.caseintake.validation.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Applies reviewer decisions to the review store and merges them with the out/ results, which it only reads.
 * Stored reviews for a case index the output no longer has, or a field id the catalog no longer has, are kept on
 * disk but ignored in every merge, count and bulk action.
 */
@Service
public class ReviewService {
    private enum Action { ACCEPT, REJECT, EDIT, RESET }

    private final ReviewStore store;
    private final Catalog catalog;
    private final ExtractionReader reader;
    private final Clock clock;
    private final List<String> fieldOrder;

    @Autowired
    public ReviewService(AppProperties props, Catalog catalog, ExtractionReader reader, Clock clock) {
        this(new ReviewStore(props.reviews()), catalog, reader, clock);
    }

    public ReviewService(ReviewStore store, Catalog catalog, ExtractionReader reader, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.reader = reader;
        this.clock = clock;
        this.fieldOrder = catalog.fields().stream().map(FieldDef::id).toList();
    }

    public MergedField apply(String id, int caseIndex, String fieldId, ReviewRequest request) {
        JsonNode extraction = reader.read(id);
        Action action = parseAction(request.action(), Set.of(Action.values()));
        boolean hasValue = request.value() != null;
        if (action == Action.EDIT && !hasValue) {
            throw badRequest("An edit needs a value");
        }
        if (action != Action.EDIT && hasValue) {
            throw badRequest("A value is only allowed with action edit");
        }
        JsonNode caseNode = caseAt(extraction, id, caseIndex);
        FieldDef def = catalog.field(fieldId).orElseThrow(() -> notFound("No catalog field with id " + fieldId));
        FieldReview review = switch (action) {
            case ACCEPT -> new FieldReview(ReviewState.ACCEPTED, null, now());
            case REJECT -> new FieldReview(ReviewState.REJECTED, null, now());
            case RESET -> null;
            case EDIT -> {
                ValidationResult result = FieldValidator.validate(def, request.value());
                if (!result.ok()) {
                    throw badRequest("Invalid value for field " + fieldId + ": " + result.error());
                }
                yield new FieldReview(ReviewState.EDITED, request.value(), now());
            }
        };
        storeAccess(id, () -> store.locked(id, () -> {
            DocumentReviews reviews = store.load(id);
            store.save(id, reviews.with(caseIndex, Collections.singletonMap(fieldId, review), fieldOrder));
            return null;
        }));
        return new MergedField(fieldId, caseNode.path("fields").get(fieldId), review);
    }

    /** Applies accept or reject to the unreviewed fields among the requested ones (all catalog fields by default). */
    public List<MergedField> applyBulk(String id, int caseIndex, BulkReviewRequest request) {
        JsonNode extraction = reader.read(id);
        Action action = parseAction(request.action(), Set.of(Action.ACCEPT, Action.REJECT));
        JsonNode caseNode = caseAt(extraction, id, caseIndex);
        List<String> targets = targets(request.fieldIds());
        ReviewState state = action == Action.ACCEPT ? ReviewState.ACCEPTED : ReviewState.REJECTED;
        Map<String, FieldReview> changes = storeAccess(id, () -> store.locked(id, () -> {
            DocumentReviews reviews = store.load(id);
            Map<String, FieldReview> applied = new LinkedHashMap<>();
            for (String fieldId : targets) {
                if (reviews.get(caseIndex, fieldId) == null) {
                    applied.put(fieldId, new FieldReview(state, null, now()));
                }
            }
            if (!applied.isEmpty()) {
                store.save(id, reviews.with(caseIndex, applied, fieldOrder));
            }
            return applied;
        }));
        List<MergedField> result = new ArrayList<>();
        changes.forEach((fieldId, review) ->
                result.add(new MergedField(fieldId, caseNode.path("fields").get(fieldId), review)));
        return result;
    }

    public Progress progress(String id) {
        JsonNode extraction = reader.read(id);
        return progress(extraction, current(id, extraction));
    }

    public Progress progress(JsonNode extraction, Map<Integer, Map<String, FieldReview>> current) {
        int reviewed = current.values().stream().mapToInt(Map::size).sum();
        return new Progress(reviewed, extraction.path("cases").size() * fieldOrder.size());
    }

    /**
     * The stored reviews that still apply to this output and catalog: cases ascending, fields in catalog order,
     * cases without such reviews left out.
     */
    public Map<Integer, Map<String, FieldReview>> current(String id, JsonNode extraction) {
        DocumentReviews reviews = storeAccess(id, () -> store.load(id));
        int caseCount = extraction.path("cases").size();
        Map<Integer, Map<String, FieldReview>> result = new TreeMap<>();
        reviews.cases().forEach((caseIndex, fields) -> {
            if (caseIndex < 0 || caseIndex >= caseCount) {
                return;
            }
            Map<String, FieldReview> kept = new LinkedHashMap<>();
            for (String fieldId : fieldOrder) {
                FieldReview review = fields.get(fieldId);
                if (review != null) {
                    kept.put(fieldId, review);
                }
            }
            if (!kept.isEmpty()) {
                result.put(caseIndex, Collections.unmodifiableMap(kept));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private List<String> targets(List<String> fieldIds) {
        if (fieldIds == null) {
            return fieldOrder;
        }
        if (fieldIds.stream().anyMatch(Objects::isNull)) {
            throw badRequest("fieldIds must not contain null");
        }
        Set<String> requested = new HashSet<>(fieldIds);
        Set<String> unknown = new LinkedHashSet<>(fieldIds);
        unknown.removeAll(fieldOrder);
        if (!unknown.isEmpty()) {
            throw notFound("No catalog field with id " + String.join(", ", unknown));
        }
        return fieldOrder.stream().filter(requested::contains).toList();
    }

    private static Action parseAction(String raw, Set<Action> allowed) {
        for (Action action : allowed) {
            if (action.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return action;
            }
        }
        List<String> names = allowed.stream().sorted().map(a -> a.name().toLowerCase(Locale.ROOT)).toList();
        throw badRequest("action must be one of " + names);
    }

    /** Review time, to the millisecond, so clients get a plain ISO-8601 UTC string. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static JsonNode caseAt(JsonNode extraction, String id, int caseIndex) {
        JsonNode cases = extraction.path("cases");
        if (caseIndex < 0 || caseIndex >= cases.size()) {
            throw notFound("Document " + id + " has no case " + caseIndex);
        }
        return cases.get(caseIndex);
    }

    private static <T> T storeAccess(String id, Supplier<T> action) {
        try {
            return action.get();
        } catch (UncheckedIOException | IllegalStateException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Reviews for document " + id + " cannot be read or written", e);
        }
    }

    private static DocumentApiException badRequest(String message) {
        return new DocumentApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static DocumentApiException notFound(String message) {
        return new DocumentApiException(HttpStatus.NOT_FOUND, message);
    }
}
