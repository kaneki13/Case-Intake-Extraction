package com.caseintake.duplicates;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.output.CaseOutput;
import com.caseintake.output.ExtractionOutput;
import com.caseintake.output.FieldResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds documents that describe the same case. Deterministic: documents are visited in id order and cases in index
 * order, so the same outputs always give the same report.
 */
public final class DuplicateDetector {
    static final String REASON_PREFIX = "Same patient and event: ";
    static final String REASON_SEPARATOR = "; ";
    private static final int MAX_REASON_VALUES = 6;

    private DuplicateDetector() {}

    public static DuplicateReport detect(List<ExtractionOutput> outputs, Catalog catalog, DuplicateProperties props) {
        props.validate(catalog);
        ValueComparator comparator = new ValueComparator(props.integerTolerance());
        List<ExtractionOutput> docs = outputs.stream().sorted(Comparator.comparing(ExtractionOutput::documentId))
                .toList();
        List<CasePair> pairs = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            for (int j = i + 1; j < docs.size(); j++) {
                if (docs.get(i).documentId().equals(docs.get(j).documentId())) {
                    continue;
                }
                for (CaseOutput a : sortedCases(docs.get(i))) {
                    for (CaseOutput b : sortedCases(docs.get(j))) {
                        CasePair pair = compare(docs.get(i).documentId(), a, docs.get(j).documentId(), b, catalog,
                                props, comparator);
                        if (pair != null) {
                            pairs.add(pair);
                        }
                    }
                }
            }
        }
        return new DuplicateReport(group(pairs, catalog), List.copyOf(pairs));
    }

    private static List<CaseOutput> sortedCases(ExtractionOutput o) {
        return o.cases().stream().sorted(Comparator.comparingInt(CaseOutput::caseIndex)).toList();
    }

    private static CasePair compare(String docA, CaseOutput a, String docB, CaseOutput b, Catalog catalog,
                                    DuplicateProperties props, ValueComparator comparator) {
        int agreements = 0;
        int differences = 0;
        Set<String> matchedSections = new LinkedHashSet<>();
        List<String> values = new ArrayList<>();
        for (FieldDef field : catalog.fields()) {
            FieldResult ra = a.fields().get(field.id());
            FieldResult rb = b.fields().get(field.id());
            Comparison c = comparator.compare(field, ra, rb);
            if (c == Comparison.DIFFER && field.sectionId().equals(props.identitySection())) {
                return null;
            }
            if (c == Comparison.DIFFER) {
                differences++;
            }
            if (c == Comparison.MATCH) {
                agreements++;
                matchedSections.add(field.sectionId());
                String text = ValueComparator.render(field, ra.value());
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
        }
        if (differences > props.maxDifferences() || agreements < props.minAgreements()
                || !matchedSections.containsAll(props.requiredSections())) {
            return null;
        }
        String confidence = agreements >= props.highAgreements() ? "high" : "medium";
        String reason = REASON_PREFIX + String.join(REASON_SEPARATOR, values.subList(0, Math.min(values.size(), MAX_REASON_VALUES)));
        return new CasePair(docA, a.caseIndex(), docB, b.caseIndex(), agreements, confidence, reason);
    }

    private static List<DuplicateGroup> group(List<CasePair> pairs, Catalog catalog) {
        Map<String, String> parent = new HashMap<>();
        for (CasePair p : pairs) {
            parent.putIfAbsent(p.docA(), p.docA());
            parent.putIfAbsent(p.docB(), p.docB());
            parent.put(find(parent, p.docA()), find(parent, p.docB()));
        }
        Map<String, List<CasePair>> byRoot = new LinkedHashMap<>();
        for (CasePair p : pairs) {
            byRoot.computeIfAbsent(find(parent, p.docA()), k -> new ArrayList<>()).add(p);
        }
        // sorted by the group's first (smallest) document id
        TreeMap<String, DuplicateGroup> groups = new TreeMap<>();
        for (List<CasePair> members : byRoot.values()) {
            Set<String> ids = new java.util.TreeSet<>();
            Set<String> docPairs = new LinkedHashSet<>();
            CasePair strongest = members.get(0);
            String confidence = "high";
            for (CasePair p : members) {
                ids.add(p.docA());
                ids.add(p.docB());
                docPairs.add(p.docA() + "\n" + p.docB());
                if (p.agreements() > strongest.agreements()) {
                    strongest = p;
                }
                if (p.confidence().equals("medium")) {
                    confidence = "medium";
                }
            }
            String reason = strongest.reason();
            if (docPairs.size() > 1) {
                reason += " (+" + (docPairs.size() - 1) + " more document pairs)";
            }
            List<String> sorted = List.copyOf(ids);
            groups.put(sorted.get(0), new DuplicateGroup(sorted, confidence, reason));
        }
        return List.copyOf(groups.values());
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }
}
