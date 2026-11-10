package com.nnois.nlp;

import static org.junit.Assert.*;
import com.nnois.nlp.wsd.OccurrenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import org.junit.Test;

public class MultilingualOmwDisambiguatorTest {
    private static Path database() {
        Path database = Path.of("nnois/src/main/resources/omw/omw_multilingual.db").toAbsolutePath();
        return Files.isRegularFile(database) ? database : Path.of("src/main/resources/omw/omw_multilingual.db").toAbsolutePath();
    }

    @Test public void resolvesItalianBankFromOmwCandidates() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var disambiguator = new NativeOmwDisambiguator(connection)) {
            var result = disambiguator.analyze("banca", "ita",
                    OccurrenceContext.uniqueTarget("La banca ha approvato il prestito", "banca"), Set.of(), "n");
            assertFalse("Italian OMW candidates missing", result.candidates().isEmpty());
            String ranking = result.candidates().stream().map(item -> item.candidate().offset + "-"
                    + item.candidate().pos + " " + item.applicabilityScore() + " " + item.candidate().gloss)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertNotNull("Scores did not resolve a sense:\n" + ranking, result.selected());
            assertEquals("08420278", result.selected().offset);
        }
    }

    @Test public void wholeItalianSentenceProducesOmwMatches() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var pipeline = new AutoTuningMultilingualPipeline(connection, "langdetect-183.bin")) {
            var result = pipeline.processContrastive("La banca ha approvato il prestito", null, "ita", false);
            assertTrue("Expected at least one resolved Italian OMW unit", result.analyzedNominalUnits > 0);
            assertFalse("Expected multilingual transparency results", result.transparencyResults.isEmpty());
        }
    }

    @Test public void resolvesLanguagesWhoseOmwEntriesOmitGlosses() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var disambiguator = new NativeOmwDisambiguator(connection)) {
            assertResolved(disambiguator, "banque", "fra", "La banque approuve le pret de la famille");
            assertResolved(disambiguator, "banco", "spa", "El banco aprueba el prestamo para la familia");
            assertResolved(disambiguator, "bank", "nld", "De bank keurt de lening voor het gezin goed");
        }
    }

    private static void assertResolved(NativeOmwDisambiguator disambiguator, String lemma,
            String language, String sentence) throws Exception {
        var result = disambiguator.analyze(lemma, language,
                OccurrenceContext.uniqueTarget(sentence, lemma), Set.of(), "n");
        assertFalse(language + " OMW candidates missing", result.candidates().isEmpty());
        assertTrue(language + " candidates still have no definitions",
                result.candidates().stream().allMatch(item -> !item.candidate().gloss.isBlank()));
        assertNotNull(language + " scores did not resolve a sense", result.selected());
    }
}
