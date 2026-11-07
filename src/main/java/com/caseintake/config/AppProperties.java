package com.caseintake.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Path catalog, List<Path> documents, Path fixtures, Path out) {}
