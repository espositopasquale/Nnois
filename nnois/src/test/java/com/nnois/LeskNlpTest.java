package com.nnois;

import com.nnois.nlp.LeskNlp;
import com.nnois.disambiguator.JwiLeskDisambiguator;
import edu.mit.jwi.item.POS;
import org.junit.Test;
import org.junit.Assume;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public class LeskNlpTest {
    @Test
    public void modelsSelectAndLemmatizeContentWords() throws Exception {
        LeskNlp nlp = new LeskNlp();
        List<String> lemmas = nlp.analyze("The children were running beside the ox.").stream()
                .map(LeskNlp.Term::lemma).toList();
        assertTrue(lemmas.contains("child"));
        assertTrue(lemmas.contains("run"));
        assertTrue(lemmas.contains("ox"));
        assertFalse(lemmas.contains("the"));
        assertFalse(lemmas.contains("be"));
        assertFalse(lemmas.contains("beside"));
        assertTrue(nlp.analyze(null).isEmpty());
        assertTrue(nlp.analyze(" ").isEmpty());
    }

    @Test
    public void missingModelsProduceAnActionableError() throws Exception {
        try {
            new LeskNlp("missing-tokenizer.bin", "missing-pos.bin", "missing-lemma.bin");
            fail("Expected missing model error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("missing-tokenizer.bin"));
        }
    }

    @Test
    public void wordnetContextSelectsDistinctBankSenses() throws Exception {
        String path = System.getProperty("wordnet.dict");
        Assume.assumeTrue(path != null);
        try (JwiLeskDisambiguator lesk = new JwiLeskDisambiguator(path)) {
            var river = lesk.disambiguate("bank", POS.NOUN,
                    List.of("The", "river", "water", "flowed", "along", "the", "bank"));
            var finance = lesk.disambiguate("bank", POS.NOUN,
                    List.of("The", "bank", "accepts", "deposits", "and", "lends", "money"));
            assertNotNull(river);
            assertNotNull(finance);
            assertFalse(river.getID().equals(finance.getID()));
            assertEquals(river.getID(), lesk.disambiguate("banks", POS.NOUN,
                    List.of("The", "river", "water", "flowed", "along", "the", "banks")).getID());
            assertNull(lesk.disambiguate("", POS.NOUN, List.of()));
            assertNull(lesk.disambiguate("zzzzunknownword", POS.NOUN, List.of()));
        }
    }
}
