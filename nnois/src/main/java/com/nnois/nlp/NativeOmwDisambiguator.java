package com.nnois.nlp;

import com.nnois.nlp.wsd.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public final class NativeOmwDisambiguator implements AutoCloseable {
    private final Connection conn;
    private final WsdSelection selection;
    private OmwSenseModel senseModel;
    private WordNetRepository wordnet;
    private boolean closed;

    public NativeOmwDisambiguator(Connection conn) { this(conn, null, WsdSelection.configured()); }
    public NativeOmwDisambiguator(Connection conn, OmwSenseModel model) { this(conn, model, WsdSelection.configured()); }
    public NativeOmwDisambiguator(Connection conn, OmwSenseModel model, WsdSelection selection) {
        this.conn = Objects.requireNonNull(conn, "conn");
        this.senseModel = model;
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public static final class CandidateSynset {
        public final String offset;
        public final String pos;
        public final String gloss;
        public final String matchedLemma;
        public CandidateSynset(String offset, String pos, String gloss, String matchedLemma) {
            this.offset = offset; this.pos = pos; this.gloss = gloss; this.matchedLemma = matchedLemma;
        }
    }

    public record ScoredSense(CandidateSynset candidate, double applicabilityScore) { }
    public record Result(String status, CandidateSynset selected, List<ScoredSense> candidates) {
        public Result { candidates = List.copyOf(candidates); }
    }

    public CandidateSynset disambiguateNative(String lemma, String language, OccurrenceContext context,
            Set<String> stopwords, String preferredPos) throws SQLException {
        return analyze(lemma, language, context, stopwords, preferredPos).selected();
    }

    public Result analyze(String lemma, String language, OccurrenceContext context,
            Set<String> stopwords, String preferredPos) throws SQLException {
        if (closed) throw new IllegalStateException("WSD disambiguator is closed");
        WsdSelection.requireEnglish(language);
        if (lemma == null || lemma.isBlank()
                || (stopwords != null && stopwords.contains(lemma.strip().toLowerCase(Locale.ROOT)))) {
            return new Result("unlinked", null, List.of());
        }
        Objects.requireNonNull(context, "Original context and target offsets are required");
        List<CandidateSynset> candidates = candidates(lemma, preferredPos);
        if (candidates.isEmpty()) return new Result("unlinked", null, List.of());
        List<CandidateSynset> defined = candidates.stream().filter(c -> !c.gloss.isBlank()).toList();
        if (defined.isEmpty()) return new Result("missing-english-definition", null, List.of());
        try {
            if (senseModel == null) senseModel = new GradedWsdModel(GradedWsdModel.configuredDirectory());
            double[] scores = senseModel.score(context, defined.stream().map(c -> c.gloss).toList());
            if (scores.length != defined.size()) throw new IllegalArgumentException("Invalid WSD score count");
            int selected = selection.select(scores);
            List<ScoredSense> ranked = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) ranked.add(new ScoredSense(defined.get(i), scores[i]));
            ranked.sort(Comparator.comparingDouble(ScoredSense::applicabilityScore).reversed()
                    .thenComparing(s -> s.candidate.offset).thenComparing(s -> s.candidate.pos));
            if (defined.size() != candidates.size()) return new Result("missing-english-definition", null, ranked);
            return new Result(selected < 0 ? "ambiguous" : "resolved", selected < 0 ? null : defined.get(selected), ranked);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("graded-wsd failed for '" + lemma + "': " + error.getMessage(), error);
        }
    }

    private List<CandidateSynset> candidates(String lemma, String preferredPos) throws SQLException {
        if (conn.isClosed()) throw new SQLException("OMW connection is closed");
        String pos = preferredPos == null || preferredPos.isBlank() ? null : preferredPos.strip().toLowerCase(Locale.ROOT);
        if ("s".equals(pos)) pos = "a";
        if (pos != null && !Set.of("n", "v", "a", "r").contains(pos)) {
            throw new IllegalArgumentException("Unsupported WordNet POS: " + preferredPos);
        }
        try {
            if (wordnet == null) {
                var dictionary = WordNetRepository.configured();
                if (!dictionary.version().equals("3.0")) {
                    dictionary.close();
                    throw new IllegalArgumentException("OMW offset lookup requires Princeton WordNet 3.0");
                }
                wordnet = dictionary;
            }
            var result = new LinkedHashMap<String, CandidateSynset>();
            List<String> tags = pos == null ? List.of("NN", "VB", "JJ", "RB") : List.of(switch (pos) {
                case "n" -> "NN"; case "v" -> "VB"; case "a" -> "JJ"; default -> "RB";
            });
            for (String tag : tags) {
                for (var sense : wordnet.candidates(lemma, tag)) {
                    var synset = sense.synset();
                    String offset = String.format(Locale.ROOT, "%08d", synset.getOffset());
                    String candidatePos = String.valueOf(synset.getPOS().getTag());
                    result.putIfAbsent(offset + ":" + candidatePos,
                            new CandidateSynset(offset, candidatePos, synset.getGloss(), lemma));
                }
            }
            return List.copyOf(result.values());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load English WordNet: " + error.getMessage(), error);
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        try { if (senseModel != null) senseModel.close(); }
        finally { if (wordnet != null) wordnet.close(); }
    }
}
