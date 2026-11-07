package com.caseintake.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** A reviewer's decision on one field. Serialised in lower case. */
public enum ReviewState {
    ACCEPTED, REJECTED, EDITED;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ReviewState fromJson(String s) {
        for (ReviewState state : values()) {
            if (state.json().equals(s)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown review state '" + s + "'");
    }
}
