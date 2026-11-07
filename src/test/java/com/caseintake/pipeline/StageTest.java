package com.caseintake.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StageTest {
    @Test
    void cliValuesSelectTheStage() {
        assertThat(Stage.fromCli("cases")).contains(Stage.CASES);
        assertThat(Stage.fromCli("extract")).contains(Stage.EXTRACT);
        assertThat(Stage.fromCli("bogus")).isEmpty();
        assertThat(Stage.fromCli("CASES")).isEmpty();
    }

    @Test
    void fixtureKeysAndOwnership() {
        assertThat(Stage.CASES.fixtureKey()).isEqualTo("cases");
        assertThat(Stage.EXTRACT.fixtureKey(2)).isEqualTo("extract-case-2");
        assertThat(Stage.CASES.owns("cases")).isTrue();
        assertThat(Stage.CASES.owns("extract-case-0")).isFalse();
        assertThat(Stage.EXTRACT.owns("extract-case-12")).isTrue();
        assertThat(Stage.EXTRACT.owns("cases")).isFalse();
        assertThat(Stage.EXTRACT.owns("extract-case-")).isFalse();
    }
}
