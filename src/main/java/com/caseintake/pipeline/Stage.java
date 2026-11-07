package com.caseintake.pipeline;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two model stages: their {@code --stage} value on the command line and the key their exchanges are recorded
 * under in {@code fixtures/<id>/} (LLD 1.1, 10). Case finding runs once per document; extraction once per case,
 * keyed by the case index.
 */
enum Stage {
    CASES("cases", "cases", false),
    EXTRACT("extract", "extract-case-", true);

    private final String cliValue;
    private final String keyPrefix;
    private final boolean perCase;

    Stage(String cliValue, String keyPrefix, boolean perCase) {
        this.cliValue = cliValue;
        this.keyPrefix = keyPrefix;
        this.perCase = perCase;
    }

    String cliValue() {
        return cliValue;
    }

    /** The stage named by an exact {@code --stage} value. */
    static Optional<Stage> fromCli(String value) {
        return Arrays.stream(values()).filter(s -> s.cliValue.equals(value)).findFirst();
    }

    /** The fixture key of a once-per-document stage. */
    String fixtureKey() {
        if (perCase) {
            throw new IllegalStateException(this + " is recorded per case");
        }
        return keyPrefix;
    }

    /** The fixture key of a per-case stage for the given case index. */
    String fixtureKey(int caseIndex) {
        if (!perCase) {
            throw new IllegalStateException(this + " is recorded once per document");
        }
        return keyPrefix + caseIndex;
    }

    /** True when the fixture key was recorded by this stage. */
    boolean owns(String fixtureKey) {
        if (!perCase) {
            return fixtureKey.equals(keyPrefix);
        }
        return fixtureKey.startsWith(keyPrefix) && fixtureKey.length() > keyPrefix.length()
                && fixtureKey.substring(keyPrefix.length()).chars().allMatch(Character::isDigit);
    }
}
