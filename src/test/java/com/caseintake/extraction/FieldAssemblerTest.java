package com.caseintake.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.catalog.Catalog;
import com.caseintake.catalog.FieldDef;
import com.caseintake.catalog.FieldType;
import com.caseintake.catalog.OptionDef;
import com.caseintake.catalog.ValidationRules;
import com.caseintake.output.FieldResult;
import com.caseintake.output.FieldStatus;
import com.caseintake.output.Provenance;
import com.caseintake.text.SourceText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FieldAssemblerTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static final FieldDef INITIALS = new FieldDef("initials", "Initials", "p", FieldType.STRING, List.of(),
            new ValidationRules(Optional.of(Pattern.compile("^[A-Z]{1,4}$")), Optional.empty(), Optional.empty()), "");
    private static final FieldDef AGE = new FieldDef("age", "Age", "p", FieldType.INTEGER, List.of(),
            new ValidationRules(Optional.empty(), Optional.of(BigDecimal.ZERO), Optional.of(new BigDecimal("120"))), "");
    private static final FieldDef SEX = new FieldDef("sex", "Sex", "p", FieldType.CODED,
            List.of(new OptionDef("male", "Male"), new OptionDef("female", "Female")), ValidationRules.NONE, "");
    private static final FieldDef DOSE = new FieldDef("dose", "Dose", "d", FieldType.NUMBER, List.of(),
            ValidationRules.NONE, "");
    private static final FieldDef ONSET = new FieldDef("onset", "Onset", "e", FieldType.DATE, List.of(),
            ValidationRules.NONE, "");

    private static final Catalog CATALOG = new Catalog("t", List.of(), List.of(INITIALS, AGE, SEX, DOSE, ONSET),
            List.of("high", "medium", "low"), Map.of(), null);

    private static final SourceText TEXT = SourceText.of("t",
            "Patient  MK, aged 54, female. Dose 250 mcg daily. Onset on 15/05/2026.");
    private static final DocumentHints NO_HINT = new DocumentHints(DateConvention.UNKNOWN, false, false);

    /** JSON written with single quotes for readability. */
    private static JsonNode json(String singleQuoted) {
        try {
            return M.readTree(singleQuoted.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static LinkedHashMap<String, FieldResult> assemble(String fieldsJson) {
        return FieldAssembler.assemble(CATALOG, json(fieldsJson), TEXT, OptionalInt.empty(), NO_HINT);
    }

    private static void assertBare(FieldResult r) {
        assertThat(r.value()).isNull();
        assertThat(r.confidence()).isNull();
        assertThat(r.provenance()).isEmpty();
    }

    // --- LLD §9.2 rows ---

    @Test
    void idAbsentFromResponseIsUnknownWithNote() {
        FieldResult r = assemble("{}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("Not covered by the recorded model response.");
        assertBare(r);
    }

    @Test
    void validationFailureBecomesUnknownWithValidatorError() {
        FieldResult r = assemble("{'initials':{'status':'extracted','value':'M.K.','confidence':'high',"
                + "'quotes':['Patient  MK'],'reason':''}}").get("initials");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("Model value \"M.K.\" rejected: value M.K. does not match pattern ^[A-Z]{1,4}$.");
        assertBare(r);
    }

    @Test
    void outOfRangeIntegerBecomesUnknown() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':154,'confidence':'high',"
                + "'quotes':['aged 54'],'reason':''}}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("Model value 154 rejected: value 154 above max 120.");
    }

    @Test
    void validationRunsBeforeQuoteLocation() {
        FieldResult r = assemble("{'sex':{'status':'extracted','value':'woman','confidence':'high',"
                + "'quotes':['not in the document'],'reason':''}}").get("sex");
        assertThat(r.note()).isEqualTo("Model value \"woman\" rejected: value woman is not one of [male, female].");
    }

    @Test
    void nullValueOnExtractedBecomesUnknown() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':null,'confidence':'high',"
                + "'quotes':['aged 54'],'reason':''}}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("Model value null rejected: value is missing.");
        assertBare(r);
    }

    @Test
    void missingValueOnExtractedBecomesUnknown() {
        FieldResult r = assemble("{'age':{'status':'extracted','confidence':'high','quotes':['aged 54']}}").get("age");
        assertThat(r.note()).isEqualTo("Model value null rejected: value is missing.");
    }

    @Test
    void unlocatedQuoteBecomesUnknown() {
        FieldResult r = assemble("{'initials':{'status':'extracted','value':'MK','confidence':'high',"
                + "'quotes':['Patient ZZ'],'reason':''}}").get("initials");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("Model value \"MK\" rejected: quote not found in document.");
        assertBare(r);
    }

    @Test
    void noQuotesAtAllBecomesUnknown() {
        FieldResult r = assemble("{'initials':{'status':'extracted','value':'MK','confidence':'high',"
                + "'quotes':[],'reason':''}}").get("initials");
        assertThat(r.note()).isEqualTo("Model value \"MK\" rejected: quote not found in document.");
    }

    @Test
    void conflictWithFewerThanTwoDistinctCandidatesBecomesUnknown() {
        FieldResult r = assemble("{'sex':{'status':'conflict','candidates':['female',' Female '],'quotes':[],"
                + "'reason':''}}").get("sex");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("Model reported conflict without two candidates.");
        assertBare(r);
    }

    @Test
    void conflictWithoutCandidatesBecomesUnknown() {
        FieldResult r = assemble("{'sex':{'status':'conflict','quotes':[],'reason':''}}").get("sex");
        assertThat(r.note()).isEqualTo("Model reported conflict without two candidates.");
    }

    @Test
    void conflictListsDistinctCandidatesInOrder() {
        FieldResult r = assemble("{'age':{'status':'conflict','value':54,'confidence':'high',"
                + "'candidates':[' 54 ','45','54','  '],'quotes':['aged 54'],'reason':''}}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.CONFLICT);
        assertThat(r.note()).isEqualTo("Candidates: 54; 45");
        assertBare(r);
    }

    @Test
    void modelUnknownCarriesTrimmedReason() {
        FieldResult r = assemble("{'age':{'status':'unknown','value':54,'confidence':'high',"
                + "'quotes':['aged 54'],'reason':'  not stated  '}}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.UNKNOWN);
        assertThat(r.note()).isEqualTo("not stated");
        assertBare(r);
    }

    @Test
    void modelUnknownWithoutReasonHasNullNote() {
        assertThat(assemble("{'age':{'status':'unknown','quotes':[]}}").get("age").note()).isNull();
        assertThat(assemble("{'age':{'status':'unknown','quotes':[],'reason':'  '}}").get("age").note()).isNull();
    }

    @Test
    void extractedWithConfidenceCappedNotesRules() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':54,'confidence':'high','derived':true,"
                + "'quotes':['aged 54'],'reason':''}}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.EXTRACTED);
        assertThat(r.confidence()).isEqualTo("medium");
        assertThat(r.note()).isEqualTo("Confidence lowered: DERIVED.");
    }

    @Test
    void extractedWithTwoRulesListsBoth() {
        FieldResult r = assemble("{'dose':{'status':'extracted','value':0.25,'confidence':'high','derived':true,"
                + "'quotes':['Dose 250 mcg'],'reason':''}}").get("dose");
        assertThat(r.confidence()).isEqualTo("medium");
        assertThat(r.note()).isEqualTo("Confidence lowered: DERIVED, LITERAL_ABSENT.");
    }

    @Test
    void cleanExtractionHasValueConfidenceProvenanceAndNoNote() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':54,'confidence':'high',"
                + "'quotes':['aged 54'],'reason':''}}").get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.EXTRACTED);
        assertThat(r.value()).isEqualTo(json("54"));
        assertThat(r.confidence()).isEqualTo("high");
        assertThat(r.provenance()).containsExactly(new Provenance("aged 54", 13, 20));
        assertThat(r.note()).isNull();
    }

    // --- brief names ---

    @Test
    void extraResponseKeysAreIgnored() {
        var out = assemble("{'not_a_field':{'status':'extracted','value':1},'age':{'status':'unknown','quotes':[]}}");
        assertThat(out).doesNotContainKey("not_a_field");
        assertThat(out.keySet()).containsExactly("initials", "age", "sex", "dose", "onset");
    }

    @Test
    void outputHasEveryCatalogFieldInCatalogOrder() {
        var out = assemble("{'onset':{'status':'unknown','quotes':[]},'initials':{'status':'unknown','quotes':[]}}");
        assertThat(out.keySet()).containsExactly("initials", "age", "sex", "dose", "onset");
    }

    @Test
    void missingConfidenceTreatedAsLowest() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':54,'quotes':['aged 54'],'reason':''}}")
                .get("age");
        assertThat(r.status()).isEqualTo(FieldStatus.EXTRACTED);
        assertThat(r.confidence()).isEqualTo("low");
        assertThat(r.note()).isEqualTo("Model gave no confidence; treated as low.");
    }

    @Test
    void blankConfidenceTreatedAsLowest() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':54,'confidence':' ','quotes':['aged 54']}}")
                .get("age");
        assertThat(r.confidence()).isEqualTo("low");
        assertThat(r.note()).isEqualTo("Model gave no confidence; treated as low.");
    }

    @Test
    void confidenceOutsideCatalogTreatedAsLowest() {
        FieldResult r = assemble("{'age':{'status':'extracted','value':54,'confidence':'certain',"
                + "'quotes':['aged 54']}}").get("age");
        assertThat(r.confidence()).isEqualTo("low");
        assertThat(r.note()).isEqualTo("Model confidence certain not in catalog; treated as low.");
    }

    @Test
    void noLoweredNoteWhenNothingDropsBelowModelLevel() {
        FieldResult r = assemble("{'onset':{'status':'extracted','value':'2026-05-15','confidence':'medium',"
                + "'derived':true,'quotes':['Onset on 15/05/2026']}}").get("onset");
        assertThat(r.confidence()).isEqualTo("medium");
        assertThat(r.note()).isNull();
        FieldResult r2 = assemble("{'onset':{'status':'extracted','value':'2026-05-16','confidence':'unsure',"
                + "'quotes':['Onset on 15/05/2026']}}").get("onset");
        assertThat(r2.confidence()).isEqualTo("low");
        assertThat(r2.note()).isEqualTo("Model confidence unsure not in catalog; treated as low.");
    }

    @Test
    void provenanceQuoteIsOriginalSpanText() {
        FieldResult r = assemble("{'initials':{'status':'extracted','value':'MK','confidence':'high',"
                + "'quotes':['patient mk'],'reason':''}}").get("initials");
        assertThat(r.status()).isEqualTo(FieldStatus.EXTRACTED);
        assertThat(r.provenance()).containsExactly(new Provenance("Patient  MK", 0, 11));
    }

    @Test
    void identicalSpansAreDedupedAndOrderKept() {
        FieldResult r = assemble("{'dose':{'status':'extracted','value':250,'confidence':'high',"
                + "'quotes':['Dose 250 mcg','dose 250 MCG','Patient  MK', 'no such text']}}").get("dose");
        assertThat(r.provenance()).extracting(Provenance::quote).containsExactly("Dose 250 mcg", "Patient  MK");
    }

    @Test
    void anchorSelectsNearestOccurrence() {
        SourceText text = SourceText.of("t", "aged 54 ... aged 54");
        var out = FieldAssembler.assemble(CATALOG,
                json("{'age':{'status':'extracted','value':54,'confidence':'high','quotes':['aged 54']}}"),
                text, OptionalInt.of(18), NO_HINT);
        assertThat(out.get("age").provenance()).containsExactly(new Provenance("aged 54", 12, 19));
    }

    @Test
    void hintsReachThePolicy() {
        SourceText text = SourceText.of("t", "Onset on 03/04/2026.");
        String fields = "{'onset':{'status':'extracted','value':'2026-04-03','confidence':'high',"
                + "'quotes':['Onset on 03/04/2026']}}";
        var noHint = FieldAssembler.assemble(CATALOG, json(fields), text, OptionalInt.empty(), NO_HINT);
        var hint = FieldAssembler.assemble(CATALOG, json(fields), text, OptionalInt.empty(),
                new DocumentHints(DateConvention.DAY_FIRST, true, false));
        assertThat(noHint.get("onset").confidence()).isEqualTo("low");
        assertThat(noHint.get("onset").note()).isEqualTo("Confidence lowered: AMBIGUOUS_NUMERIC_DATE.");
        assertThat(hint.get("onset").confidence()).isEqualTo("medium");
    }

    // --- response shape ---

    @Test
    void missingFieldsNodeIsShapeError() {
        assertThatThrownBy(() -> FieldAssembler.assemble(CATALOG, null, TEXT, OptionalInt.empty(), NO_HINT))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields missing");
        assertThatThrownBy(() -> FieldAssembler.assemble(CATALOG, json("[]"), TEXT, OptionalInt.empty(), NO_HINT))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields missing");
    }

    @Test
    void nonObjectEntryIsShapeError() {
        assertThatThrownBy(() -> assemble("{'age':'Patient  MK'}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age invalid");
    }

    @Test
    void missingOrInvalidStatusIsShapeError() {
        assertThatThrownBy(() -> assemble("{'age':{'value':54}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.status invalid");
        assertThatThrownBy(() -> assemble("{'age':{'status':'Patient  MK'}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.status invalid");
    }

    @Test
    void wrongTypedOptionalKeysAreShapeErrors() {
        assertThatThrownBy(() -> assemble("{'age':{'status':'unknown','quotes':'aged 54'}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.quotes invalid");
        assertThatThrownBy(() -> assemble("{'age':{'status':'conflict','candidates':[54]}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.candidates invalid");
        assertThatThrownBy(() -> assemble("{'age':{'status':'extracted','derived':'yes'}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.derived invalid");
        assertThatThrownBy(() -> assemble("{'age':{'status':'extracted','confidence':1}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.confidence invalid");
        assertThatThrownBy(() -> assemble("{'age':{'status':'unknown','reason':['x']}}"))
                .isInstanceOf(ResponseShapeException.class).hasMessage("fields.age.reason invalid");
    }
}
