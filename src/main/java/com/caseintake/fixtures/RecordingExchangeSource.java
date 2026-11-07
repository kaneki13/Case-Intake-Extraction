package com.caseintake.fixtures;

import com.caseintake.llm.LlmClient;
import com.caseintake.llm.LlmCredential;
import com.caseintake.llm.LlmRequest;
import com.caseintake.llm.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Extract mode: records every exchange (key removed) and then returns the copy read back from disk.
 * Only successful responses are recorded; non-2xx responses raised by the client are not. Repair
 * attempts are recorded through {@link #retry}.
 */
public class RecordingExchangeSource implements ExchangeSource {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LlmClient client;
    private final FixtureStore store;
    private final LlmCredential credential;
    private final String catalogVersion;
    private final boolean force;
    private final Clock clock;

    /** Records with the client's own credential, which must be set: it is what the recordings are cleaned of. */
    public RecordingExchangeSource(LlmClient client, FixtureStore store, String catalogVersion, boolean force,
                                   Clock clock) {
        this.credential = client.credential();
        if (!credential.isPresent()) {
            throw new IllegalArgumentException("Recording requires a non-blank API key (" + credential.settingName()
                    + ")");
        }
        this.client = client;
        this.store = store;
        this.catalogVersion = catalogVersion;
        this.force = force;
        this.clock = clock;
    }

    @Override
    public Exchange obtain(ExchangeKey key, Supplier<LlmRequest> request) {
        if (!force) {
            var existing = store.latest(key);
            if (existing.isPresent()) {
                return existing.get().toExchange(key);
            }
        }
        return record(key, request);
    }

    @Override
    public Exchange retry(ExchangeKey key, Supplier<LlmRequest> request) {
        return record(key, request);
    }

    private Exchange record(ExchangeKey key, Supplier<LlmRequest> supplier) {
        LlmRequest request = supplier.get();
        LlmResponse response = client.generate(request);
        int attempt = store.nextAttempt(key);
        FixtureRecord record = new FixtureRecord(key.documentId(), key.stage(), attempt, client.providerId(),
                response.model(), catalogVersion, sha256(request.systemInstruction() + "\n" + request.userContent()),
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString(),
                Redactor.redact(response.outputText(), credential),
                new FixtureRecord.Request(Redactor.redact(response.requestUrl(), credential),
                        body(response.rawRequestBody())),
                new FixtureRecord.Response(response.httpStatus(), body(response.rawResponseBody())));
        store.write(key, attempt, record, credential.secret());
        return store.read(key, attempt).toExchange(key);
    }

    private JsonNode body(String raw) {
        String redacted = Redactor.redact(raw, credential);
        if (redacted == null) {
            return TextNode.valueOf("");
        }
        try {
            JsonNode n = MAPPER.readTree(redacted);
            return n == null ? TextNode.valueOf(redacted) : n;
        } catch (Exception e) {
            return TextNode.valueOf(redacted);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
