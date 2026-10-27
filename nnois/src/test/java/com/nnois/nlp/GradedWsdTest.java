package com.nnois.nlp;

import com.google.gson.JsonParser;
import com.nnois.nlp.wsd.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class GradedWsdTest {
    private final OccurrenceContext bank = OccurrenceContext.uniqueTarget("The bank approved the loan.", "bank");

    @Test public void preservesContextAndRanksEnglishDefinitions() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                var wsd = new NativeOmwDisambiguator(conn, (context, definitions) -> {
                    assertEquals("The <t>bank</t> approved the loan.", context.markedText());
                    assertTrue(definitions.size() > 1);
                    return definitions.stream().mapToDouble(d -> d.contains("financial institution") ? 4 : 1).toArray();
                })) {
            var result = wsd.analyze("bank", "en", bank, Set.of("the"), "n");
            assertEquals("resolved", result.status());
            assertTrue(result.selected().gloss.contains("financial institution"));
            assertEquals("n", result.selected().pos);
            assertEquals(8, result.selected().offset.length());
            assertEquals(4, result.candidates().get(0).applicabilityScore(), 0);
        }
    }

    @Test public void scoresSingleSenseAndAbstainsOnWeakEvidence() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                var wsd = new NativeOmwDisambiguator(conn, (context, definitions) -> {
                    assertEquals(1, definitions.size()); return new double[] { 1 };
                })) {
            var context = OccurrenceContext.uniqueTarget("She played the xylophone.", "xylophone");
            assertEquals("ambiguous", wsd.analyze("xylophone", "eng", context, Set.of(), "n").status());
            assertNull(wsd.disambiguateNative("xylophone", "eng", context, Set.of(), "n"));
        }
    }

    @Test public void unknownWordsAndStopwordsSkipInference() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                var wsd = new NativeOmwDisambiguator(conn, (context, definitions) -> { throw new AssertionError(); })) {
            assertEquals("unlinked", wsd.analyze("zzzzunknownword", "eng", bank, Set.of(), "n").status());
            assertEquals("unlinked", wsd.analyze("the", "eng", bank, Set.of("the"), "n").status());
        }
    }

    @Test public void enforcesPosWithoutFallbackToNouns() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                var wsd = new NativeOmwDisambiguator(conn, (context, definitions) -> {
                    double[] scores = new double[definitions.size()]; scores[0] = 4; return scores;
                })) {
            var result = wsd.analyze("bank", "eng", bank, Set.of(), "v");
            assertTrue(result.candidates().stream().allMatch(c -> c.candidate().pos.equals("v")));
            assertEquals("v", result.selected().pos);
        }
    }

    @Test public void rejectsNonEnglishAndPropagatesFailures() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                var wsd = new NativeOmwDisambiguator(conn, (context, definitions) -> { throw new java.io.IOException("fixture failure"); })) {
            try { wsd.analyze("banca", "ita", bank, Set.of(), "n"); fail(); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("English")); }
            try { wsd.analyze("bank", "eng", bank, Set.of(), "n"); fail(); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("fixture failure")); }
        }
    }

    @Test public void thresholdsAndMarginsMatchKExtractor() {
        var policy = new WsdSelection(2.5, 0.25);
        assertEquals(1, policy.select(new double[] { 3, 4 }));
        assertEquals(-1, policy.select(new double[] { 2.49 }));
        assertEquals(0, policy.select(new double[] { 2.5, 2.25 }));
        assertEquals(-1, policy.select(new double[] { 3, 2.9 }));
        assertEquals(-1, new WsdSelection(0, 0).select(new double[] { 3, 3 }));
        assertEquals(-1, policy.select(new double[0]));
        try { policy.select(new double[] { Double.NaN }); fail(); } catch (IllegalArgumentException expected) { }
        try { new WsdSelection(0, -1); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void originalOffsetsDistinguishRepeatedTargetsAndPreserveUnicode() {
        String text = "First sentence. The 🌊 bank was beside another bank.";
        int begin = text.lastIndexOf("bank");
        assertEquals("The 🌊 bank was beside another <t>bank</t>.",
                OccurrenceContext.fromDocument(text, begin, begin + 4).markedText());
        try { OccurrenceContext.uniqueTarget(text, "bank"); fail(); } catch (IllegalArgumentException expected) { }
        try { OccurrenceContext.of("🌊 bank", 1, 2); fail(); } catch (IllegalArgumentException expected) { }
        try { OccurrenceContext.of("<t>bank</t>", 3, 7); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void findsInflectedOccurrencesWithoutLosingTheOriginalText() throws Exception {
        var nlp = new NativeLanguageNLP("opennlp-en-token.bin", "opennlp-en-pos.bin", "en-lemmatizer.dict");
        String text = "The banks lend money.";
        var target = nlp.findTarget(text, "banks");
        assertEquals("bank", target.lemma()); assertEquals("n", target.pos());
        assertEquals("banks", text.substring(target.begin(), target.end()));
        assertEquals(target, nlp.findTarget(text, "bank"));
    }

    @Test public void tokenizerMatchesTheIndependentHuggingFaceReference() throws Exception {
        var directory = GradedWsdModel.configuredDirectory();
        var tokenizer = new GradedWsdTokenizer(directory.resolve("tokenizer.json"));
        try (var input = new java.io.InputStreamReader(getClass().getResourceAsStream("/wsd/tokenizer-reference.json"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            var reference = JsonParser.parseReader(input).getAsJsonObject();
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(directory.resolve("tokenizer.json"))));
            assertEquals(reference.get("tokenizerSha256").getAsString(), digest);
            for (var value : reference.getAsJsonArray("cases")) {
                var example = value.getAsJsonObject();
                long[] expected = new long[example.getAsJsonArray("ids").size()];
                for (int i = 0; i < expected.length; i++) expected[i] = example.getAsJsonArray("ids").get(i).getAsLong();
                assertArrayEquals(example.get("name").getAsString(), expected,
                        tokenizer.encode(example.get("sentence").getAsString(), example.get("definition").getAsString()));
            }
        }
        try { tokenizer.encode("unmarked bank", "definition"); fail(); } catch (IllegalArgumentException expected) { }
        try { new GradedWsdModel(Path.of("missing-graded-wsd-assets")); fail(); } catch (java.io.IOException expected) { }
    }
}
