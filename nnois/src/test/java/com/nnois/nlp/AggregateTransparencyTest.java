package com.nnois.nlp;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AggregateTransparencyTest {
    @Test public void synonymsContributeOnlyOncePerLanguage() {
        var results = List.of(
                new NativeMultilingualPipeline.TransparencyResult("ita", "a", 0.8),
                new NativeMultilingualPipeline.TransparencyResult("ita", "b", 0.9),
                new NativeMultilingualPipeline.TransparencyResult("ita", "b", 0.9),
                new NativeMultilingualPipeline.TransparencyResult("fra", "c", 0.4));
        var scores = AutoTuningMultilingualPipeline.bestLanguageScores(results);
        assertEquals(0.9, scores.get("ita"), 1e-9);
        assertEquals(0.4, scores.get("fra"), 1e-9);
        assertTrue(AutoTuningMultilingualPipeline.bestLanguageScores(List.of()).isEmpty());
    }
}
