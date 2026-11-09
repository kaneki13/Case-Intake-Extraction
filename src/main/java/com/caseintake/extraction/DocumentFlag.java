package com.caseintake.extraction;

import java.util.Locale;

/** Document-level flags reported in the output. */
public enum DocumentFlag {
    NO_ADVERSE_EVENT,
    MULTIPLE_PATIENTS,
    UNSUPPORTED_FORMAT,
    MISSING_MINIMUM_ELEMENTS,
    EMBEDDED_INSTRUCTIONS_IGNORED,
    POSSIBLE_DUPLICATE,
    TRANSCRIBED_FROM_IMAGE;

    /** The lower snake_case name used in the output JSON. */
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
