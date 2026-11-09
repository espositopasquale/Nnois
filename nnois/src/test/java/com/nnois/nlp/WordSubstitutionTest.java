package com.nnois.nlp;

import static org.junit.Assert.assertEquals;

import com.nnois.nlp.AutoTuningMultilingualPipeline.NominalSenseComparison;
import com.nnois.nlp.NativeMultilingualPipeline.TransparencyResult;
import java.util.List;
import org.junit.Test;

public class WordSubstitutionTest {

    private static final String TEXT = "The Hospital near the river";

    private static List<NominalSenseComparison> senses() {
        return List.of(
                new NominalSenseComparison("hospital", "noun", 4, 12, "1", "n", "a clinic", true, 0.9,
                        List.of(new TransparencyResult("ita", "ospedale", 0.7), new TransparencyResult("ita", "clinica", 0.3),
                                new TransparencyResult("fra", "hôpital", 0.8))),
                new NominalSenseComparison("river", "noun", 22, 27, "2", "n", "a stream", true, 0.8,
                        List.of(new TransparencyResult("ita", "fiume", 0.1))),
                new NominalSenseComparison("river", "noun", 22, 27, "3", "n", "other", false, 0.2,
                        List.of(new TransparencyResult("ita", "riviera", 0.9))));
    }

    @Test
    public void moreSimilarPicksHighestScoreForSelectedSenseAndKeepsCase() {
        var options = new WordSubstitution.Options("it", WordSubstitution.Direction.MORE_SIMILAR, 0.5, 0);
        var result = WordSubstitution.apply(TEXT, WordSubstitution.propose(TEXT, senses(), options), null);
        assertEquals("The Ospedale near the river", result.text());
    }

    @Test
    public void lessSimilarPicksLowestScoreUnderThreshold() {
        var options = new WordSubstitution.Options("it", WordSubstitution.Direction.LESS_SIMILAR, 0.5, 0);
        var result = WordSubstitution.apply(TEXT, WordSubstitution.propose(TEXT, senses(), options), null);
        assertEquals("The Clinica near the fiume", result.text());
    }

    @Test
    public void grammarCheckerRejectsHarmfulSubstitution() {
        var options = new WordSubstitution.Options("it", WordSubstitution.Direction.LESS_SIMILAR, 0.5, 0);
        var proposals = WordSubstitution.propose(TEXT, senses(), options);
        var result = WordSubstitution.apply(TEXT, proposals, t -> t.contains("fiume") ? 0.1 : 0.9);
        assertEquals("The Clinica near the river", result.text());
        assertEquals(1, result.rejected().size());
    }
    @Test
    public void scaleRanksSourceSynonymsAgainstReferenceForms() {
        var sense = new NominalSenseComparison("hospital", "noun", 4, 12, "1", "n", "a clinic", true, 0.9,
                List.of(new TransparencyResult("eng", "hospital", 1), new TransparencyResult("eng", "clinic", 0),
                        new TransparencyResult("eng", "infirmary", 0), new TransparencyResult("ita", "clinica", 0)));
        var scale = WordSubstitution.scale(TEXT, sense, "eng", "ita");
        assertEquals("unverified", scale.validation());
        assertEquals("Clinic", scale.candidates().get(0).replacement());
        assertEquals("clinica", scale.candidates().get(0).referenceForm());
        assertEquals(2, scale.candidates().size());
        assertEquals(4, scale.begin());
    }

    @Test
    public void scaleKeepsItalianAlternativesInItalian() {
        var sense = new NominalSenseComparison("banca", "noun", 3, 8, "08420278", "n", "financial institution", true, 0.9,
                List.of(new TransparencyResult("ita", "banca", 0), new TransparencyResult("ita", "istituto di credito", 0),
                        new TransparencyResult("eng", "bank", 0)));
        var scale = WordSubstitution.scale("La banca", sense, "it", "en");
        assertEquals("istituto di credito", scale.candidates().get(0).replacement());
        assertEquals("bank", scale.candidates().get(0).referenceForm());
    }

}
