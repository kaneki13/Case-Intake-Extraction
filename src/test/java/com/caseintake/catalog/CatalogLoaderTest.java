package com.caseintake.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.support.CatalogFixtures;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogLoaderTest {
    @TempDir Path dir;

    private int firstCodedIndex(ObjectNode root) {
        ArrayNode fields = (ArrayNode) root.get("fields");
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).get("type").asText().equals("coded")) return i;
        }
        throw new IllegalStateException("no coded field");
    }

    @Test
    void loadsCommittedCatalog() {
        ObjectNode raw = CatalogFixtures.readCommitted();
        Catalog catalog = CatalogLoader.load(CatalogFixtures.COMMITTED);

        assertThat(catalog.fields()).isNotEmpty().hasSize(raw.get("fields").size());
        assertThat(catalog.sections()).hasSize(raw.get("sections").size());
        assertThat(catalog.confidenceLevels()).containsExactly("high", "medium", "low");
        assertThat(catalog.fields().get(0).type()).isEqualTo(FieldType.STRING);
        assertThat(catalog.raw()).isEqualTo(raw);
        FieldDef first = catalog.fields().get(0);
        assertThat(catalog.field(first.id())).contains(first);
        assertThat(catalog.field("no_such_field")).isEmpty();
        assertThat(catalog.fieldsInSection(first.sectionId())).contains(first);
        assertThat(first.validation().pattern()).isPresent();
    }

    @Test
    void rejectsUnknownType() {
        Path p = CatalogFixtures.mutated(dir, r -> CatalogFixtures.field(r, 0).put("type", "banana"));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageContaining("banana");
    }

    @Test
    void rejectsUnknownValidationKey() {
        Path p = CatalogFixtures.mutated(dir,
                r -> ((ObjectNode) CatalogFixtures.field(r, 0).get("validation")).put("maxLength", 3));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageContaining("maxLength");
    }

    @Test
    void rejectsDuplicateFieldId() {
        Path p = CatalogFixtures.mutated(dir, r -> {
            String id = CatalogFixtures.field(r, 0).get("id").asText();
            CatalogFixtures.field(r, 1).put("id", id);
        });
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsCodedFieldWithoutOptions() {
        Path p = CatalogFixtures.mutated(dir, r -> CatalogFixtures.field(r, firstCodedIndex(r)).remove("options"));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageContaining("options");
    }

    @Test
    void rejectsUnknownSection() {
        Path p = CatalogFixtures.mutated(dir, r -> CatalogFixtures.field(r, 0).put("section", "nowhere"));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageContaining("nowhere");
    }

    @Test
    void rejectsStatusKeysThatDifferFromFieldStatuses() {
        Path p = CatalogFixtures.mutated(dir, r -> ((ObjectNode) r.get("statuses")).put("extra", "x"));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageContaining("statuses");
    }

    @Test
    void rejectsValidationThatIsNotAnObject() {
        Path p = CatalogFixtures.mutated(dir, r -> CatalogFixtures.field(r, 0).put("validation", "^[A-Z]$"));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageEndingWith(": validation must be an object");
    }

    @Test
    void rejectsPatternThatIsNotText() {
        Path p = CatalogFixtures.mutated(dir,
                r -> ((ObjectNode) CatalogFixtures.field(r, 0).get("validation")).put("pattern", 5));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageEndingWith(": validation pattern must be a string");
    }

    @Test
    void rejectsMissingOrBlankCatalogVersion() {
        Path blank = CatalogFixtures.mutated(dir, r -> r.put("catalog_version", " "));
        assertThatThrownBy(() -> CatalogLoader.load(blank))
                .isInstanceOf(CatalogException.class)
                .hasMessage("catalog 'catalog_version' must be a non-blank string");
        Path missing = CatalogFixtures.mutated(dir, r -> r.remove("catalog_version"));
        assertThatThrownBy(() -> CatalogLoader.load(missing))
                .isInstanceOf(CatalogException.class)
                .hasMessage("catalog 'catalog_version' must be a non-blank string");
    }

    @Test
    void rejectsDuplicateConfidenceLevels() {
        Path p = CatalogFixtures.mutated(dir, r -> {
            ArrayNode levels = r.putArray("confidence_levels");
            levels.add("high").add("medium").add("high");
        });
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessage("duplicate confidence level 'high'");
    }

    @Test
    void typeNamesMatchExactlyInLowerCase() {
        assertThat(FieldType.fromJson("coded_multi")).isEqualTo(FieldType.CODED_MULTI);
        assertThatThrownBy(() -> FieldType.fromJson("CODED_MULTI")).isInstanceOf(CatalogException.class)
                .hasMessage("unknown field type 'CODED_MULTI'");
        assertThatThrownBy(() -> FieldType.fromJson("Coded")).isInstanceOf(CatalogException.class);
        Path p = CatalogFixtures.mutated(dir, r -> CatalogFixtures.field(r, 0).put("type", "String"));
        assertThatThrownBy(() -> CatalogLoader.load(p))
                .isInstanceOf(CatalogException.class)
                .hasMessageEndingWith(": unknown field type 'String'");
    }

    @Test
    void lowerConfidenceClampsAtLowest() {
        Catalog c = CatalogLoader.load(CatalogFixtures.COMMITTED);
        assertThat(c.lowerConfidence("medium", 5)).isEqualTo("low");
        assertThat(c.lowerConfidence("high", 1)).isEqualTo("medium");
        assertThat(c.lowerConfidence("high", 0)).isEqualTo("high");
        assertThatThrownBy(() -> c.lowerConfidence("bogus", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minConfidenceReturnsLowerRanked() {
        Catalog c = CatalogLoader.load(CatalogFixtures.COMMITTED);
        assertThat(c.minConfidence("high", "medium")).isEqualTo("medium");
        assertThat(c.minConfidence("low", "high")).isEqualTo("low");
        assertThatThrownBy(() -> c.minConfidence("high", "bogus")).isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(FieldType.values())).contains(FieldType.CODED_MULTI);
        assertThat(FieldType.fromJson("coded_multi")).isEqualTo(FieldType.CODED_MULTI);
    }
}
