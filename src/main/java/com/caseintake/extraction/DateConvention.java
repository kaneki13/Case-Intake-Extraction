package com.caseintake.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How a document writes numeric dates. JSON values: day_first, month_first, unknown. */
public enum DateConvention {
    DAY_FIRST, MONTH_FIRST, UNKNOWN;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static DateConvention fromJson(String s) {
        for (DateConvention c : values()) {
            if (c.json().equals(s)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown date convention '" + s + "'");
    }
}
