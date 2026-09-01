package com.nnois.nlp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NativeMultilingualPipeline implements AutoCloseable {

    private final Connection conn;
    private final NativeLanguageNLP sourceNlp;
    private final NativeOmwDisambiguator disambiguator;
    private final Set<String> stopWords;

    public NativeMultilingualPipeline(String sqliteDbPath,
            String tokenizerModel,
            String posModel,
            String lemmaDict,
            String stopwordsPath) throws Exception {

        String url = "jdbc:sqlite:" + sqliteDbPath;
        this.conn = DriverManager.getConnection(url);

        this.sourceNlp = new NativeLanguageNLP(tokenizerModel, posModel, lemmaDict);
        this.disambiguator = new NativeOmwDisambiguator(this.conn);
        this.stopWords = loadStopwords(stopwordsPath);
    }

    public static class TransparencyResult {

        public final String lang;
        public final String lemma;
        public final double score;

        public TransparencyResult(String lang, String lemma, double score) {
            this.lang = lang;
            this.lemma = lemma;
            this.score = score;
        }

        @Override
        public String toString() {
            return String.format("Language: %-5s | Lemma: %-20s | Transparency: %.2f", lang, lemma, score);
        }
    }

    public List<TransparencyResult> analyzeNativeAndCalculateTransparency(String rawSentence, String targetWord, String sourceLang) throws Exception {

        List<String> tokens = sourceNlp.tokenize(rawSentence);
        List<String> lemmatizedContext = sourceNlp.lemmatizeTokens(tokens);

        var bestSynset = disambiguator.disambiguateNative(targetWord, sourceLang, lemmatizedContext, stopWords);

        if (bestSynset == null) {
            System.err.println("No matching senses found in OMW for '" + targetWord + "' in language '" + sourceLang + "'.");
            return Collections.emptyList();
        }

        System.out.println("Disambiguated Synset Offset : " + bestSynset.offset + "-" + bestSynset.pos);
        System.out.println("Native Definition          : " + bestSynset.gloss);

        return fetchAllLanguagesAndCalculateTransparency(targetWord, bestSynset.offset, bestSynset.pos);
    }

    private List<TransparencyResult> fetchAllLanguagesAndCalculateTransparency(String sourceWord, String offset, String pos) throws SQLException {
        List<TransparencyResult> results = new ArrayList<>();
        String sql = "SELECT lang, lemma FROM synset_lemmas WHERE synset_offset = ? AND pos = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, offset);
            pstmt.setString(2, pos);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String lang = rs.getString("lang");
                    String translatedLemma = rs.getString("lemma");

                    double transparency = computeNormalizedLevenshtein(sourceWord, translatedLemma);
                    results.add(new TransparencyResult(lang, translatedLemma, transparency));
                }
            }
        }

        results.sort(Comparator.comparingDouble((TransparencyResult r) -> r.score).reversed());
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

    private Set<String> loadStopwords(String resourcePath) throws Exception {
        Set<String> words = new HashSet<>();
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        words.add(line);
                    }
                }
            }
        }
        return words;
    }

    @Override
    public void close() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
