package com.caseintake.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String provider, Models models, RateLimit rateLimit, Retry retry, Gemini gemini) {

    public record Models(String caseFinding, String extraction, String transcription) {}

    public record RateLimit(int requestsPerMinute) {}

    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {}

    public record Gemini(String baseUrl, String apiKey, Duration timeout) {
        /** Never prints the key. */
        @Override
        public String toString() {
            String key = (apiKey == null || apiKey.isBlank()) ? "<empty>" : "***";
            return "Gemini[baseUrl=" + baseUrl + ", apiKey=" + key + ", timeout=" + timeout + "]";
        }
    }
}
