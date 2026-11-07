package com.caseintake.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.catalog.OptionDef;
import com.caseintake.catalog.ValidationRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FieldValidatorTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static FieldDef def(FieldType t, ValidationRules v, String... codes) {
        return new FieldDef("f", "F", "s", t, java.util.Arrays.stream(codes).map(c -> new OptionDef(c, c)).toList(), v, "");
    }

    private static ValidationResult run(FieldDef d, String json) throws Exception {
        return FieldValidator.validate(d, M.readTree(json));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"54|true", "54.5|false", "\"54\"|false", "121|false", "54.0|false", "-1|false", "120|true"})
    void integer(String json, boolean ok) throws Exception {
        var d = def(FieldType.INTEGER, new ValidationRules(Optional.empty(), Optional.of(BigDecimal.ZERO), Optional.of(new BigDecimal("120"))));
        assertThat(run(d, json).ok()).isEqualTo(ok);
    }

    @Test
    void integerMessages() throws Exception {
        var d = def(FieldType.INTEGER, new ValidationRules(Optional.empty(), Optional.of(BigDecimal.ZERO), Optional.of(new BigDecimal("120"))));
        assertThat(run(d, "121").error()).isEqualTo("value 121 above max 120");
        assertThat(run(d, "-1").error()).isEqualTo("value -1 below min 0");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"2026-02-28\"|true", "\"2026-02-30\"|false", "\"2026-3-4\"|false", "20260304|false"})
    void date(String json, boolean ok) throws Exception {
        assertThat(run(def(FieldType.DATE, ValidationRules.NONE), json).ok()).isEqualTo(ok);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"MK\"|true", "\"M.K.\"|false", "\"ABCDE\"|false", "\"\"|false", "\" \"|false"})
    void patternMatchesWholeValue(String json, boolean ok) throws Exception {
        var d = def(FieldType.STRING, new ValidationRules(Optional.of(Pattern.compile("^[A-Z]{1,4}$")), Optional.empty(), Optional.empty()));
        assertThat(run(d, json).ok()).isEqualTo(ok);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"[\"x\"]|true", "[\"x\",\"y\"]|true", "[]|false", "[\"x\",\"x\"]|false", "[\"z\"]|false", "\"x\"|false"})
    void codedMulti(String json, boolean ok) throws Exception {
        assertThat(run(def(FieldType.CODED_MULTI, ValidationRules.NONE, "x", "y"), json).ok()).isEqualTo(ok);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"[\"none\"]|true", "[\"none\",\"x\"]|false", "[\"x\",\"none\"]|false",
            "[\"x\",\"y\"]|true"})
    void noneStandsAloneWhenTheFieldOffersIt(String json, boolean ok) throws Exception {
        assertThat(run(def(FieldType.CODED_MULTI, ValidationRules.NONE, "x", "y", "none"), json).ok()).isEqualTo(ok);
    }

    @Test
    void noneCombinedMessage() throws Exception {
        var d = def(FieldType.CODED_MULTI, ValidationRules.NONE, "x", "none");
        assertThat(run(d, "[\"none\",\"x\"]").error()).isEqualTo("value [\"none\",\"x\"] combines \"none\" with other codes");
    }

    @Test
    void noneRuleDoesNotApplyWhenTheFieldHasNoNoneOption() throws Exception {
        var d = def(FieldType.CODED_MULTI, ValidationRules.NONE, "x", "y");
        assertThat(run(d, "[\"x\",\"y\"]").ok()).isTrue();
        assertThat(run(d, "[\"none\",\"x\"]").error()).isEqualTo("value none is not one of [x, y]");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"[]|true", "[\"a\"]|true", "[\" \"]|false", "[1]|false", "\"a\"|false"})
    void stringList(String json, boolean ok) throws Exception {
        assertThat(run(def(FieldType.STRING_LIST, ValidationRules.NONE), json).ok()).isEqualTo(ok);
    }

    @Test
    void codedValueIsCaseSensitive() throws Exception {
        var d = def(FieldType.CODED, ValidationRules.NONE, "female");
        assertThat(run(d, "\"female\"").ok()).isTrue();
        var r = run(d, "\"Female\"");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("value Female is not one of [female]");
    }

    @Test
    void nullAndMissingFail() {
        var d = def(FieldType.STRING, ValidationRules.NONE);
        assertThat(FieldValidator.validate(d, M.nullNode()).error()).isEqualTo("value is missing");
        assertThat(FieldValidator.validate(d, null).ok()).isFalse();
    }

    @Test
    void numberAcceptsDecimalsRejectsStrings() throws Exception {
        var d = def(FieldType.NUMBER, ValidationRules.NONE);
        assertThat(run(d, "54.5").ok()).isTrue();
        assertThat(run(d, "\"5\"").ok()).isFalse();
    }

    @Test
    void patternErrorMessage() throws Exception {
        var d = def(FieldType.STRING, new ValidationRules(Optional.of(Pattern.compile("^[A-Z]{1,4}$")), Optional.empty(), Optional.empty()));
        assertThat(run(d, "\"M.K.\"").error()).isEqualTo("value M.K. does not match pattern ^[A-Z]{1,4}$");
    }
}
