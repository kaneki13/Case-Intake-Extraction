package com.caseintake.llm.gemini;

import com.caseintake.llm.LlmClient;
import com.caseintake.llm.LlmCredential;
import com.caseintake.llm.LlmException;
import com.caseintake.llm.LlmHttpException;
import com.caseintake.llm.LlmProperties;
import com.caseintake.llm.LlmRequest;
import com.caseintake.llm.RateLimiter;
import com.caseintake.llm.RetryPolicy;
import com.caseintake.llm.LlmResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Gemini {@code generateContent} adapter. Each attempt acquires a rate-limiter slot and makes one HTTP
 * call; {@link RetryPolicy} wraps the attempts.
 * The API key is sent only in the {@code x-goog-api-key} header and is never logged.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {
    static final String API_KEY_SETTING = "GEMINI_API_KEY";
    static final String API_KEY_HEADER = "x-goog-api-key";

    private final LlmProperties.Gemini config;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final RetryPolicy retryPolicy;

    @Autowired
    public GeminiLlmClient(LlmProperties props, RestClient.Builder builder, ObjectMapper mapper,
            RateLimiter rateLimiter, RetryPolicy retryPolicy) {
        this(props, builder.requestFactory(requestFactory(props.gemini())).build(), mapper, rateLimiter, retryPolicy);
    }

    /** Test seam: a pre-built RestClient (e.g. bound to MockRestServiceServer). */
    GeminiLlmClient(LlmProperties props, RestClient restClient, ObjectMapper mapper,
            RateLimiter rateLimiter, RetryPolicy retryPolicy) {
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
        this.config = props.gemini();
        this.mapper = mapper;
        this.restClient = restClient;
    }

    private static JdkClientHttpRequestFactory requestFactory(LlmProperties.Gemini cfg) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(cfg.timeout()).build());
        factory.setReadTimeout(cfg.timeout());
        return factory;
    }

    @Override
    public String providerId() {
        return "gemini";
    }

    @Override
    public LlmCredential credential() {
        return new LlmCredential(config.apiKey(), API_KEY_SETTING, Set.of(API_KEY_HEADER));
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        LlmCredential credential = credential();
        if (!credential.isPresent()) {
            throw new LlmException(API_KEY_SETTING + " is not set");
        }
        String key = credential.secret();
        String url = config.baseUrl() + "/v1beta/models/" + request.model() + ":generateContent";
        String requestBody = buildBody(request);

        return retryPolicy.execute(() -> {
            rateLimiter.acquire(request.model());
            return attempt(request, key, url, requestBody);
        });
    }

    private LlmResponse attempt(LlmRequest request, String key, String url, String requestBody) {
        var spec = restClient.post().uri(url)
                .header(API_KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .exchange((req, res) -> new Raw(res.getStatusCode().value(),
                        new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        if (spec.status() / 100 != 2) {
            throw new LlmHttpException(spec.status(), spec.body());
        }
        return new LlmResponse(request.model(), spec.status(), requestBody, spec.body(), url,
                extractText(spec.body()));
    }

    private record Raw(int status, String body) {}

    private String buildBody(LlmRequest r) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", r.systemInstruction());
        ObjectNode content = root.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", r.userContent());
        ObjectNode gen = root.putObject("generationConfig");
        gen.put("temperature", 0);
        gen.put("responseMimeType", "application/json");
        gen.set("responseSchema", GeminiSchemaTranslator.translate(r.responseJsonSchema()));
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmException("cannot serialise request", e);
        }
    }

    private String extractText(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new LlmException("unparseable response body", e);
        }
        JsonNode candidate = root == null ? null : root.path("candidates").path(0);
        if (candidate == null || candidate.isMissingNode()) {
            throw new LlmException("finishReason=<no candidates>");
        }
        String finish = candidate.path("finishReason").asText("<missing>");
        if (!"STOP".equals(finish)) {
            throw new LlmException("finishReason=" + finish);
        }
        JsonNode text = candidate.path("content").path("parts").path(0).path("text");
        if (!text.isTextual()) {
            throw new LlmException("finishReason=STOP but response has no text part");
        }
        return text.asText();
    }
}
