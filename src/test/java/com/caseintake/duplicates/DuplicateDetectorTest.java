package com.caseintake.duplicates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.CatalogLoader;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.output.CaseOutput;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.FieldResult;
import com.caseintake.output.FieldStatus;
import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Synthetic outputs built from the real catalog by section and type; no field id is written here. */
class DuplicateDetectorTest {
    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Catalog CATALOG = CatalogLoader.load(CatalogFixtures.COMMITTED);
    private static final List<String> REQUIRED = List.of("patient", "drug", "event");
    private static final Set<String> ALL = Set.copyOf(REQUIRED);
    private static final int ALL_FIELDS = (int) CATALOG.fields().stream()
            .filter(f -> ALL.contains(f.sectionId())).count();

    private static DuplicateProperties props(int min, int high) {
        return props(min, high, 4);
    }

    private static DuplicateProperties props(int min, int high, int maxDifferences) {
        return new DuplicateProperties("patient", REQUIRED, min, high, 1, maxDifferences);
    }

    /** A value for the type; the same seed gives the same value, a different seed a different one. */
    private static JsonNode value(FieldDef f, int seed) {
        return switch (f.type()) {
            case STRING -> J.textNode("text" + seed);
            case INTEGER -> J.numberNode(40 + seed * 10);
            case NUMBER -> J.numberNode(seed * 100 + 0.5);
            case DATE -> J.textNode("2026-01-0" + seed);
            case CODED -> J.textNode(f.options().get(seed % f.options().size()).code());
            case CODED_MULTI -> J.arrayNode().add(f.options().get(seed % f.options().size()).code());
            case STRING_LIST -> J.arrayNode().add("item" + seed);
        };
    }

    private static FieldResult unknown() {
        return new FieldResult(FieldStatus.UNKNOWN, null, "low", List.of(), null);
    }

    /** A case with values (seed) in the given sections and unknown elsewhere. */
    private static LinkedHashMap<String, FieldResult> fields(Set<String> sections, int seed) {
        LinkedHashMap<String, FieldResult> map = new LinkedHashMap<>();
        for (FieldDef f : CATALOG.fields()) {
            map.put(f.id(), sections.contains(f.sectionId())
                    ? new FieldResult(FieldStatus.EXTRACTED, value(f, seed), "high", List.of(), null) : unknown());
        }
        return map;
    }

    private static ExtractionOutput doc(String id, LinkedHashMap<String, FieldResult>... cases) {
        List<CaseOutput> list = new ArrayList<>();
        for (int i = 0; i < cases.length; i++) {
            list.add(new CaseOutput(i, "case " + i, cases[i]));
        }
        return new ExtractionOutput(id, CATALOG.version(), "m", List.of(), list);
    }

    @SafeVarargs
    private static ExtractionOutput one(String id, LinkedHashMap<String, FieldResult>... cases) {
        return doc(id, cases);
    }

    private static void set(LinkedHashMap<String, FieldResult> m, FieldDef f, int seed) {
        m.put(f.id(), new FieldResult(FieldStatus.EXTRACTED, value(f, seed), "high", List.of(), null));
    }

    private static FieldDef firstOfType(String section, FieldType type) {
        return CATALOG.fieldsInSection(section).stream().filter(f -> f.type() == type).findFirst().orElseThrow();
    }

