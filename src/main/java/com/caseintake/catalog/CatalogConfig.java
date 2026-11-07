package com.caseintake.catalog;

import com.caseintake.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogConfig {
    @Bean
    public Catalog catalog(AppProperties props) {
        return CatalogLoader.load(props.catalog());
    }
}
