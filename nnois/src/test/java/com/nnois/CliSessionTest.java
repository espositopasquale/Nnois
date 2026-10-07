package com.nnois;

import com.nnois.nlp.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Scanner;
import org.junit.Test;
import static org.junit.Assert.*;

public class CliSessionTest {
    private static class FakeService implements App.AnalysisService {
        int analyses;
        String language;
        String text;
        boolean failOnce;
        String detected = "eng";
        public LanguageIdentification.Detection detect(String text) {
            return new LanguageIdentification.Detection(detected, 0.6,
                    List.of(new LanguageIdentification.Candidate(detected, 0.6)), true, 3);
        }
        public AutoTuningMultilingualPipeline.ContrastiveResult analyze(String text, String word, String language) throws Exception {
            analyses++; this.language = language; this.text = text;
            if (failOnce && analyses == 1) throw new Exception("fixture failure");
            return new AutoTuningMultilingualPipeline.ContrastiveResult(
                AutoTuningMultilingualPipeline.AnalysisMode.NOMINAL_AGGREGATE_SUM, language, 1, null, 0,
                new OpenNLPMWEExtractor.NominalAggregateResult(0, 0, List.of()), List.of(), List.of());
        }
        public List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses(String word, String language) { return List.of(); }
    }
    private String session(FakeService service, String input) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(captured, true, "UTF-8"); Scanner scanner = new Scanner(input)) {
            System.setOut(output); App.runSession(service, scanner);
        } finally { System.setOut(original); }
        return captured.toString("UTF-8");
    }
    @Test public void enterAcceptsDefaultsAndInvalidLanguageReprompts() throws Exception {
        var service = new FakeService();
        String output = session(service, "The tree grows\ninvalid\n\n\nexit\n");
        assertEquals(1, service.analyses);
        assertEquals("eng", service.language);
        assertTrue(output.contains("Language is uncertain"));
        assertTrue(output.contains("Choose en"));
        assertFalse(output.contains("\u001b"));
    }
    @Test public void errorsPreserveSessionAndPasteAcceptsMultipleLines() throws Exception {
        var service = new FakeService(); service.failOnce = true;
        String output = session(service, "The tree grows\n\n\npaste\nThe tree\ngrows quickly\n.\nit\n\nexit\n");
        assertEquals(2, service.analyses);
        assertEquals("ita", service.language);
        assertEquals("The tree\ngrows quickly", service.text);
        assertTrue(output.contains("session is still open"));
    }
    @Test public void unsupportedLanguageNeedsOverrideAndBackCancels() throws Exception {
        var service = new FakeService(); service.detected = "jpn";
        String output = session(service, "Some input text\n\nback\nexit\n");
        assertEquals(0, service.analyses);
        assertTrue(output.contains("no bundled NLP models"));
    }
    @Test public void eofEndsWithoutRepeatedPrompts() throws Exception {
        var service = new FakeService();
        session(service, "The tree grows\n");
        assertEquals(0, service.analyses);
    }
    @Test public void badTargetsRepromptInsteadOfChangingAnalysisMode() throws Exception {
        var service = new FakeService();
        String output = session(service, "The tree grows\n\n2\nthe\nmissing\nback\n\nexit\n");
        assertEquals(1, service.analyses);
        assertTrue(output.contains("Stopwords are excluded"));
        assertTrue(output.contains("word that appears"));
    }
    @Test public void interactiveDemoRunsAllLanguagesAndReturnsToTextPrompt() throws Exception {
        var service = new FakeService();
        String output = session(service, "demo\n\nexit\n");
        assertEquals(14, service.analyses);
        assertTrue(output.contains("European languages demo"));
        assertTrue(output.contains("Demo summary"));
        assertTrue(output.endsWith("Goodbye.\n") || output.endsWith("Goodbye.\r\n"));
    }
    @Test public void singleLanguageDemoAcceptsAliasAndCancellation() throws Exception {
        var service = new FakeService();
        String output = session(service, "demo\nback\ndemo Italian\ndemo invalid\nexit\n");
        assertEquals(2, service.analyses);
        assertTrue(service.text.contains("fiume"));
        assertTrue(output.contains("Demo language must be"));
    }
}
