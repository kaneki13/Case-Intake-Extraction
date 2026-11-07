package com.caseintake.extraction;

/** The four elements a report needs before it is a case (glossary, "Case"). */
public enum MinimumElement {
    PATIENT("patient_quote"),
    REPORTER("reporter_quote"),
    SUSPECT_DRUG("suspect_drug_quote"),
    ADVERSE_EVENT("adverse_event_quote");

    private final String quoteKey;

    MinimumElement(String quoteKey) {
        this.quoteKey = quoteKey;
    }

    /** The stage-1 response property that carries this element's quote. */
    public String quoteKey() {
        return quoteKey;
    }
}
