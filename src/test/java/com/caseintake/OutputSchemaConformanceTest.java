package com.caseintake;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.support.CatalogFixtures;
import com.caseintake.support.SchemaAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every committed out/*.json is schema-valid and every case carries every catalog field (LLD §14). */
class OutputSchemaConformanceTest {

    @Test
    void everyOutputIsSchemaValidAndHasEveryField() throws Exception {
        List<String> catalogIds = new ArrayList<>();
        CatalogFixtures.readCommitted().get("fields").forEach(f -> catalogIds.add(f.get("id").asText()));
        List<String> files = ReplayGoldenTest.jsonFiles(ReplayGoldenTest.COMMITTED_OUT);
        assertThat(files).hasSize(12); // D01-D08 and the stretch documents S01-S04

        for (String name : files) {
            var path = ReplayGoldenTest.COMMITTED_OUT.resolve(name);
            SchemaAssertions.assertValid(path);
            JsonNode root = ReplayGoldenTest.JSON.readTree(path.toFile());
            for (JsonNode c : root.get("cases")) {
                List<String> ids = new ArrayList<>();
                c.get("fields").fieldNames().forEachRemaining(ids::add);
                assertThat(ids).as(name + " case " + c.get("case_index")).containsExactlyElementsOf(catalogIds);
            }
        }
    }
}
