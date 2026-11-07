package com.caseintake.catalog;

import java.util.Locale;

public enum FieldType {
    STRING, INTEGER, NUMBER, DATE, CODED, CODED_MULTI, STRING_LIST;

    /** The catalog spelling: the lower-case name, e.g. "coded_multi". */
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the exact catalog spelling, e.g. "coded_multi" gives CODED_MULTI; any other case is rejected. */
    public static FieldType fromJson(String s) {
        for (FieldType t : values()) {
            if (t.json().equals(s)) {
                return t;
            }
        }
        throw new CatalogException("unknown field type '" + s + "'");
    }
}
