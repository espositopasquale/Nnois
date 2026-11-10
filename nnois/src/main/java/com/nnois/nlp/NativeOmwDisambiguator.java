package com.nnois.nlp;

import com.nnois.nlp.wsd.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public final class NativeOmwDisambiguator implements AutoCloseable {
    private final Connection conn;
    private final WsdSelection selection;
    private final OmwSenseModel suppliedModel;
    private OmwSenseModel englishModel;
    private OmwSenseModel multilingualModel;
    private WordNetRepository wordnet;
    private boolean closed;

    public NativeOmwDisambiguator(Connection conn) { this(conn, null, WsdSelection.configured()); }
    public NativeOmwDisambiguator(Connection conn, OmwSenseModel model) { this(conn, model, WsdSelection.configured()); }
    public NativeOmwDisambiguator(Connection conn, OmwSenseModel model, WsdSelection selection) {
        this.conn = Objects.requireNonNull(conn, "conn");
        this.suppliedModel = model;
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
        String normalizedLanguage = LanguageIdentification.normalizeCode(language);
        if (lemma == null || lemma.isBlank()
                || (stopwords != null && stopwords.contains(lemma.strip().toLowerCase(Locale.ROOT)))) {
            return new Result("unlinked", null, List.of());
        }
        Objects.requireNonNull(context, "Original context and target offsets are required");
        List<CandidateSynset> candidates = candidates(lemma, normalizedLanguage, preferredPos);
        if (candidates.isEmpty()) return new Result("unlinked", null, List.of());
        if (!"eng".equals(normalizedLanguage)) candidates = withEnglishGlossFallback(candidates);
        List<CandidateSynset> defined = candidates.stream().filter(c -> !c.gloss.isBlank()).toList();
        if (defined.isEmpty()) return new Result("missing-english-definition", null, List.of());
        try {
            OmwSenseModel activeModel = suppliedModel;
            if (activeModel == null && "eng".equals(normalizedLanguage)) {
                if (englishModel == null) englishModel = new GradedWsdModel(GradedWsdModel.configuredDirectory());
                activeModel = englishModel;
            } else if (activeModel == null) {
                if (multilingualModel == null) multilingualModel = new MultilingualE5Model();
                activeModel = multilingualModel;
            }
            double[] scores = activeModel.score(context, defined.stream().map(c -> c.gloss).toList());
            if (scores.length != defined.size()) throw new IllegalArgumentException("Invalid WSD score count");
            int selected = ("eng".equals(normalizedLanguage) || suppliedModel != null
                    ? selection : WsdSelection.multilingualConfigured()).select(scores);
            List<ScoredSense> ranked = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) ranked.add(new ScoredSense(defined.get(i), scores[i]));
            ranked.sort(Comparator.comparingDouble(ScoredSense::applicabilityScore).reversed()
                    .thenComparing(s -> s.candidate.offset).thenComparing(s -> s.candidate.pos));
            if (defined.size() != candidates.size()) return new Result("missing-english-definition", null, ranked);
            return new Result(selected < 0 ? "ambiguous" : "resolved", selected < 0 ? null : defined.get(selected), ranked);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(("eng".equals(normalizedLanguage) ? "graded-wsd" : "multilingual E5")
                    + " failed for '" + lemma + "': " + error.getMessage(), error);
        }
    }

    private List<CandidateSynset> withEnglishGlossFallback(List<CandidateSynset> candidates) {
        if (candidates.stream().allMatch(candidate -> !candidate.gloss.isBlank())) return candidates;
        try {
            WordNetRepository repository = wordnet();
            List<CandidateSynset> enriched = new ArrayList<>(candidates.size());
            for (CandidateSynset candidate : candidates) {
                String gloss = candidate.gloss.isBlank()
                        ? repository.gloss(candidate.offset, candidate.pos) : candidate.gloss;
                enriched.add(new CandidateSynset(candidate.offset, candidate.pos, gloss, candidate.matchedLemma));
            }
            return List.copyOf(enriched);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load English WordNet definitions: " + error.getMessage(), error);
        }
    }

    private WordNetRepository wordnet() throws java.io.IOException {
        if (wordnet == null) {
            var dictionary = WordNetRepository.configured();
            if (!dictionary.version().equals("3.0")) {
                dictionary.close();
                throw new IllegalArgumentException("OMW offset lookup requires Princeton WordNet 3.0");
            }
            wordnet = dictionary;
        }
        return wordnet;
    }

    private List<CandidateSynset> candidates(String lemma, String language, String preferredPos) throws SQLException {
        if (conn.isClosed()) throw new SQLException("OMW connection is closed");
        String pos = preferredPos == null || preferredPos.isBlank() ? null : preferredPos.strip().toLowerCase(Locale.ROOT);
        if ("s".equals(pos)) pos = "a";
        if (pos != null && !Set.of("n", "v", "a", "r").contains(pos)) {
            throw new IllegalArgumentException("Unsupported WordNet POS: " + preferredPos);
        }
        if (!"eng".equals(language)) {
            String sql = "SELECT l.synset_offset, l.pos, COALESCE(MIN(en.def), MIN(src.def), '') AS gloss "
                    + "FROM synset_lemmas l "
                    + "LEFT JOIN synset_def en ON en.synset_offset=l.synset_offset AND en.pos=l.pos AND en.lang='eng' "
                    + "LEFT JOIN synset_def src ON src.synset_offset=l.synset_offset AND src.pos=l.pos AND src.lang=l.lang "
                    + "WHERE l.lang=? AND LOWER(REPLACE(l.lemma, '_', ' '))=? "
                    + (pos == null ? "" : "AND l.pos=? ")
                    + "GROUP BY l.synset_offset, l.pos ORDER BY l.synset_offset, l.pos";
            List<CandidateSynset> result = new ArrayList<>();
            try (var statement = conn.prepareStatement(sql)) {
                statement.setString(1, language);
                statement.setString(2, lemma.strip().toLowerCase(Locale.ROOT).replace('_', ' '));
                if (pos != null) statement.setString(3, pos);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new CandidateSynset(rows.getString(1), rows.getString(2),
                            Objects.toString(rows.getString(3), ""), lemma));
                }
            }
            return List.copyOf(result);
        }
        try {
            wordnet();
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
        try {
            if (suppliedModel != null) suppliedModel.close();
            if (englishModel != null) englishModel.close();
            if (multilingualModel != null) multilingualModel.close();
        } finally { if (wordnet != null) wordnet.close(); }
    }
}
