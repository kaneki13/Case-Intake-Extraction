package com.caseintake.fixtures;

public class FixtureMissingException extends RuntimeException {
    private final ExchangeKey key;

    public FixtureMissingException(ExchangeKey key) {
        super("No fixture recorded for " + key.documentId() + "/" + key.stage());
        this.key = key;
    }

    public ExchangeKey key() {
        return key;
    }
}
