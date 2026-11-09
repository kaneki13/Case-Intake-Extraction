package com.caseintake.support;

import com.caseintake.config.AppProperties;
import com.caseintake.duplicates.DuplicateProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * The settings of the real src/main/resources/application.yml, bound with Spring's Binder exactly as the application
 * binds them, for tests that build commands by hand. Nothing is copied, so tests and replay.sh cannot drift apart.
 * Only plain keys are bound; placeholders (such as the API key) and config imports are never resolved.
 */
public final class DuplicateFixtures {
    public static final String APPLICATION_YML = "src/main/resources/application.yml";
    private static final Binder BINDER = binder();

    private DuplicateFixtures() {}

    /** The {@code duplicates} settings of application.yml. */
    public static DuplicateProperties standard() {
        return BINDER.bind("duplicates", DuplicateProperties.class)
                .orElseThrow(() -> new IllegalStateException("no duplicates settings in " + APPLICATION_YML));
    }

    /** The {@code app} settings of application.yml (paths relative to the repository root). */
    public static AppProperties app() {
        return BINDER.bind("app", AppProperties.class)
                .orElseThrow(() -> new IllegalStateException("no app settings in " + APPLICATION_YML));
    }

    private static Binder binder() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new FileSystemResource(APPLICATION_YML));
            return new Binder(ConfigurationPropertySources.from(sources));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + APPLICATION_YML, e);
        }
    }
}
