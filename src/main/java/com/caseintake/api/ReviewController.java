package com.caseintake.api;

import com.caseintake.review.BulkReviewRequest;
import com.caseintake.review.MergedField;
import com.caseintake.review.ReviewRequest;
import com.caseintake.review.ReviewService;
import java.util.List;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reviewer decisions; stored under app.reviews, never in out/. */
@RestController
@RequestMapping("/api/documents/{id}/cases/{caseIndex}/fields")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PatchMapping("/{fieldId}")
    public MergedField review(@PathVariable String id, @PathVariable int caseIndex, @PathVariable String fieldId,
                              @RequestBody ReviewRequest request) {
        return service.apply(id, caseIndex, fieldId, request);
    }

    @PatchMapping
    public List<MergedField> reviewUnreviewed(@PathVariable String id, @PathVariable int caseIndex,
                                              @RequestBody BulkReviewRequest request) {
        return service.applyBulk(id, caseIndex, request);
    }
}
