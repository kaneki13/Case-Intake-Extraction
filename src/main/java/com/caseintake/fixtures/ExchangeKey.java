package com.caseintake.fixtures;

import com.caseintake.output.DocumentId;

/**
 * Identifies one model exchange; both parts become path segments, so they are validated against the shared
 * document-id pattern.
 */
public record ExchangeKey(String documentId, String stage) {
    public ExchangeKey {
        check("documentId", documentId);
        check("stage", stage);
    }

    private static void check(String name, String v) {
        if (!DocumentId.isValid(v)) {
            throw new IllegalArgumentException(name + " must match " + DocumentId.PATTERN.pattern() + ": " + v);
        }
    }
}
