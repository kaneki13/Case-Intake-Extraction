package com.caseintake.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** One model request. {@code attachments} (copied, never null) go ahead of the user text; empty means text only. */
public record LlmRequest(String model, String systemInstruction, String userContent, JsonNode responseJsonSchema,
        List<LlmAttachment> attachments) {
    public LlmRequest {
        attachments = List.copyOf(attachments);
    }
}
