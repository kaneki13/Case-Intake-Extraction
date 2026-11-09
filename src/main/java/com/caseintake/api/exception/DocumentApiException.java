package com.caseintake.api.exception;

import org.springframework.http.HttpStatus;

/** A failure with the HTTP status it maps to; the message is safe to show to clients. */
public class DocumentApiException extends RuntimeException {
    private final HttpStatus status;

    public DocumentApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public DocumentApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
