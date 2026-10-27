package com.nnois.nlp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.nnois.nlp.wsd.WordNetRepository;

public class NominalSenseComparisonTest {

    private Connection database() throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE synset_lemmas (synset_offset TEXT, pos TEXT, lang TEXT, lemma TEXT)");
            sql.execute("CREATE TABLE synset_def (synset_offset TEXT, pos TEXT, lang TEXT, def TEXT)");
        }
        try (var wordnet = WordNetRepository.configured(); var insert = connection.prepareStatement("INSERT INTO synset_lemmas VALUES (?, 'n', ?, ?)")) {
            for (var candidate : wordnet.candidates("bank", "NN")) {
                boolean financial = candidate.synset().getGloss().contains("financial institution");
                boolean riverside = candidate.synset().getGloss().contains("sloping land");
                if (!financial && !riverside) {
                    continue;
                }
                String offset = String.format(java.util.Locale.ROOT, "%08d", candidate.synset().getOffset());
                for (var row : List.of(new String[]{"eng", "bank"},
                        new String[]{"ita", financial ? "banca" : "bank"},
                        new String[]{"fra", financial ? "banque" : "rive"})) {
                    insert.setString(1, offset);
                    insert.setString(2, row[0]);
                    insert.setString(3, row[1]);
                    insert.executeUpdate();
                }
            }
        }
        return connection;
    }

    private OmwSenseModel contextualModel() {
        return (context, definitions) -> definitions.stream().mapToDouble(definition
                -> definition.contains(context.text().contains("river") ? "sloping land" : "financial institution") ? 4 : 1).toArray();
    }

    @Test
    public void alternativesStayInDetailsWhileSelectedSenseDrivesScoresAndGraph() throws Exception {
        try (var pipeline = new AutoTuningMultilingualPipeline(database(), "langdetect-183.bin", contextualModel())) {
            var result = pipeline.processContrastive("The bank approved the loan.", null, "eng", false);
            var selected = result.nominalSenseComparisons.stream().filter(s -> s.selected()).toList();
            assertEquals(1, result.analyzedNominalUnits);
            assertEquals(1, selected.size());
            assertTrue(selected.get(0).gloss().contains("financial institution"));
            assertTrue(result.nominalSenseComparisons.get(0).selected());
            assertTrue(result.nominalSenseComparisons.stream().filter(s -> !s.selected())
                    .flatMap(s -> s.translations().stream()).anyMatch(row -> row.lang.equals("ita") && row.lemma.equals("bank")));
            assertFalse(result.nominalLanguageTransparencies.stream()
                    .anyMatch(row -> row.language.equals("ita") && row.lemma.equals("bank")));
            assertTrue(result.similarityGraph.nodes().stream().anyMatch(node -> node.language().equals("ita") && node.word().equals("banca")));
            assertFalse(result.similarityGraph.nodes().stream().anyMatch(node -> node.language().equals("ita") && node.word().equals("bank")));

            var single = pipeline.processContrastive("The bank approved the loan.", "bank", "eng", false);
            assertTrue(single.chosenSense.gloss.contains("financial institution"));
            assertTrue(single.nominalSenseComparisons.isEmpty());
            assertFalse(single.transparencyResults.stream().anyMatch(row -> row.lang.equals("ita") && row.lemma.equals("bank")));
        }
    }

    @Test
    public void unresolvedCandidatesNeverBecomeRecognizedSenses() throws Exception {
        try (var pipeline = new AutoTuningMultilingualPipeline(database(), "langdetect-183.bin",
                (context, definitions) -> definitions.stream().mapToDouble(d -> 1).toArray())) {
            var result = pipeline.processContrastive("The bank approved the loan.", null, "eng", false);
            assertFalse(result.nominalSenseComparisons.isEmpty());
            assertTrue(result.nominalSenseComparisons.stream().noneMatch(s -> s.selected()));
            assertEquals(0, result.analyzedNominalUnits);
            assertTrue(result.nominalLanguageTransparencies.isEmpty());
            assertTrue(result.transparencyResults.isEmpty());
        }
    }

    @Test
    public void repeatedWordsRetainTheirOwnOccurrencesAndSelectedSenses() throws Exception {
        try (var pipeline = new AutoTuningMultilingualPipeline(database(), "langdetect-183.bin", contextualModel())) {
            var result = pipeline.processContrastive("The bank approved the loan. The river flooded the bank.", null, "eng", false);
            var selected = result.nominalSenseComparisons.stream().filter(s -> s.selected()).toList();
            assertEquals(2, selected.size());
            assertNotEquals(selected.get(0).begin(), selected.get(1).begin());
            assertNotEquals(selected.get(0).synsetOffset(), selected.get(1).synsetOffset());
        }
    }
}
