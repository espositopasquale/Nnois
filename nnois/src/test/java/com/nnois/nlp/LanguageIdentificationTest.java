package com.nnois.nlp;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class LanguageIdentificationTest {
    @Test public void identifiesAllBundledLanguages() throws Exception {
        var detector = new LanguageIdentification("langdetect-183.bin");
        var samples = Map.of(
            "eng", "The children are reading a book in the garden while their parents prepare dinner in the kitchen.",
            "ita", "I bambini leggono un libro nel giardino mentre i loro genitori preparano la cena in cucina.",
            "fra", "Les enfants lisent un livre dans le jardin pendant que leurs parents préparent le dîner dans la cuisine.",
            "deu", "Die Kinder lesen ein Buch im Garten während ihre Eltern das Abendessen in der Küche vorbereiten.",
            "spa", "Los niños leen un libro en el jardín mientras sus padres preparan la cena en la cocina.",
            "por", "As crianças estão lendo um livro no jardim enquanto os pais preparam o jantar na cozinha.",
            "nld", "De kinderen lezen een boek in de tuin terwijl hun ouders het avondeten in de keuken bereiden.");
        for (var sample : samples.entrySet()) {
            var result = detector.detect(sample.getValue());
            assertEquals(sample.getValue(), sample.getKey(), result.code());
            assertEquals(3, result.alternatives().size());
            assertTrue(result.confidence() >= 0 && result.confidence() <= 1);
        }
    }
    @Test public void shortWordsStayUncertainAndNoiseIsRemoved() throws Exception {
        var detector = new LanguageIdentification("langdetect-183.bin");
        assertTrue(detector.detect("bank").uncertain());
        assertEquals("Hello world", LanguageIdentification.clean("Hello\nhttps://example.org/test world user@example.com"));
        try { detector.detect("123 https://example.org"); fail("Expected no words error"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("words")); }
    }
    @Test public void aliasesNormalizeConsistently() {
        assertEquals("eng", LanguageIdentification.normalizeCode(" English "));
        assertEquals("fra", LanguageIdentification.normalizeCode("fre"));
        assertEquals("deu", LanguageIdentification.normalizeCode("DE"));
        assertFalse(LanguageIdentification.supported("jpn"));
        assertEquals("Italian (ita)", LanguageIdentification.label("it"));
    }
    @Test public void mixedSentenceLanguagesRemainUncertain() throws Exception {
        var detector = new LanguageIdentification("langdetect-183.bin");
        var result = detector.detect("The children are reading a book in the garden while their parents prepare dinner in the kitchen. "
                + "I bambini leggono un libro nel giardino mentre i loro genitori preparano la cena in cucina.");
        assertTrue(result.mixedLanguage());
        assertTrue(result.uncertain());
    }
    @Test public void detectsAllFullDemoPassages() throws Exception {
        var detector = new LanguageIdentification("langdetect-183.bin");
        for (var sample : com.nnois.DemoTexts.SAMPLES) {
            assertEquals(sample.language(), detector.detect(sample.text()).code());
            assertTrue(sample.text().contains(sample.target()));
        }
    }
}
