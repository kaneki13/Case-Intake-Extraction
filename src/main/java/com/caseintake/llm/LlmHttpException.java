package com.caseintake.llm;

/** Non-2xx reply. Carries status and raw body so the retry policy can decide. */
public class LlmHttpException extends LlmException {
    private final int httpStatus;
    private final String responseBody;

    public LlmHttpException(int httpStatus, String responseBody) {
        super("HTTP " + httpStatus + ": " + responseBody);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String responseBody() {
        return responseBody;
    }
}
