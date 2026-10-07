package com.nnois;

import com.nnois.nlp.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadabilityTest {
    private Connection database() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var sql = conn.createStatement()) {
            sql.execute("CREATE TABLE synset_lemmas (synset_offset TEXT, pos TEXT, lang TEXT, lemma TEXT)");
            sql.execute("CREATE TABLE synset_def (synset_offset TEXT, pos TEXT, lang TEXT, def TEXT)");
            sql.execute("INSERT INTO synset_lemmas VALUES ('01','n','eng','tree'), ('02','n','eng','bank'), ('03','n','eng','bank'), ('04','v','eng','bank'), ('05','n','eng','the')");
            sql.execute("INSERT INTO synset_def VALUES ('02','n','eng','financial institution for money'), ('03','n','eng','sloping land beside water'), ('04','v','eng','turn an aircraft')");
        }
        return conn;
    }

    @Test public void coverageCountsOccurrencesAndExcludesStopwordsAndPunctuation() throws Exception {
        try (Connection conn = database()) {
            var extractor = new OpenNLPMWEExtractor("missing", "missing", "missing", "missing");
            var result = extractor.analyzeNominalAggregateScore("the tree tree quickly .", "eng", conn);
            assertEquals(2, result.consideredWords);
            assertEquals(3, result.totalWords);
            assertEquals(2.0 / 3, result.totalAggregateScore, 1e-9);
            assertEquals(0, extractor.analyzeNominalAggregateScore("the and .", "eng", conn).totalWords);
            assertEquals(0, extractor.analyzeNominalAggregateScore("", "eng", conn).totalAggregateScore, 0);
            assertEquals(1, extractor.analyzeNominalAggregateScore("tree tree", "eng", conn).totalAggregateScore, 0);
        }
    }

    @Test public void modelSelectsAnExistingOmwSenseAfterPosFiltering() throws Exception {
        try (Connection conn = database()) {
            OmwSenseModel model = (context, definitions) -> {
                assertFalse(context.contains("the"));
                assertEquals(2, definitions.size());
                return definitions.stream().mapToDouble(d -> d.contains("water") ? 0.9 : 0.1).toArray();
            };
            var wsd = new NativeOmwDisambiguator(conn, model);
            var sense = wsd.disambiguateNative("bank", "eng", List.of("the", "river", "bank"), Stopwords.forLanguage("eng"), "n");
            assertEquals("03", sense.offset);
            assertEquals("n", sense.pos);
            assertNull(wsd.disambiguateNative("the", "eng", List.of("tree"), Stopwords.forLanguage("eng")));
        }
    }

    @Test public void unavailableModelFallsBackToLexicalOverlap() throws Exception {
        try (Connection conn = database()) {
            var wsd = new NativeOmwDisambiguator(conn, (context, definitions) -> { throw new java.io.IOException("offline"); });
            assertEquals("02", wsd.disambiguateNative("bank", "eng", List.of("money"), Set.of(), "n").offset);
            assertEquals("03", wsd.disambiguateNative("bank", "eng", List.of("water"), Set.of(), "n").offset);
        }
    }

    @Test public void nativeModelsActuallyLoadAndLemmatize() throws Exception {
        var nlp = new NativeLanguageNLP("opennlp-en-token.bin", "opennlp-en-pos.bin", "en-lemmatizer.dict");
        assertTrue(nlp.lemmatizeTokens(nlp.tokenize("The children ran.")).contains("child"));
        assertEquals("v", nlp.inferPreferredOmwPos("They run daily.", "run"));
    }
}
