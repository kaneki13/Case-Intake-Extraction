package com.caseintake.api.controller;

import com.caseintake.catalog.Catalog;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/catalog")
public class CatalogController {
    private final Catalog catalog;

    @GetMapping
    public JsonNode catalog() {
        return catalog.raw();
    }
}
