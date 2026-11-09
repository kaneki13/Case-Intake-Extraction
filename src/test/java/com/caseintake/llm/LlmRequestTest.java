package com.caseintake.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmRequestTest {
    @Test
    void attachmentsAreCopiedAndImmutable() {
        List<LlmAttachment> source = new ArrayList<>(List.of(new LlmAttachment("application/pdf", new byte[] {1})));
        LlmRequest request = new LlmRequest("m", "s", "u", null, source);

        source.clear();

        assertThat(request.attachments()).hasSize(1);
        assertThatThrownBy(() -> request.attachments().add(new LlmAttachment("image/png", new byte[0])))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullAttachmentsAreRejected() {
        assertThatThrownBy(() -> new LlmRequest("m", "s", "u", null, null)).isInstanceOf(NullPointerException.class);
    }
}
