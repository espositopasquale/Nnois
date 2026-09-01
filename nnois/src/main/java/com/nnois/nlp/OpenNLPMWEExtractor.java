package com.nnois.nlp;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import edu.mit.jwi.item.POS;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;

public class OpenNLPMWEExtractor {

    public static class NominalUnitScore {

        public final String lemma;
        public final String unitType;
        public final double score;

        public NominalUnitScore(String lemma, String unitType, double score) {
            this.lemma = lemma;
            this.unitType = unitType;
            this.score = score;
        }
    }

    public static class NominalAggregateResult {

        public final double totalAggregateScore;
        public final int totalNominalUnits;
        public final List<NominalUnitScore> units;

        public NominalAggregateResult(double totalAggregateScore, int totalNominalUnits, List<NominalUnitScore> units) {
            this.totalAggregateScore = totalAggregateScore;
            this.totalNominalUnits = totalNominalUnits;
            this.units = units;
        }
    }

    private TokenizerME tokenizer;
    private POSTaggerME posTagger;
    private ChunkerME chunker;
    private DictionaryLemmatizer lemmatizer;

    public OpenNLPMWEExtractor(String tokenModelPath, String posModelPath, String chunkerModelPath,
            String lemmaDictPath) {
        try (InputStream tokenIs = getClass().getClassLoader().getResourceAsStream(tokenModelPath); InputStream posIs = getClass().getClassLoader().getResourceAsStream(posModelPath); InputStream chunkerIs = getClass().getClassLoader().getResourceAsStream(chunkerModelPath); InputStream lemmaIs = getClass().getClassLoader().getResourceAsStream(lemmaDictPath)) {

            if (tokenIs != null) {
                this.tokenizer = new TokenizerME(new TokenizerModel(tokenIs));
            }
            if (posIs != null) {
                this.posTagger = new POSTaggerME(new POSModel(posIs));
            }
            if (chunkerIs != null) {
                this.chunker = new ChunkerME(new ChunkerModel(chunkerIs));
            }
            if (lemmaIs != null) {
                this.lemmatizer = new DictionaryLemmatizer(lemmaIs);
            }

        } catch (Exception e) {
            System.err.println("[NNois Error] Errore nel caricamento dei modelli OpenNLP: " + e.getMessage());
        }
    }

    public List<String> tokenizeAndValidateMWE(String text, String langCode, Connection sqliteConn) {
        List<String> finalTokens = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return finalTokens;
        }

        String[] tokens = tokenizer.tokenize(text);

        String[] posTags = posTagger.tag(tokens);

        String[] lemmas = (lemmatizer != null) ? lemmatizer.lemmatize(tokens, posTags) : tokens;
        for (int i = 0; i < lemmas.length; i++) {
            if ("O".equals(lemmas[i])) {
                lemmas[i] = tokens[i].toLowerCase(); 
            }else {
                lemmas[i] = lemmas[i].toLowerCase();
            }
        }

        Span[] chunkSpans = (chunker != null) ? chunker.chunkAsSpans(tokens, posTags) : new Span[0];

        int lastIndex = 0;

        for (Span span : chunkSpans) {
            for (int i = lastIndex; i < span.getStart(); i++) {
                finalTokens.add(lemmas[i]);
            }

            if (span.length() > 1 && "NP".equalsIgnoreCase(span.getType())) {
                StringBuilder mweBuilder = new StringBuilder();
                for (int i = span.getStart(); i < span.getEnd(); i++) {
                    mweBuilder.append(lemmas[i]);
                    if (i < span.getEnd() - 1) {
                        mweBuilder.append("_");
                    }
                }

                String candidateMwe = mweBuilder.toString();

                if (isMweInOmw(candidateMwe, langCode, sqliteConn)) {
                    finalTokens.add(candidateMwe);
                } else {
                    for (int i = span.getStart(); i < span.getEnd(); i++) {
                        finalTokens.add(lemmas[i]);
                    }
                }
            } else {
                for (int i = span.getStart(); i < span.getEnd(); i++) {
                    finalTokens.add(lemmas[i]);
                }
            }

            lastIndex = span.getEnd();
        }

        for (int i = lastIndex; i < tokens.length; i++) {
            finalTokens.add(lemmas[i]);
        }

        return finalTokens;
    }

    public NominalAggregateResult analyzeNominalAggregateScore(String text, String langCode, Connection sqliteConn) {
        List<NominalUnitScore> units = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return new NominalAggregateResult(0.0, 0, units);
        }

        String[] tokens = (tokenizer != null) ? tokenizer.tokenize(text) : text.trim().split("\\s+");
        String[] posTags = (posTagger != null) ? posTagger.tag(tokens) : new String[tokens.length];
        String[] lemmas = (lemmatizer != null && posTagger != null) ? lemmatizer.lemmatize(tokens, posTags) : tokens;

        for (int i = 0; i < lemmas.length; i++) {
            if ("O".equals(lemmas[i])) {
                lemmas[i] = tokens[i].toLowerCase();
            } else {
                lemmas[i] = lemmas[i].toLowerCase();
            }
        }

        Span[] chunkSpans = (chunker != null && posTagger != null) ? chunker.chunkAsSpans(tokens, posTags) : new Span[0];
        boolean[] consumed = new boolean[tokens.length];

        for (Span span : chunkSpans) {
            if (span.length() <= 1 || !"NP".equalsIgnoreCase(span.getType())) {
                continue;
            }

            StringBuilder mweBuilder = new StringBuilder();
            for (int i = span.getStart(); i < span.getEnd(); i++) {
                mweBuilder.append(lemmas[i]);
                if (i < span.getEnd() - 1) {
                    mweBuilder.append("_");
                }
            }

            String candidateMwe = mweBuilder.toString();
            if (isMweInOmw(candidateMwe, langCode, sqliteConn)) {
                units.add(new NominalUnitScore(candidateMwe, "NOMINAL_MWE", 1.0));
                for (int i = span.getStart(); i < span.getEnd(); i++) {
                    consumed[i] = true;
                }
            }
        }

        for (int i = 0; i < tokens.length; i++) {
            if (consumed[i]) {
                continue;
            }

            POS wordNetPos = PosMapper.mapToWordNetPOS(posTags[i]);
            if (wordNetPos == POS.NOUN || isNominalLemmaInOmw(lemmas[i], langCode, sqliteConn)) {
                units.add(new NominalUnitScore(lemmas[i], "NOMINAL_TOKEN", 1.0));
            }
        }

        double total = 0.0;
        for (NominalUnitScore unit : units) {
            total += unit.score;
        }

        return new NominalAggregateResult(total, units.size(), units);
    }

    private boolean isNominalLemmaInOmw(String lemma, String langCode, Connection conn) {
        if (lemma == null || lemma.isBlank() || conn == null) {
            return false;
        }

        String sql = "SELECT 1 FROM synset_lemmas WHERE LOWER(lemma) = ? AND lang = ? AND pos = 'n' LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lemma.toLowerCase());
            pstmt.setString(2, langCode.toLowerCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isMweInOmw(String mweLemma, String langCode, Connection conn) {
        if (conn == null) {
            return true;
        }

        String sql = "SELECT 1 FROM synset_lemmas WHERE LOWER(lemma) = ? AND lang = ? LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, mweLemma.toLowerCase());
            pstmt.setString(2, langCode.toLowerCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
}
