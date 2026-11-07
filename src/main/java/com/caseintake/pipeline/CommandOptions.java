package com.caseintake.pipeline;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;

/** Reads the command options of LLD 1.1 ({@code --only}, {@code --stage}, {@code --out}). */
final class CommandOptions {
    private CommandOptions() {}

    /** {@code --only=<id>,<id>} (may be repeated); empty means every document. */
    static Set<String> only(ApplicationArguments args) {
        Set<String> ids = new LinkedHashSet<>();
        List<String> values = args.getOptionValues("only");
        if (values != null) {
            values.forEach(v -> Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(ids::add));
        }
        return ids;
    }

    /**
     * The last value of {@code --name=<value>}, or empty when the option is absent. Throws IllegalArgumentException
     * when the option is given without a value or with a blank one, so a typo never falls back to a default.
     */
    static Optional<String> single(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return Optional.empty();
        }
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(values.size() - 1).isBlank()) {
            throw new IllegalArgumentException("--" + name + " needs a value");
        }
        return Optional.of(values.get(values.size() - 1));
    }
}
