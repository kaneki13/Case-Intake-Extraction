package com.caseintake.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.caseintake.llm.LlmAttachment;
import com.caseintake.llm.LlmException;
import com.caseintake.llm.LlmHttpException;
import com.caseintake.llm.LlmCredential;
import com.caseintake.llm.LlmProperties;
import com.caseintake.llm.LlmRequest;
import com.caseintake.llm.LlmResponse;
import com.caseintake.llm.RateLimiter;
import com.caseintake.llm.RetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiLlmClientTest {
    private static final String BASE = "https://gemini.test";
    private static final String URL = BASE + "/v1beta/models/m1:generateContent";
    private final ObjectMapper om = new ObjectMapper();

    private record Fixture(GeminiLlmClient client, MockRestServiceServer server, List<Duration> sleeps) {}

    private Fixture fixture(String key) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        var props = new LlmProperties("gemini", null, null, null,
                new LlmProperties.Gemini(BASE, key, Duration.ofSeconds(5)));
        List<Duration> sleeps = new ArrayList<>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var retry = new LlmProperties.Retry(3, Duration.ofSeconds(2), Duration.ofSeconds(10));
        var client = new GeminiLlmClient(props, b.build(), om, new RateLimiter(100, clock, sleeps::add),
                new RetryPolicy(retry, sleeps::add, new Random(1)));
        return new Fixture(client, server, sleeps);
    }

    private LlmRequest req() throws Exception {
        return new LlmRequest("m1", "sys", "user text", om.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":[\"string\",\"null\"]}}}"), List.of());
    }

    private static String ok(String finish) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"a\\\":null}\"}]},\"finishReason\":\"" + finish + "\"}]}";
    }

    @Test
    void credentialNamesTheKeySettingAndTheHeaderThatCarriesIt() {
        LlmCredential c = fixture("test-key").client.credential();
        assertThat(c.secret()).isEqualTo("test-key");
        assertThat(c.settingName()).isEqualTo("GEMINI_API_KEY");
        assertThat(c.headerNames()).containsExactly("x-goog-api-key");
        assertThat(fixture(" ").client.credential().isPresent()).isFalse();
    }

    @Test
    void sendsKeyInHeaderNotUrl() throws Exception {
        Fixture f = fixture("test-key");
        f.server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess(ok("STOP"), MediaType.APPLICATION_JSON));
        LlmResponse r = f.client.generate(req());
        f.server.verify();
        assertThat(r.requestUrl()).isEqualTo(URL).doesNotContain("key=").doesNotContain("test-key");
        assertThat(r.rawRequestBody()).doesNotContain("test-key");
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.outputText()).isEqualTo("{\"a\":null}");
        assertThat(r.rawResponseBody()).isEqualTo(ok("STOP"));
        assertThat(r.model()).isEqualTo("m1");
        assertThat(f.client.providerId()).isEqualTo("gemini");
    }

    @Test
    void requestHasTemperatureZeroAndJsonMime() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withSuccess(ok("STOP"), MediaType.APPLICATION_JSON));
        LlmResponse r = f.client.generate(req());
        JsonNode body = om.readTree(r.rawRequestBody());
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo("sys");
        assertThat(body.at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo("user text");
        assertThat(body.at("/generationConfig/temperature").asInt(-1)).isZero();
        assertThat(body.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(body.at("/generationConfig/responseSchema/properties/a/nullable").asBoolean()).isTrue();
    }

    @Test
    void attachmentBecomesInlineDataPartBeforeText() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withSuccess(ok("STOP"), MediaType.APPLICATION_JSON));
        byte[] bytes = {0, 1, 2, (byte) 250, (byte) 255, 62, 63};
        LlmRequest base = req();
        LlmRequest r = new LlmRequest(base.model(), base.systemInstruction(), base.userContent(),
                base.responseJsonSchema(), List.of(new LlmAttachment("application/pdf", bytes)));
        JsonNode body = om.readTree(f.client.generate(r).rawRequestBody());
        assertThat(body.at("/contents/0/parts/0/inline_data/mime_type").asText()).isEqualTo("application/pdf");
        assertThat(body.at("/contents/0/parts/0/inline_data/data").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(body.at("/contents/0/parts/1/text").asText()).isEqualTo("user text");
    }

    @Test
    void noAttachmentsKeepsSingleTextPart() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withSuccess(ok("STOP"), MediaType.APPLICATION_JSON));
        JsonNode body = om.readTree(f.client.generate(req()).rawRequestBody());
        assertThat(body.at("/contents/0/parts")).hasSize(1);
        assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo("user text");
    }

    @Test
    void attachmentValidatesCopiesAndHidesBytes() {
        byte[] bytes = {1, 2, 3};
        LlmAttachment a = new LlmAttachment("application/pdf", bytes);
        bytes[0] = 9;
        assertThat(a.data()).containsExactly(1, 2, 3);
        a.data()[1] = 9;
        assertThat(a.data()).containsExactly(1, 2, 3);
        assertThat(a).isEqualTo(new LlmAttachment("application/pdf", new byte[] {1, 2, 3}))
                .hasSameHashCodeAs(new LlmAttachment("application/pdf", new byte[] {1, 2, 3}));
        assertThat(a.toString()).contains("application/pdf").contains("3").doesNotContain("[B");
        assertThatThrownBy(() -> new LlmAttachment(" ", bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmAttachment("x/y", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nonStopFinishReasonThrows() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withSuccess(ok("MAX_TOKENS"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> f.client.generate(req()))
                .isInstanceOf(LlmException.class).hasMessageContaining("finishReason=MAX_TOKENS");
    }

    @Test
    void missingCandidatesThrows() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> f.client.generate(req()))
                .isInstanceOf(LlmException.class).hasMessageContaining("finishReason=");
    }

    @Test
    void missingKeyThrowsOnFirstCall() throws Exception {
        for (String key : new String[] {null, "", "  "}) {
            Fixture f = fixture(key); // construction must not throw
            assertThatThrownBy(() -> f.client.generate(req()))
                    .isInstanceOf(LlmException.class).hasMessage("GEMINI_API_KEY is not set");
        }
    }

    @Test
    void nonSuccessCarriesStatusAndBody() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error\":{}}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> f.client.generate(req()))
                .isInstanceOfSatisfying(LlmHttpException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.responseBody()).isEqualTo("{\"error\":{}}");
                });
    }

    @Test
    void retriesThen503ThenSucceeds() throws Exception {
        Fixture f = fixture("k");
        f.server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("{\"error\":{}}").contentType(MediaType.APPLICATION_JSON));
        f.server.expect(requestTo(URL)).andRespond(withSuccess(ok("STOP"), MediaType.APPLICATION_JSON));
        LlmResponse r = f.client.generate(req());
        f.server.verify();
        assertThat(r.outputText()).isEqualTo("{\"a\":null}");
        assertThat(f.sleeps).hasSize(1);
    }
}
