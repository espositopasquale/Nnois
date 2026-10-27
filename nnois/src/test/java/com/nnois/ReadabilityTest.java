package com.nnois;

import com.nnois.nlp.*;
import java.sql.Connection;
import java.sql.DriverManager;
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
            assertEquals(4, result.units.get(0).begin);
            assertEquals(8, result.units.get(0).end);
            assertEquals(9, result.units.get(1).begin);
            assertEquals(13, result.units.get(1).end);
            assertEquals(3, result.totalWords);
            assertEquals(2.0 / 3, result.totalAggregateScore, 1e-9);
            assertEquals(0, extractor.analyzeNominalAggregateScore("the and .", "eng", conn).totalWords);
            assertEquals(0, extractor.analyzeNominalAggregateScore("", "eng", conn).totalAggregateScore, 0);
            assertEquals(1, extractor.analyzeNominalAggregateScore("tree tree", "eng", conn).totalAggregateScore, 0);
        }
    }

    @Test public void nativeModelsActuallyLoadAndLemmatize() throws Exception {
        var nlp = new NativeLanguageNLP("opennlp-en-token.bin", "opennlp-en-pos.bin", "en-lemmatizer.dict");
        assertTrue(nlp.lemmatizeTokens(nlp.tokenize("The children ran.")).contains("child"));
        assertEquals("v", nlp.inferPreferredOmwPos("They run daily.", "run"));
    }
}
