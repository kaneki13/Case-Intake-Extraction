package com.caseintake.fixtures;

import com.caseintake.llm.LlmRequest;
import java.util.function.Supplier;

/** Replay: reads only fixtures; no network, no key, never touches an LlmClient. */
public class FixtureExchangeSource implements ExchangeSource {
    private final FixtureStore store;

    public FixtureExchangeSource(FixtureStore store) {
        this.store = store;
    }

    @Override
    public Exchange obtain(ExchangeKey key, Supplier<LlmRequest> request) {
        return store.latest(key).map(r -> r.toExchange(key)).orElseThrow(() -> new FixtureMissingException(key));
    }

    @Override
    public Exchange retry(ExchangeKey key, Supplier<LlmRequest> request) {
        throw new FixtureMissingException(key);
    }
}
