package com.caseintake.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
/**
 * {@code derived} holds the transcripts of image-only documents, written by extract and rebuilt by replay.
 * {@code reviews} holds the reviewers' decisions ({@code <ID>.json}); it is git-ignored and never touches out/.
 */
public record AppProperties(Path catalog, List<Path> documents, Path fixtures, Path out, Path derived, Path reviews) {}
