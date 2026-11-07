package com.caseintake.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
/** {@code derived} holds the transcripts of image-only documents, written by extract and rebuilt by replay. */
public record AppProperties(Path catalog, List<Path> documents, Path fixtures, Path out, Path derived) {}
