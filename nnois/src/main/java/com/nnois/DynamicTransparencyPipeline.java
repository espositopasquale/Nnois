package com.nnois;

import java.io.File;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.nnois.utils.ResourceLoaderHelper;
import com.nnois.disambiguator.JwiGradedWsdDisambiguator;

import edu.mit.jwi.Dictionary;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.IStemmer;
import edu.mit.jwi.morph.WordnetStemmer;

public class DynamicTransparencyPipeline implements AutoCloseable {

    private final IDictionary dict;
    private final IStemmer stemmer;
    private final JwiGradedWsdDisambiguator disambiguator;
    private final Connection sqliteConn;
    private File tempDbFile;

    public static class DynamicResult {

        public final String targetLemma;
        public final ISynset selectedSynset;
        public final List<LangTransparency> languageScores;

        public DynamicResult(String targetLemma, ISynset selectedSynset, List<LangTransparency> languageScores) {
            this.targetLemma = targetLemma;
            this.selectedSynset = selectedSynset;
            this.languageScores = languageScores;
        }
    }

    public static class LangTransparency {

        public final String lang;
        public final String translatedLemma;
        public final double transparencyScore;

        public LangTransparency(String lang, String translatedLemma, double transparencyScore) {
            this.lang = lang;
            this.translatedLemma = translatedLemma;
            this.transparencyScore = transparencyScore;
        }

        @Override
        public String toString() {
            return String.format("[%s] %-20s | Trasparenza Ortografica: %.2f%%",
                    lang, translatedLemma, transparencyScore * 100);
        }
    }

    public DynamicTransparencyPipeline(String dictResourceFolder, String sqliteResourcePath, String stopwordsResourcePath) throws Exception {
        URL url = getClass().getClassLoader().getResource(dictResourceFolder);
        if (url == null) {
            File dictDir = new File(dictResourceFolder);
            url = dictDir.toURI().toURL();
        }
        this.dict = new Dictionary(url);
        this.dict.open();
        this.stemmer = new WordnetStemmer(this.dict);

        ResourceLoaderHelper resourceLoader = new ResourceLoaderHelper();
        this.disambiguator = new JwiGradedWsdDisambiguator(dict);
        this.sqliteConn = resourceLoader.loadSqliteFromResources(sqliteResourcePath);
    }

    public DynamicResult analyzeAndCalculateTransparency(String rawWord, POS pos, List<String> sentenceTokens) throws Exception {
        String targetLemma = getLemma(rawWord, pos);
        if (targetLemma == null) {
            return null;
        }

        ISynset bestSynset = disambiguator.disambiguate(rawWord, pos, sentenceTokens);
        if (bestSynset == null) {
            return null;
        }

        List<LangTransparency> scores = calculateTransparencyForSynset(targetLemma, bestSynset);

        return new DynamicResult(targetLemma, bestSynset, scores);
    }

    private List<LangTransparency> calculateTransparencyForSynset(String sourceLemma, ISynset synset) throws SQLException {
        String offsetStr = String.format("%08d", synset.getOffset());
        String posStr = convertPosToString(synset.getPOS());

        List<LangTransparency> results = new ArrayList<>();
        String sql = "SELECT lang, lemma FROM synset_lemmas WHERE synset_offset = ? AND pos = ?";

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setString(1, offsetStr);
            pstmt.setString(2, posStr);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String lang = rs.getString("lang");
                    String translatedLemma = rs.getString("lemma");

                    double transparency = computeNormalizedLevenshtein(sourceLemma, translatedLemma);
                    results.add(new LangTransparency(lang, translatedLemma, transparency));
                }
            }
        }

        results.sort(Comparator.comparingDouble((LangTransparency r) -> r.transparencyScore).reversed());
        return results;
    }

    private double computeNormalizedLevenshtein(String s1, String s2) {
        String a = s1.toLowerCase().trim();
        String b = s2.toLowerCase().trim();
        if (a.equals(b)) {
            return 1.0;
        }

        int[] costs = new int[b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else if (j > 0) {
                    int newValue = costs[j - 1];
                    if (a.charAt(i - 1) != b.charAt(j - 1)) {
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                    }
                    costs[j - 1] = lastValue;
                    lastValue = newValue;
                }
            }
            if (i > 0) {
                costs[b.length()] = lastValue;
            }
        }

        int maxLen = Math.max(a.length(), b.length());
        return maxLen == 0 ? 1.0 : 1.0 - ((double) costs[b.length()] / maxLen);
    }

    private String getLemma(String rawWord, POS pos) {
        if (rawWord == null) {
            return null;
        }
        String cleaned = rawWord.toLowerCase().trim().replace(" ", "_");
        List<String> stems = (pos != null) ? stemmer.findStems(cleaned, pos) : Collections.emptyList();
        return stems.isEmpty() ? cleaned : stems.get(0);
    }

    private String convertPosToString(POS pos) {
        if (pos == null) {
            return "n";
        }
        switch (pos) {
            case NOUN:
                return "n";
            case VERB:
                return "v";
            case ADJECTIVE:
                return "a";
            case ADVERB:
                return "r";
            default:
                return pos.getTag() + "";
        }
    }

    @Override
    public void close() throws Exception {
        try { disambiguator.close(); }
        finally {
            if (dict != null && dict.isOpen()) dict.close();
            if (sqliteConn != null && !sqliteConn.isClosed()) sqliteConn.close();
        }
    }

    public static void main(String[] args) throws Exception {
        try (DynamicTransparencyPipeline pipeline = new DynamicTransparencyPipeline(
                "dict", "omw/omw_multilingual.db", "stopwords/stopwords.txt")) {

            System.out.println("=== TEST 1: RIVA DEL FIUME ===");
            List<String> context1 = Arrays.asList("they", "walked", "along", "the", "river", "bank");
            var result1 = pipeline.analyzeAndCalculateTransparency("bank", POS.NOUN, context1);

            if (result1 == null) { System.out.println("Unresolved sense"); return; }
            System.out.println("Senso individuato: " + result1.selectedSynset.getGloss());
            result1.languageScores.forEach(score -> System.out.println(score));

            System.out.println("\n=== TEST 2: ISTITUTO FINANZIARIO ===");
            List<String> context2 = Arrays.asList("he", "deposited", "money", "in", "the", "bank", "account");
            var result2 = pipeline.analyzeAndCalculateTransparency("bank", POS.NOUN, context2);

            if (result2 == null) { System.out.println("Unresolved sense"); return; }
            System.out.println("Senso individuato: " + result2.selectedSynset.getGloss());
            result2.languageScores.forEach(score -> System.out.println(score));
        }
    }

}
