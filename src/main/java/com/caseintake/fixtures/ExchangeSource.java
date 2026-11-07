package com.caseintake.fixtures;

import com.caseintake.llm.LlmRequest;
import java.util.function.Supplier;

/** Source of model exchanges. The request supplier is invoked only when a live call is made. */
public interface ExchangeSource {
    /** Latest recorded attempt (a new live attempt only if none exists, or when forced). */
    Exchange obtain(ExchangeKey key, Supplier<LlmRequest> request);

    /** Always a new attempt n+1 (live). Replay has no further recordings and throws. */
    Exchange retry(ExchangeKey key, Supplier<LlmRequest> request);
}
