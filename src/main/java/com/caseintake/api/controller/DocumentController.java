package com.caseintake.api.controller;

import com.caseintake.api.dto.DocumentDetail;
import com.caseintake.api.dto.DocumentSummary;
import com.caseintake.api.service.DocumentService;
import com.caseintake.review.BulkReviewRequest;
import com.caseintake.review.MergedField;
import com.caseintake.review.ReviewRequest;
import com.caseintake.review.ReviewService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;
    private final ReviewService reviewService;

    @GetMapping
    public List<DocumentSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public DocumentDetail get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/cases/{caseIndex}/fields/{fieldId}")
    public MergedField review(@PathVariable String id, @PathVariable int caseIndex, @PathVariable String fieldId,
                              @RequestBody ReviewRequest request) {
        return reviewService.apply(id, caseIndex, fieldId, request);
    }

    @PatchMapping("/{id}/cases/{caseIndex}/fields")
    public List<MergedField> reviewUnreviewed(@PathVariable String id, @PathVariable int caseIndex,
                                              @RequestBody BulkReviewRequest request) {
        return reviewService.applyBulk(id, caseIndex, request);
    }
}
