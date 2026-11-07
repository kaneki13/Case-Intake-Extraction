package com.caseintake.llm;

import java.time.Clock;
import java.util.Random;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for rate limiting and retry; Clock, Sleeper and Random are beans so tests can replace them. */
@Configuration
public class LlmConfig {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    Sleeper sleeper() {
        return Sleeper.system();
    }

    @Bean
    @ConditionalOnMissingBean
    Random random() {
        return new Random();
    }

    @Bean
    RateLimiter rateLimiter(LlmProperties props, Clock clock, Sleeper sleeper) {
        return new RateLimiter(props.rateLimit().requestsPerMinute(), clock, sleeper);
    }

    @Bean
    RetryPolicy retryPolicy(LlmProperties props, Sleeper sleeper, Random random) {
        return new RetryPolicy(props.retry(), sleeper, random);
    }
}
