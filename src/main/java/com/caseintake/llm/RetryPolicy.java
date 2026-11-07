package com.caseintake.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;

/** Retries 429/500/503 and I/O timeouts with exponential backoff, jitter and server-directed delay. */
public class RetryPolicy {
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 503);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmProperties.Retry config;
    private final Sleeper sleeper;
    private final Random random;

    public RetryPolicy(LlmProperties.Retry config, Sleeper sleeper, Random random) {
        if (config.maxAttempts() < 1) {
            throw new IllegalArgumentException("max-attempts must be >= 1, got " + config.maxAttempts());
        }
        requireNonNegative("initial-backoff", config.initialBackoff());
        requireNonNegative("max-backoff", config.maxBackoff());
        this.config = config;
        this.sleeper = sleeper;
        this.random = random;
    }

    private static void requireNonNegative(String name, Duration d) {
        if (d == null || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + d);
        }
    }

    public LlmResponse execute(Supplier<LlmResponse> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                return call.get();
            } catch (LlmHttpException e) {
                if (e.httpStatus() == 429) {
                    String quota = perDayQuotaId(e.responseBody());
                    if (quota != null) {
                        throw new DailyQuotaExhaustedException("daily quota exhausted (" + quota + ")");
                    }
                }
                if (!RETRYABLE.contains(e.httpStatus())) {
                    throw e;
                }
                last = e;
                if (attempt < config.maxAttempts()) {
                    sleeper.sleep(delay(attempt, serverRetryDelay(e.responseBody())));
                }
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException e) {
                if (!isTimeout(e)) {
                    throw e;
                }
                last = e;
                if (attempt < config.maxAttempts()) {
                    sleeper.sleep(delay(attempt, Duration.ZERO));
                }
            }
        }
        throw new LlmException("gave up after " + config.maxAttempts() + " attempts: " + last.getMessage(), last);
    }

    private Duration delay(int attempt, Duration serverDelay) {
        long initial = config.initialBackoff().toMillis();
        long exp = initial * (1L << Math.min(attempt - 1, 30));
        long backoff = Math.min(config.maxBackoff().toMillis(), exp);
        long jitter = initial > 0 ? (long) (random.nextDouble() * initial) : 0;
        return Duration.ofMillis(Math.max(serverDelay.toMillis(), backoff + jitter));
    }

    private static boolean isTimeout(Throwable t) {
        if (t instanceof ResourceAccessException) {
            return true;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode details(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            return root == null ? null : root.path("error").path("details");
        } catch (Exception e) {
            return null;
        }
    }

    private static Duration serverRetryDelay(String body) {
        JsonNode details = details(body);
        if (details == null || !details.isArray()) {
            return Duration.ZERO;
        }
        for (JsonNode d : details) {
            String s = d.path("retryDelay").asText("");
            if (s.endsWith("s")) {
                try {
                    return Duration.ofMillis(Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1000));
                } catch (NumberFormatException ignored) {
                    // unparseable delay: fall back to normal backoff
                }
            }
        }
        return Duration.ZERO;
    }

    private static String perDayQuotaId(String body) {
        JsonNode details = details(body);
        if (details == null || !details.isArray()) {
            return null;
        }
        for (JsonNode d : details) {
            for (JsonNode v : d.path("violations")) {
                String id = v.path("quotaId").asText("");
                if (id.contains("PerDay")) {
                    return id;
                }
            }
        }
        return null;
    }
}
