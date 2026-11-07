package com.caseintake.output;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum FieldStatus {
    EXTRACTED,
    UNKNOWN,
    CONFLICT;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the exact output spelling, e.g. "extracted"; anything else throws IllegalArgumentException. */
    @JsonCreator
    public static FieldStatus fromJson(String s) {
        for (FieldStatus status : values()) {
            if (status.json().equals(s)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown field status '" + s + "'");
    }
}
