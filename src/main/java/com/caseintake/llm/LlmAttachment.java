package com.caseintake.llm;

import java.util.Arrays;
import java.util.Objects;

/** Binary content (e.g. a PDF) sent inline with an LLM request. The byte array is defensively copied. */
public record LlmAttachment(String mimeType, byte[] data) {
    public LlmAttachment {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        Objects.requireNonNull(data, "data");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LlmAttachment other
                && mimeType.equals(other.mimeType) && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * mimeType.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "LlmAttachment[mimeType=" + mimeType + ", bytes=" + data.length + "]";
    }
}