    @Test
    void sameCaseInTwoDocumentsIsGrouped() {
        var report = DuplicateDetector.detect(
                List.of(one("B", fields(ALL, 1)), one("A", fields(ALL, 1))), CATALOG, props(5, 8));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().get(0).documentIds()).containsExactly("A", "B");
        assertThat(report.pairs()).hasSize(1);
        assertThat(report.pairs().get(0).agreements()).isEqualTo(ALL_FIELDS);
    }

    @Test
    void identityContradictionVetoes() {
        // the D01/S04 shape: everything else agrees, only the patient string differs
        var a = fields(ALL, 1);
        var b = fields(ALL, 1);
        set(b, firstOfType("patient", FieldType.STRING), 2);

        var report = DuplicateDetector.detect(List.of(one("A", a), one("B", b)), CATALOG, props(5, 8));

        assertThat(report.groups()).isEmpty();
        assertThat(report.pairs()).isEmpty();
    }

    /**
     * The D01/S04 shape without the identity veto: the patient string is present on one side only, the rest of the
     * patient agrees, one drug field and every event field agree, and the other five drug fields differ.
     */
    private static List<ExtractionOutput> manyDifferences() {
        var a = fields(ALL, 1);
        var b = fields(ALL, 1);
        b.put(firstOfType("patient", FieldType.STRING).id(), unknown());
        List<FieldDef> drug = CATALOG.fieldsInSection("drug");
        for (FieldDef f : drug.subList(1, drug.size())) {
            set(b, f, 2);
        }
        return List.of(one("A", a), one("B", b));
    }

    @Test
    void moreDifferencesThanMaximumRejectEvenWithoutIdentityContradiction() {
        assertThat(CATALOG.fieldsInSection("drug")).hasSize(6);

        // five differing fields: allowed at a maximum of 5, rejected at 4 (the application.yml setting)
        assertThat(DuplicateDetector.detect(manyDifferences(), CATALOG, props(5, 8, 5)).groups()).hasSize(1);
        var report = DuplicateDetector.detect(manyDifferences(), CATALOG, props(5, 8, 4));

        assertThat(report.groups()).isEmpty();
        assertThat(report.pairs()).isEmpty();
    }

    @Test
    void twoNonIdentityDifferencesAreStillGrouped() {
        // the S01/S02 shape: two fields outside the identity section differ, everything else agrees
        var a = fields(ALL, 1);
        var b = fields(ALL, 1);
        set(b, firstOfType("drug", FieldType.DATE), 2);
        set(b, firstOfType("event", FieldType.DATE), 2);

        var report = DuplicateDetector.detect(List.of(one("A", a), one("B", b)), CATALOG, props(5, 8, 4));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.pairs().get(0).agreements()).isEqualTo(ALL_FIELDS - 2);
    }

    @Test
    void missingRequiredSectionAgreementRejects() {
        var report = DuplicateDetector.detect(
                List.of(one("A", fields(Set.of("patient", "drug"), 1)), one("B", fields(Set.of("patient", "drug"), 1))),
                CATALOG, props(5, 8));

        assertThat(report.groups()).isEmpty();
    }

    @Test
    void fewerAgreementsThanMinimumRejects() {
        var report = DuplicateDetector.detect(
                List.of(one("A", fields(ALL, 1)), one("B", fields(ALL, 1))), CATALOG, props(ALL_FIELDS + 1, ALL_FIELDS + 1));

        assertThat(report.groups()).isEmpty();
    }

    @Test
    void threeWayGroupViaUnionFind() {
        var report = DuplicateDetector.detect(
                List.of(one("C", fields(ALL, 1)), one("A", fields(ALL, 1)), one("B", fields(ALL, 1)),
                        one("D", fields(ALL, 3))),
                CATALOG, props(5, 8));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().get(0).documentIds()).containsExactly("A", "B", "C");
        assertThat(report.groups().get(0).reason()).endsWith("(+2 more document pairs)");
    }

    @Test
    void casesInTheSameDocumentAreNotCompared() {
        var report = DuplicateDetector.detect(
                List.of(one("A", fields(ALL, 1), fields(ALL, 1))), CATALOG, props(5, 8));

        assertThat(report.groups()).isEmpty();
    }

    @Test
    void confidenceHighAtHighAgreements() {
        var docs = List.of(one("A", fields(ALL, 1)), one("B", fields(ALL, 1)));

        assertThat(DuplicateDetector.detect(docs, CATALOG, props(5, ALL_FIELDS)).groups().get(0).confidence())
                .isEqualTo("high");
        assertThat(DuplicateDetector.detect(docs, CATALOG, props(5, ALL_FIELDS + 1)).groups().get(0).confidence())
                .isEqualTo("medium");
    }

    @Test
    void groupConfidenceIsTheLowestOfItsPairs() {
        var a = fields(ALL, 1);
        var b = fields(ALL, 1);
        var c = fields(ALL, 1);
        // C loses one agreement with everyone: it differs in a non-identity field
        set(c, firstOfType("drug", FieldType.DATE), 2);
        var report = DuplicateDetector.detect(
                List.of(one("A", a), one("B", b), one("C", c)), CATALOG, props(5, ALL_FIELDS));

        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().get(0).confidence()).isEqualTo("medium");
    }

    @Test
    void reasonListsMatchedValuesInCatalogOrder() {
        var report = DuplicateDetector.detect(
                List.of(one("A", fields(ALL, 1)), one("B", fields(ALL, 1))), CATALOG, props(5, 8));

        List<String> expected = new ArrayList<>();
        var sample = fields(ALL, 1);
        for (FieldDef f : CATALOG.fields()) {
            JsonNode v = sample.get(f.id()).value();
            if (v == null) {
                continue;
            }
            if (v.isArray()) {
                List<String> parts = new ArrayList<>();
                v.forEach(n -> parts.add(n.asText()));
                expected.add(String.join("/", parts));
            } else if (v.isNumber() && !v.isIntegralNumber()) {
                expected.add(v.decimalValue().stripTrailingZeros().toPlainString());
            } else {
                expected.add(v.asText());
            }
        }
        assertThat(report.groups().get(0).reason())
                .isEqualTo("Same patient and event: " + String.join("; ", expected.subList(0, 6)));
    }

    @Test
    void unknownSectionInConfigurationIsRejected() {
        var bad = new DuplicateProperties("nope", REQUIRED, 5, 8, 1, 4);
        assertThatThrownBy(() -> DuplicateDetector.detect(List.of(), CATALOG, bad))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
        var badRequired = new DuplicateProperties("patient", List.of("patient", "zzz"), 5, 8, 1, 4);
        assertThatThrownBy(() -> DuplicateDetector.detect(List.of(), CATALOG, badRequired))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zzz");
    }

    @Test
    void invalidThresholdsAreRejected() {
        assertThatThrownBy(() -> new DuplicateProperties("patient", REQUIRED, 0, 8, 1, 4).validate(CATALOG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DuplicateProperties("patient", REQUIRED, 5, 4, 1, 4).validate(CATALOG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DuplicateProperties("patient", REQUIRED, 5, 8, -1, 4).validate(CATALOG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DuplicateProperties("patient", REQUIRED, 5, 8, 1, -1).validate(CATALOG))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicates.max-differences");
        new DuplicateProperties("patient", REQUIRED, 5, 8, 1, 0).validate(CATALOG);
    }
}
