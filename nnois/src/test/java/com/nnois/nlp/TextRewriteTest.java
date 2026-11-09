package com.nnois.nlp;

import static org.junit.Assert.*;
import com.nnois.nlp.AutoTuningMultilingualPipeline.NominalSenseComparison;
import com.nnois.nlp.NativeMultilingualPipeline.TransparencyResult;
import java.util.List;
import org.junit.Test;

public class TextRewriteTest {
    private static NominalSenseComparison sense(String word, int begin, int end, boolean selected) {
        return new NominalSenseComparison(word, "noun", begin, end, "1", "n", "medical institution", selected, 0.9,
                List.of(new TransparencyResult("eng", "hospital", 1), new TransparencyResult("eng", "clinic", 0),
                        new TransparencyResult("eng", "infirmary", 0), new TransparencyResult("ita", "clinica", 0),
                        new TransparencyResult("fra", "hopital", 0)));
    }
    private static TextRewrite.Input input(String text, String direction) {
        return new TextRewrite.Input(text, "en", "it", direction);
    }
    @Test public void selectsSourceSynsetFormByDirectionAndBaseline() {
        var more = TextRewrite.plan(input("The hospital", "more"), List.of(sense("hospital", 4, 12, true)));
        assertEquals(1, more.choices().size());
        var choice = more.choices().get(0);
        assertEquals("clinic", choice.target());
        assertEquals("clinica", choice.referenceForm());
        assertTrue(choice.delta() > 0);
        var less = TextRewrite.plan(input("The clinic", "less"), List.of(sense("clinic", 4, 10, true)));
        assertFalse(less.choices().isEmpty());
        assertTrue(less.choices().get(0).delta() < 0);
        assertFalse("clinica".equals(less.choices().get(0).target()));
    }
    @Test public void sameOrWrongDirectionAndUnselectedSensesDoNotBecomeProposals() {
        var sense = new NominalSenseComparison("music", "noun", 0, 5, "1", "n", "", true, 1,
                List.of(new TransparencyResult("eng", "music", 1), new TransparencyResult("eng", "musical", 0),
                        new TransparencyResult("ita", "music", 1)));
        var plan = TextRewrite.plan(input("music", "more"), List.of(sense));
        assertTrue(plan.choices().isEmpty());
        assertEquals(1, plan.unavailableUnits());
        assertEquals(0, TextRewrite.plan(input("hospital", "more"), List.of(sense("hospital", 0, 8, false))).consideredUnits());
    }
    @Test public void minimumChangeFiltersWeakCandidates() {
        var strict = TextRewrite.plan(new TextRewrite.Input("hospital", "en", "it", "more", 0.9),
                List.of(sense("hospital", 0, 8, true)));
        assertTrue(strict.choices().isEmpty());
        var permissive = TextRewrite.plan(new TextRewrite.Input("hospital", "en", "it", "more", 0.05),
                List.of(sense("hospital", 0, 8, true)));
        assertEquals(1, permissive.choices().size());
        assertEquals(0.05, TextRewrite.validate(new TextRewrite.Input("hospital", "en", "it", "more", 0.05)).minimumChange(), 0.0);
    }
    @Test public void acceptsOnlyValidatedProposalsAndPreservesAllOthers() {
        var p = TextRewrite.plan(input("hospital hospital", "more"), List.of(sense("hospital",0,8,true),sense("hospital",9,17,true)));
        var result = TextRewrite.complete(input("hospital hospital", "more"), p, List.of("accepted", "unverified"), null);
        assertEquals("clinic hospital", result.text());
        assertEquals(1, result.appliedChoices());
        assertEquals("unverified", result.choices().get(1).status());
        var failed = TextRewrite.complete(input("hospital hospital", "more"), p, List.of("accepted"), "failed");
        assertEquals("hospital hospital", failed.text());
        assertEquals(0, failed.appliedChoices());
    }
    @Test public void acceptsEverySupportedSourceLanguage() {
        for (String source : List.of("en", "it", "fr", "de", "es", "pt", "nl")) {
            String reference = source.equals("en") ? "it" : "en";
            assertEquals(LanguageIdentification.normalizeCode(source), TextRewrite.validate(
                    new TextRewrite.Input("testo", source, reference, "more")).sourceLanguage());
        }
    }
    @Test public void overlapsChooseLongestAtSamePosition() {
        var p = TextRewrite.plan(input("hospital ward", "more"), List.of(sense("hospital",0,8,true), sense("hospital ward",0,13,true)));
        assertEquals(1, p.consideredUnits());
    }
    private static void expectFailure(Class<? extends Exception> expected, Runnable task) {
        try { task.run(); fail("Expected " + expected.getSimpleName()); }
        catch (Exception e) { assertTrue(expected.isInstance(e)); }
    }
    @Test public void rejectsInvalidInputAndInvalidModelOutput() {
        expectFailure(IllegalArgumentException.class, () -> TextRewrite.validate(input("test", "neutral")));
        expectFailure(IllegalArgumentException.class, () -> TextRewrite.validate(new TextRewrite.Input("hello", "xx", "eng", "more")));
        expectFailure(IllegalArgumentException.class, () -> TextRewrite.validate(input("x".repeat(1001), "more")));
        expectFailure(IllegalArgumentException.class, () -> TextRewrite.validate(new TextRewrite.Input("hello", "en", "it", "more", 1.1)));
        expectFailure(IllegalStateException.class, () -> TextRewrite.parse("{\"statuses\":[\"accepted\"]}", 2));
        expectFailure(IllegalStateException.class, () -> TextRewrite.parse("{\"statuses\":[\"yes\"]}", 1));
    }
}
