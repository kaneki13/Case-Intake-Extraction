package com.caseintake.duplicates;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.SectionDef;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Duplicate-detection settings (yml {@code duplicates}). Section ids come from configuration, never from code.
 * {@code identitySection}: any differing field there vetoes a pair. {@code requiredSections}: each needs one match.
 * {@code maxDifferences}: a pair with more differing fields than this, over the whole catalog, is not a duplicate.
 */
@ConfigurationProperties(prefix = "duplicates")
public record DuplicateProperties(String identitySection, List<String> requiredSections, int minAgreements,
                                  int highAgreements, int integerTolerance, int maxDifferences) {

    /** Throws IllegalArgumentException (a configuration error, exit 2) when the settings do not fit the catalog. */
    public void validate(Catalog catalog) {
        if (identitySection == null || identitySection.isBlank()) {
            throw new IllegalArgumentException("duplicates.identity-section is not set");
        }
        if (requiredSections == null || requiredSections.isEmpty()) {
            throw new IllegalArgumentException("duplicates.required-sections is not set");
        }
        List<String> known = catalog.sections().stream().map(SectionDef::id).toList();
        if (!known.contains(identitySection)) {
            throw new IllegalArgumentException("duplicates.identity-section '" + identitySection
                    + "' is not a catalog section");
        }
        for (String section : requiredSections) {
            if (!known.contains(section)) {
                throw new IllegalArgumentException("duplicates.required-sections has '" + section
                        + "', which is not a catalog section");
            }
        }
        if (minAgreements < 1) {
            throw new IllegalArgumentException("duplicates.min-agreements must be at least 1, got " + minAgreements);
        }
        if (highAgreements < minAgreements) {
            throw new IllegalArgumentException("duplicates.high-agreements (" + highAgreements
                    + ") must be at least duplicates.min-agreements (" + minAgreements + ")");
        }
        if (integerTolerance < 0) {
            throw new IllegalArgumentException("duplicates.integer-tolerance must not be negative, got "
                    + integerTolerance);
        }
        if (maxDifferences < 0) {
            throw new IllegalArgumentException("duplicates.max-differences must not be negative, got "
                    + maxDifferences);
        }
    }
}
