package com.caseintake.extraction;

/**
 * Document-wide hints from case finding. The convention counts only when its evidence quote was located.
 * {@code sourceTranscribed} is true when the text is a model transcript of an image-only document.
 */
public record DocumentHints(DateConvention dateConvention, boolean evidenceLocated, boolean sourceTranscribed) {
    /** These hints for a document whose source text is a transcript. */
    public DocumentHints transcribed() {
        return new DocumentHints(dateConvention, evidenceLocated, true);
    }
}
