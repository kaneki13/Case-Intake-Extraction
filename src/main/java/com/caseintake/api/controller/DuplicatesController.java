package com.caseintake.api.controller;

import com.caseintake.api.dto.DuplicateGroupView;
import com.caseintake.api.dto.DuplicatesView;
import com.caseintake.api.exception.DocumentApiException;
import com.caseintake.api.service.ExtractionReader;

import com.caseintake.catalog.Catalog;
import com.caseintake.duplicates.CasePair;
import com.caseintake.duplicates.DuplicateDetector;
import com.caseintake.duplicates.DuplicateGroup;
import com.caseintake.duplicates.DuplicateProperties;
import com.caseintake.duplicates.DuplicateReport;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.OutputWriter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duplicate groups, recomputed with the detector over every out/ file on each request; nothing is cached. The
 * response also carries the integer tolerance, so the UI compares values with the same rule as the detector.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/duplicates")
public class DuplicatesController {
    private final ExtractionReader reader;
    private final Catalog catalog;
    private final DuplicateProperties props;

    @GetMapping
    public DuplicatesView list() {
        List<ExtractionOutput> outputs = new ArrayList<>();
        for (String id : reader.ids()) {
            JsonNode tree = reader.read(id);
            try {
                outputs.add(OutputWriter.fromTree(tree));
            } catch (IllegalArgumentException e) {
                throw ExtractionReader.unreadable(id, "does not match the extraction output shape", e);
            }
        }
        DuplicateReport report;
        try {
            report = DuplicateDetector.detect(outputs, catalog, props);
        } catch (IllegalArgumentException e) {
            throw new DocumentApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Duplicate detection is misconfigured: " + e.getMessage(), e);
        }
        return new DuplicatesView(props.integerTolerance(),
                report.groups().stream().map(g -> view(g, report.pairs())).toList());
    }

    private static DuplicateGroupView view(DuplicateGroup group, List<CasePair> pairs) {
        List<DuplicateGroupView.PairView> inGroup = pairs.stream()
                .filter(p -> group.documentIds().contains(p.docA()))
                .map(p -> new DuplicateGroupView.PairView(
                        new DuplicateGroupView.CaseRef(p.docA(), p.caseA()),
                        new DuplicateGroupView.CaseRef(p.docB(), p.caseB()), p.agreements()))
                .toList();
        return new DuplicateGroupView(group.documentIds(), group.confidence(), group.reason(), inGroup);
    }
}
