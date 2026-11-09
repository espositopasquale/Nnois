package com.nnois.nlp.wsd;

import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public class MultilingualE5ModelTest {
    @Test public void ranksItalianContextAgainstEnglishGlossesInsideJvm() throws Exception {
        var directory = MultilingualE5Model.configuredDirectory();
        Assume.assumeTrue(Files.isRegularFile(directory.resolve("model.onnx")));
        try (var model = new MultilingualE5Model()) {
            var context = OccurrenceContext.uniqueTarget("La banca ha approvato il prestito.", "banca");
            double[] scores = model.score(context, List.of(
                    "a financial institution that accepts deposits and lends money",
                    "sloping land beside a body of water"));
            assertTrue(scores[0] > scores[1]);
            assertTrue(scores[0] >= 0.65);
            assertTrue(scores[0] - scores[1] >= 0.01);
        }
    }
}
