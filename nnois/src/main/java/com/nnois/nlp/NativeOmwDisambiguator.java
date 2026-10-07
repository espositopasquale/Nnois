package com.nnois.nlp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NativeOmwDisambiguator {

    private final Connection conn;
    private final OmwSenseModel senseModel;
    private boolean modelUnavailable;

    public NativeOmwDisambiguator(Connection conn) {
        this(conn, OllamaSenseModel.configured());
    }

    public NativeOmwDisambiguator(Connection conn, OmwSenseModel senseModel) {
        this.conn = conn;
        this.senseModel = senseModel;
    }

    public static class CandidateSynset {

        public final String offset;
        public final String pos;
        public final String gloss;
        public final String matchedLemma;

        public CandidateSynset(String offset, String pos, String gloss, String matchedLemma) {
            this.offset = offset;
            this.pos = pos;
            this.gloss = gloss;
            this.matchedLemma = matchedLemma;
        }
    }

    public CandidateSynset disambiguateNative(String sourceLemma, String langCode, List<String> contextLemmas, Set<String> stopwords) throws SQLException {
        return disambiguateNative(sourceLemma, langCode, contextLemmas, stopwords, null);
    }

    public CandidateSynset disambiguateNative(String sourceLemma,
            String langCode,
            List<String> contextLemmas,
            Set<String> stopwords,
            String preferredPos) throws SQLException {

        if (sourceLemma == null || sourceLemma.isBlank() || stopwords.contains(sourceLemma.toLowerCase().trim())) {
            return null;
        }

        List<CandidateSynset> candidates = fetchCandidateSynsets(sourceLemma, langCode);

        if (candidates.isEmpty()) {
            return null;
        }

        String normalizedPreferredPos = normalizePos(preferredPos);
        if (normalizedPreferredPos != null) {
            List<CandidateSynset> filtered = candidates.stream()
                    .filter(candidate -> normalizedPreferredPos.equalsIgnoreCase(candidate.pos))
                    .collect(Collectors.toList());
            if (!filtered.isEmpty()) {
                candidates = filtered;
            }
        }

        String normalizedSource = sourceLemma == null ? "" : sourceLemma.trim();
        if (!normalizedSource.isEmpty() && normalizedSource.equals(normalizedSource.toLowerCase())) {
            List<CandidateSynset> commonNounLike = candidates.stream()
                    .filter(candidate -> candidate.matchedLemma != null)
                    .filter(candidate -> candidate.matchedLemma.equals(candidate.matchedLemma.toLowerCase()))
                    .collect(Collectors.toList());
            if (!commonNounLike.isEmpty()) {
                candidates = commonNounLike;
            }
        }

        String sourceLemmaNormalized = sourceLemma == null ? "" : sourceLemma.toLowerCase();

        Set<String> contextSet = contextLemmas.stream()
                .map(String::toLowerCase)
                .filter(w -> !w.equals(sourceLemmaNormalized))
                .filter(w -> w.length() > 2)
                .filter(w -> !stopwords.contains(w))
                .collect(Collectors.toSet());

        CandidateSynset bestSynset = chooseDeterministicFallback(candidates);
        if (senseModel != null && !modelUnavailable) {
            List<CandidateSynset> defined = candidates.stream()
                    .filter(c -> c.gloss != null && !c.gloss.isBlank()).toList();
            List<String> contentContext = contextLemmas.stream()
                    .filter(Stopwords::isWord).filter(w -> !stopwords.contains(w.toLowerCase())).toList();
            if (defined.size() > 1 && !contentContext.isEmpty()) {
                try {
                    double[] scores = senseModel.score(String.join(" ", contentContext), defined.stream()
                            .map(c -> Arrays.stream(c.gloss.split("(?U)\\W+"))
                                    .filter(Stopwords::isWord)
                                    .filter(w -> !stopwords.contains(w.toLowerCase()))
                                    .collect(Collectors.joining(" "))).toList());
                    if (scores.length != defined.size()) throw new IllegalArgumentException("Invalid sense score count");
                    double maxScore = -Double.MAX_VALUE;
                    for (int i = 0; i < scores.length; i++) {
                        if (!Double.isFinite(scores[i])) throw new IllegalArgumentException("Invalid sense score");
                        if (scores[i] > maxScore) { maxScore = scores[i]; bestSynset = defined.get(i); }
                    }
                    return bestSynset;
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    modelUnavailable = true;
                    System.err.println("[WSD] Embedding model unavailable; using lexical overlap: " + error.getMessage());
                }
            }
        }
        int maxOverlap = 0;

        for (CandidateSynset candidate : candidates) {
            if (candidate.gloss == null || candidate.gloss.isBlank()) {
                continue;
            }

            Set<String> glossWords = Arrays.stream(candidate.gloss.toLowerCase().split("(?U)\\W+"))
                    .filter(w -> w.length() > 2)
                    .filter(w -> !stopwords.contains(w))
                    .collect(Collectors.toSet());

            Set<String> overlap = new HashSet<>(glossWords);
            overlap.retainAll(contextSet);

            int score = overlap.size();
            if (score > maxOverlap) {
                maxOverlap = score;
                bestSynset = candidate;
            }
        }

        return bestSynset;
    }

    private String normalizePos(String pos) {
        if (pos == null || pos.isBlank()) {
            return null;
        }

        String lower = pos.toLowerCase();
        if (lower.equals("n") || lower.equals("v") || lower.equals("a") || lower.equals("s") || lower.equals("r")) {
            return lower.equals("s") ? "a" : lower;
        }
        return null;
    }

    private CandidateSynset chooseDeterministicFallback(List<CandidateSynset> candidates) {
        Comparator<CandidateSynset> byPreference = Comparator
                .comparingInt((CandidateSynset c) -> posPreference(c.pos))
                .thenComparing(c -> c.offset == null ? "" : c.offset)
                .thenComparing(c -> c.pos == null ? "" : c.pos);

        return candidates.stream().min(byPreference).orElse(candidates.get(0));
    }

    private int posPreference(String pos) {
        if (pos == null) {
            return 99;
        }

        return switch (pos.toLowerCase()) {
            case "n" ->
                0;
            case "v" ->
                1;
            case "a", "s" ->
                2;
            case "r" ->
                3;
            default ->
                99;
        };
    }

    private List<CandidateSynset> fetchCandidateSynsets(String lemma, String langCode) throws SQLException {
        List<CandidateSynset> candidates = new ArrayList<>();

        String sql = "SELECT DISTINCT l.synset_offset, l.pos, l.lemma, "
                + "COALESCE(d_local.def, d_en.def, d_eng.def, '') AS gloss "
                + "FROM synset_lemmas l "
                + "LEFT JOIN synset_def d_local ON l.synset_offset = d_local.synset_offset "
                + "AND l.pos = d_local.pos AND d_local.lang = l.lang "
                + "LEFT JOIN synset_def d_en ON l.synset_offset = d_en.synset_offset "
                + "AND l.pos = d_en.pos AND d_en.lang = 'en' "
                + "LEFT JOIN synset_def d_eng ON l.synset_offset = d_eng.synset_offset "
                + "AND l.pos = d_eng.pos AND d_eng.lang = 'eng' "
                + "WHERE LOWER(l.lemma) = ? AND l.lang = ? ORDER BY l.synset_offset, l.pos, gloss";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lemma.toLowerCase().trim());
            pstmt.setString(2, langCode.toLowerCase().trim());

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String offset = rs.getString("synset_offset");
                    String pos = rs.getString("pos");
                    String def = rs.getString("gloss");
                    String matchedLemma = rs.getString("lemma");

                    candidates.add(new CandidateSynset(offset, pos, def != null ? def : "", matchedLemma));
                }
            }
        }
        return candidates;
    }
}
