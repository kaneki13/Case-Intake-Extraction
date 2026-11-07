package com.caseintake.pipeline;

/** A document could not be processed; no output is written for it. */
public class DocumentFailedException extends RuntimeException {
    public DocumentFailedException(String message) {
        super(message);
    }
}
