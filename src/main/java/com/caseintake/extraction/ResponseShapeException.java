package com.caseintake.extraction;

/**
 * A model response (case finding or extraction) was not JSON or did not match the expected shape. The message is a
 * short machine-generated description and never contains document text: it is sent back to the model as a repair
 * hint.
 */
public class ResponseShapeException extends RuntimeException {
    public ResponseShapeException(String detail) {
        super(detail);
    }
}
