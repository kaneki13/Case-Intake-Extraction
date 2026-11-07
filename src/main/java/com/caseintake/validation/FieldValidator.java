package com.caseintake.validation;

import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.OptionDef;
import com.caseintake.catalog.ValidationRules;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks one model value against one catalog field definition. Never mutates or coerces the value. */
public final class FieldValidator {
    private static final Pattern DATE_SHAPE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * The option code that means "no item applies" and so must stand alone in a {@code coded_multi} value
     * (GLOSSARY, "Seriousness": "{@code none} means no criterion applies and stands alone"; the exercise
     * checker {@code tools/check.py} rejects a list holding {@code none} with any other code). The rule applies
     * to every {@code coded_multi} field whose catalog options include this code, and to no other field.
     */
    public static final String STAND_ALONE_CODE = "none";

    private FieldValidator() {}

    public static ValidationResult validate(FieldDef def, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return ValidationResult.fail("value is missing");
        }
        return switch (def.type()) {
            case STRING -> validateString(def, value);
            case INTEGER -> value.isIntegralNumber()
                    ? checkRange(def.validation(), value.decimalValue(), value)
                    : ValidationResult.fail("value " + value + " is not an integer");
            case NUMBER -> value.isNumber()
                    ? checkRange(def.validation(), value.decimalValue(), value)
                    : ValidationResult.fail("value " + value + " is not a number");
            case DATE -> validateDate(def, value);
            case CODED -> validateCoded(def, value);
            case CODED_MULTI -> validateCodedMulti(def, value);
            case STRING_LIST -> validateStringList(def, value);
        };
    }

    private static ValidationResult validateString(FieldDef def, JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            return ValidationResult.fail("value " + value + " is not a non-blank string");
        }
        return checkPattern(def.validation(), value.asText());
    }

    private static ValidationResult validateDate(FieldDef def, JsonNode value) {
        if (!value.isTextual()) {
            return ValidationResult.fail("value " + value + " is not a date string");
        }
        String s = value.asText();
        if (!DATE_SHAPE.matcher(s).matches()) {
            return ValidationResult.fail("value " + s + " is not in YYYY-MM-DD form");
        }
        try {
            LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return ValidationResult.fail("value " + s + " is not a real calendar date");
        }
        return checkPattern(def.validation(), s);
    }

    private static ValidationResult validateCoded(FieldDef def, JsonNode value) {
        if (!value.isTextual()) {
            return ValidationResult.fail("value " + value + " is not a string code");
        }
        return codes(def).contains(value.asText())
                ? ValidationResult.success()
                : notOneOf(def, value.asText());
    }

    private static ValidationResult validateCodedMulti(FieldDef def, JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            return ValidationResult.fail("value " + value + " is not a non-empty array");
        }
        Set<String> allowed = codes(def);
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                return ValidationResult.fail("value " + item + " is not a string code");
            }
            String code = item.asText();
            if (!allowed.contains(code)) {
                return notOneOf(def, code);
            }
            if (!seen.add(code)) {
                return ValidationResult.fail("value " + code + " is duplicated");
            }
        }
        if (allowed.contains(STAND_ALONE_CODE) && seen.contains(STAND_ALONE_CODE) && seen.size() > 1) {
            return ValidationResult.fail("value " + value + " combines \"" + STAND_ALONE_CODE + "\" with other codes");
        }
        return ValidationResult.success();
    }

    private static ValidationResult validateStringList(FieldDef def, JsonNode value) {
        if (!value.isArray()) {
            return ValidationResult.fail("value " + value + " is not an array");
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return ValidationResult.fail("value " + item + " is not a non-blank string");
            }
            ValidationResult r = checkPattern(def.validation(), item.asText());
            if (!r.ok()) {
                return r;
            }
        }
        return ValidationResult.success();
    }

    private static ValidationResult checkPattern(ValidationRules rules, String s) {
        if (rules != null && rules.pattern().isPresent() && !rules.pattern().get().matcher(s).matches()) {
            return ValidationResult.fail("value " + s + " does not match pattern " + rules.pattern().get().pattern());
        }
        return ValidationResult.success();
    }

    private static ValidationResult checkRange(ValidationRules rules, BigDecimal n, JsonNode value) {
        if (rules == null) {
            return ValidationResult.success();
        }
        if (rules.max().isPresent() && n.compareTo(rules.max().get()) > 0) {
            return ValidationResult.fail("value " + value + " above max " + rules.max().get().toPlainString());
        }
        if (rules.min().isPresent() && n.compareTo(rules.min().get()) < 0) {
            return ValidationResult.fail("value " + value + " below min " + rules.min().get().toPlainString());
        }
        return ValidationResult.success();
    }

    private static Set<String> codes(FieldDef def) {
        Set<String> s = new HashSet<>();
        if (def.options() != null) {
            for (OptionDef o : def.options()) {
                s.add(o.code());
            }
        }
        return s;
    }

    private static ValidationResult notOneOf(FieldDef def, String v) {
        List<String> codes = def.options() == null ? List.of() : def.options().stream().map(OptionDef::code).toList();
        return ValidationResult.fail("value " + v + " is not one of " + codes);
    }
}
