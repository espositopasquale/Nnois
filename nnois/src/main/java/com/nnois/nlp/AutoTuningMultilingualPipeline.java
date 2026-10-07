package com.nnois.nlp;

import java.io.InputStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nnois.utils.ResourceLoaderHelper;


public class AutoTuningMultilingualPipeline implements AutoCloseable {

    private static final Map<String, String> ISO3_TO_ISO2 = new HashMap<>();

    static {
        ISO3_TO_ISO2.put("eng", "en");
        ISO3_TO_ISO2.put("ita", "it");
        ISO3_TO_ISO2.put("fra", "fr");
        ISO3_TO_ISO2.put("deu", "de");
        ISO3_TO_ISO2.put("spa", "es");
        ISO3_TO_ISO2.put("por", "pt");
        ISO3_TO_ISO2.put("nld", "nl");
    }

    public enum AnalysisMode {
        CHOSEN_WORD,
        NOMINAL_AGGREGATE_SUM
    }

    public static class ContrastiveResult {

        public final AnalysisMode mode;
        public final String detectedLanguage;
        public final double languageConfidence;
        public final String chosenWord;
        public final int analyzedNominalUnits;
        public final OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate;
        public final List<NativeMultilingualPipeline.TransparencyResult> transparencyResults;
        public final List<NominalLanguageTransparency> nominalLanguageTransparencies;
        public final NativeOmwDisambiguator.CandidateSynset chosenSense;

        public ContrastiveResult(AnalysisMode mode,
                String detectedLanguage,
                double languageConfidence,
                String chosenWord,
                int analyzedNominalUnits,
                OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate,
                List<NativeMultilingualPipeline.TransparencyResult> transparencyResults,
                List<NominalLanguageTransparency> nominalLanguageTransparencies) {
            this(mode, detectedLanguage, languageConfidence, chosenWord, analyzedNominalUnits,
                    nominalAggregate, transparencyResults, nominalLanguageTransparencies, null);
        }

        public ContrastiveResult(AnalysisMode mode, String detectedLanguage, double languageConfidence,
                String chosenWord, int analyzedNominalUnits,
                OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate,
                List<NativeMultilingualPipeline.TransparencyResult> transparencyResults,
                List<NominalLanguageTransparency> nominalLanguageTransparencies,
                NativeOmwDisambiguator.CandidateSynset chosenSense) {
            this.mode = mode;
            this.detectedLanguage = detectedLanguage;
            this.languageConfidence = languageConfidence;
            this.chosenWord = chosenWord;
            this.analyzedNominalUnits = analyzedNominalUnits;
            this.nominalAggregate = nominalAggregate;
            this.transparencyResults = transparencyResults;
            this.nominalLanguageTransparencies = nominalLanguageTransparencies;
            this.chosenSense = chosenSense;
        }
    }

    public static class NominalLanguageTransparency {

        public final String nominalUnit;
        public final String unitType;
        public final String language;
        public final String lemma;
        public final double score;

        public NominalLanguageTransparency(String nominalUnit,
                String unitType,
                String language,
                String lemma,
                double score) {
            this.nominalUnit = nominalUnit;
            this.unitType = unitType;
            this.language = language;
            this.lemma = lemma;
            this.score = score;
        }
    }

    public static class AutoAnalysisResult {

        public final String detectedLanguage;
        public final double languageConfidence;
        public final OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate;
        public final List<NativeMultilingualPipeline.TransparencyResult> transparencyResults;

        public AutoAnalysisResult(String detectedLanguage,
                double languageConfidence,
                OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate,
                List<NativeMultilingualPipeline.TransparencyResult> transparencyResults) {
            this.detectedLanguage = detectedLanguage;
            this.languageConfidence = languageConfidence;
            this.nominalAggregate = nominalAggregate;
            this.transparencyResults = transparencyResults;
        }
    }

    public static class SenseTranslationResult {

        public final String sourceLemma;
        public final String sourceLanguage;
        public final String synsetOffset;
        public final String synsetPos;
        public final String gloss;
        public final List<NativeMultilingualPipeline.TransparencyResult> translations;

        public SenseTranslationResult(String sourceLemma,
                String sourceLanguage,
                String synsetOffset,
                String synsetPos,
                String gloss,
                List<NativeMultilingualPipeline.TransparencyResult> translations) {
            this.sourceLemma = sourceLemma;
            this.sourceLanguage = sourceLanguage;
            this.synsetOffset = synsetOffset;
            this.synsetPos = synsetPos;
            this.gloss = gloss;
            this.translations = translations;
        }
    }

    private static class SenseCandidate {

        private final String synsetOffset;
        private final String synsetPos;
        private final String gloss;

        private SenseCandidate(String synsetOffset, String synsetPos, String gloss) {
            this.synsetOffset = synsetOffset;
            this.synsetPos = synsetPos;
            this.gloss = gloss;
        }
    }

    private final Connection sqliteConn;
    private final LanguageIdentification languageIdentification;
    private final Map<String, NativeLanguageNLP> nlpCache = new HashMap<>();
    private final Map<String, Set<String>> stopwordsCache = new HashMap<>();

    public AutoTuningMultilingualPipeline(String sqliteDbPath, String langDetectorModelPath) throws Exception {

        this.languageIdentification = new LanguageIdentification(langDetectorModelPath);
        ResourceLoaderHelper resourceLoader = new ResourceLoaderHelper();
        this.sqliteConn = resourceLoader.loadSqliteFromResources(sqliteDbPath);

    }

    /** Owns the supplied connection; callers can share the CLI engine with an HTTP service. */
    public AutoTuningMultilingualPipeline(Connection connection, String langDetectorModelPath) throws Exception {
        this.languageIdentification = new LanguageIdentification(langDetectorModelPath);
        this.sqliteConn = java.util.Objects.requireNonNull(connection);
    }

    public LanguageIdentification.Detection detectLanguage(String text) {
        return languageIdentification.detect(text);
    }

    public List<NativeMultilingualPipeline.TransparencyResult> processAutoDetectedSentence(
            String rawSentence, String targetWord) throws Exception {

        var predicted = detectLanguage(rawSentence);
        String detectedLangCode = resolveOmwLangCode(predicted.code());
        double confidence = predicted.confidence();



        NativeLanguageNLP nativeNlp = getOrCreateLanguageNlp(detectedLangCode);
        Set<String> stopwords = getOrCreateStopwords(detectedLangCode);

        List<String> tokens = nativeNlp.tokenize(rawSentence);
        List<String> lemmatizedContext = nativeNlp.lemmatizeTokens(tokens);
        String preferredPos = nativeNlp.inferPreferredOmwPos(rawSentence, targetWord);

        NativeOmwDisambiguator disambiguator = new NativeOmwDisambiguator(sqliteConn);
        var bestSynset = disambiguator.disambiguateNative(
                targetWord,
                detectedLangCode,
                lemmatizedContext,
                stopwords,
                preferredPos);

        if (bestSynset == null) {
            System.err.printf("[AutoPipeline] No candidate synsets found for '%s' in language '%s'.%n", targetWord,
                    detectedLangCode);
            return Collections.emptyList();
        }

        System.out.println("  OMW sense   " + bestSynset.offset + "-" + bestSynset.pos);
        System.out.println("  Definition  " + bestSynset.gloss);

        return fetchAllLanguagesAndCalculateTransparency(targetWord, bestSynset.offset, bestSynset.pos);
    }

    public AutoAnalysisResult processAutoDetectedSentenceWithNominalAggregate(
            String rawSentence, String targetWord) throws Exception {

        var predicted = detectLanguage(rawSentence);
        String detectedLangCode = resolveOmwLangCode(predicted.code());
        double confidence = predicted.confidence();



        OpenNLPMWEExtractor nominalExtractor = buildNominalExtractorForLanguage(detectedLangCode);
        OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate = nominalExtractor
                .analyzeNominalAggregateScore(rawSentence, detectedLangCode, sqliteConn, getOrCreateStopwords(detectedLangCode));

        List<NativeMultilingualPipeline.TransparencyResult> transparencyResults
                = processDetectedLanguageSentence(rawSentence, targetWord, detectedLangCode);

        return new AutoAnalysisResult(detectedLangCode, confidence, nominalAggregate, transparencyResults);
    }

    public ContrastiveResult processContrastive(String rawSentence, String targetWord) throws Exception {
        return processContrastive(rawSentence, targetWord, null, true);
    }

    public ContrastiveResult processContrastive(String rawSentence,
            String targetWord,
            String languageHint,
            boolean autoDetectLanguage) throws Exception {

        String selectedLangCode;
        double confidence;

        if (autoDetectLanguage) {
            var predicted = detectLanguage(rawSentence);
            selectedLangCode = resolveOmwLangCode(predicted.code());
            confidence = predicted.confidence();

        } else {
            String manual = languageHint == null ? "" : languageHint.trim();
            if (manual.isEmpty()) {
                var predicted = detectLanguage(rawSentence);
                selectedLangCode = resolveOmwLangCode(predicted.code());
                confidence = predicted.confidence();

            } else {
                selectedLangCode = LanguageIdentification.normalizeCode(manual);
                if (!LanguageIdentification.supported(selectedLangCode)) {
                    throw new IllegalArgumentException("Unsupported analysis language: " + manual);
                }
                confidence = 1.0;

            }
        }

        String omwLangCode = resolveOmwLangCode(selectedLangCode);

        OpenNLPMWEExtractor nominalExtractor = buildNominalExtractorForLanguage(selectedLangCode);
        OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate = nominalExtractor
                .analyzeNominalAggregateScore(rawSentence, omwLangCode, sqliteConn, getOrCreateStopwords(omwLangCode));

        if (targetWord != null && !targetWord.trim().isEmpty()) {
            ContextualAnalysis contextual = analyzeWordInContext(rawSentence, targetWord.trim(), omwLangCode);

            return new ContrastiveResult(
                    AnalysisMode.CHOSEN_WORD,
                    omwLangCode,
                    confidence,
                    targetWord.trim(),
                    0,
                    nominalAggregate,
                    contextual.translations(),
                    Collections.emptyList(),
                    contextual.sense());
        }

        AggregateNominalTransparency aggregate
                = aggregateNominalTransparencyAcrossLanguages(rawSentence, selectedLangCode, nominalAggregate);

        return new ContrastiveResult(
                AnalysisMode.NOMINAL_AGGREGATE_SUM,
                omwLangCode,
                confidence,
                null,
                aggregate.analyzedNominalUnits,
                nominalAggregate,
                aggregate.results,
                aggregate.details);
    }

    public List<SenseTranslationResult> lookupWordSensesAndTranslations(String sourceWord, String languageHint)
            throws Exception {
        if (sourceWord == null || sourceWord.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String sourceLemma = sourceWord.trim().toLowerCase();
        String hinted = LanguageIdentification.normalizeCode(languageHint);
        if (hinted.isEmpty()) {
            hinted = detectLanguage(sourceLemma).code();
        }

        String resolvedLang = resolveOmwLangCode(hinted);
        List<SenseCandidate> candidates = fetchSenseCandidates(sourceLemma, resolvedLang);

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<SenseTranslationResult> results = new ArrayList<>();
        for (SenseCandidate candidate : candidates) {
            List<NativeMultilingualPipeline.TransparencyResult> translations
                    = fetchAllLanguagesAndCalculateTransparency(sourceLemma, candidate.synsetOffset, candidate.synsetPos);
            results.add(new SenseTranslationResult(
                    sourceLemma,
                    resolvedLang,
                    candidate.synsetOffset,
                    candidate.synsetPos,
                    candidate.gloss,
                    translations));
        }

        return results;
    }

    private List<SenseCandidate> fetchSenseCandidates(String sourceLemma, String languageCode) throws Exception {
        String sql = "SELECT l.synset_offset, l.pos, COALESCE(d.def, '') AS gloss "
                + "FROM synset_lemmas l "
                + "LEFT JOIN synset_def d ON l.synset_offset = d.synset_offset AND l.pos = d.pos AND d.lang = l.lang "
                + "WHERE LOWER(l.lemma) = ? AND l.lang = ? "
                + "ORDER BY l.synset_offset, l.pos";

        List<SenseCandidate> candidates = new ArrayList<>();
        try (var pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setString(1, sourceLemma.toLowerCase());
            pstmt.setString(2, languageCode.toLowerCase());

            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new SenseCandidate(
                            rs.getString("synset_offset"),
                            rs.getString("pos"),
                            rs.getString("gloss")));
                }
            }
        }

        return candidates;
    }

    private static class AggregateNominalTransparency {

        private final int analyzedNominalUnits;
        private final List<NativeMultilingualPipeline.TransparencyResult> results;
        private final List<NominalLanguageTransparency> details;

        private AggregateNominalTransparency(int analyzedNominalUnits,
                List<NativeMultilingualPipeline.TransparencyResult> results,
                List<NominalLanguageTransparency> details) {
            this.analyzedNominalUnits = analyzedNominalUnits;
            this.results = results;
            this.details = details;
        }
    }

    private AggregateNominalTransparency aggregateNominalTransparencyAcrossLanguages(
            String rawSentence,
            String detectedLangCode,
            OpenNLPMWEExtractor.NominalAggregateResult nominalAggregate) throws Exception {

        NativeLanguageNLP nativeNlp = getOrCreateLanguageNlp(detectedLangCode);
        Set<String> stopwords = getOrCreateStopwords(detectedLangCode);

        List<String> tokens = nativeNlp.tokenize(rawSentence);
        List<String> lemmatizedContext = nativeNlp.lemmatizeTokens(tokens);

        NativeOmwDisambiguator disambiguator = new NativeOmwDisambiguator(sqliteConn);

        Map<String, Double> languageTotals = new HashMap<>();
        List<NominalLanguageTransparency> details = new ArrayList<>();
        int analyzedUnits = 0;

        for (OpenNLPMWEExtractor.NominalUnitScore unit : nominalAggregate.units) {
            var bestSynset = disambiguator.disambiguateNative(unit.lemma, resolveOmwLangCode(detectedLangCode),
                    lemmatizedContext, stopwords, "n");
            if (bestSynset == null) {
                continue;
            }

            analyzedUnits++;

            List<NativeMultilingualPipeline.TransparencyResult> unitResults
                    = fetchAllLanguagesAndCalculateTransparency(unit.lemma, bestSynset.offset, bestSynset.pos);

            Map<String, Double> bestPerLanguage = bestLanguageScores(unitResults);
            for (NativeMultilingualPipeline.TransparencyResult unitResult : unitResults) {
                details.add(new NominalLanguageTransparency(
                        unit.lemma,
                        unit.unitType,
                        unitResult.lang,
                        unitResult.lemma,
                        unitResult.score));
            }
            bestPerLanguage.forEach((language, score) -> languageTotals.merge(language, score, Double::sum));
        }

        List<NativeMultilingualPipeline.TransparencyResult> aggregateResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : languageTotals.entrySet()) {
            double normalizedScore = nominalAggregate.totalNominalUnits > 0
                    ? entry.getValue() / nominalAggregate.totalNominalUnits : 0.0;
            aggregateResults.add(new NativeMultilingualPipeline.TransparencyResult(
                    entry.getKey(),
                    "MEAN_NOMINAL_UNITS",
                    normalizedScore));
        }

        aggregateResults.sort(Comparator.comparingDouble((NativeMultilingualPipeline.TransparencyResult r) -> r.score)
                .reversed());
        details.sort(Comparator.comparing((NominalLanguageTransparency detail) -> detail.nominalUnit)
                .thenComparing(detail -> detail.language)
                .thenComparing(Comparator.comparingDouble((NominalLanguageTransparency detail) -> detail.score)
                        .reversed()));

        return new AggregateNominalTransparency(analyzedUnits, aggregateResults, details);
    }

    static Map<String, Double> bestLanguageScores(List<NativeMultilingualPipeline.TransparencyResult> translations) {
        Map<String, Double> scores = new HashMap<>();
        for (var translation : translations) scores.merge(translation.lang, translation.score, Math::max);
        return scores;
    }

    private List<NativeMultilingualPipeline.TransparencyResult> processDetectedLanguageSentence(
            String rawSentence, String targetWord, String detectedLangCode) throws Exception {
        return analyzeWordInContext(rawSentence, targetWord, detectedLangCode).translations();
    }

    private record ContextualAnalysis(NativeOmwDisambiguator.CandidateSynset sense,
            List<NativeMultilingualPipeline.TransparencyResult> translations) { }

    private ContextualAnalysis analyzeWordInContext(String rawSentence, String targetWord, String detectedLangCode)
            throws Exception {

        NativeLanguageNLP nativeNlp = getOrCreateLanguageNlp(detectedLangCode);
        Set<String> stopwords = getOrCreateStopwords(detectedLangCode);

        List<String> tokens = nativeNlp.tokenize(rawSentence);
        List<String> lemmatizedContext = nativeNlp.lemmatizeTokens(tokens);

        NativeOmwDisambiguator disambiguator = new NativeOmwDisambiguator(sqliteConn);
        var bestSynset = disambiguator.disambiguateNative(targetWord, detectedLangCode, lemmatizedContext, stopwords,
                nativeNlp.inferPreferredOmwPos(rawSentence, targetWord));

        if (bestSynset == null) {
            System.err.printf("[AutoPipeline] No candidate synsets found for '%s' in language '%s'.%n", targetWord,
                    detectedLangCode);
            return new ContextualAnalysis(null, Collections.emptyList());
        }

        System.out.println("  OMW sense   " + bestSynset.offset + "-" + bestSynset.pos);
        System.out.println("  Definition  " + bestSynset.gloss);

        return new ContextualAnalysis(bestSynset,
                fetchAllLanguagesAndCalculateTransparency(targetWord, bestSynset.offset, bestSynset.pos));
    }

    private OpenNLPMWEExtractor buildNominalExtractorForLanguage(String langCode) {
        requireSupportedLanguage(langCode);
        String openNlpLangCode = toOpenNlpLanguageCode(langCode);
        String tokenModel = String.format("opennlp-%s-token.bin", openNlpLangCode);
        String posModel = String.format("opennlp-%s-pos.bin", openNlpLangCode);
        String chunkerModel = String.format("opennlp-%s-chunker.bin", openNlpLangCode);
        String lemmaDict = String.format("%s-lemmatizer.dict", openNlpLangCode);

        return new OpenNLPMWEExtractor(tokenModel, posModel, chunkerModel, lemmaDict);
    }

    private NativeLanguageNLP getOrCreateLanguageNlp(String langCode) throws Exception {
        requireSupportedLanguage(langCode);
        String openNlpLangCode = toOpenNlpLanguageCode(langCode);

        if (nlpCache.containsKey(openNlpLangCode)) {
            return nlpCache.get(openNlpLangCode);
        }

        String tokenizerModel = String.format("opennlp-%s-token.bin", openNlpLangCode);
        String posModel = String.format("opennlp-%s-pos.bin", openNlpLangCode);
        String lemmaDict = String.format("%s-lemmatizer.dict", openNlpLangCode);


        NativeLanguageNLP nlp = new NativeLanguageNLP(tokenizerModel, posModel, lemmaDict);

        nlpCache.put(openNlpLangCode, nlp);
        return nlp;
    }

    private static void requireSupportedLanguage(String langCode) {
        if (!LanguageIdentification.supported(langCode)) {
            throw new IllegalArgumentException("No bundled NLP models for " + langCode
                    + ". Choose en, it, fr, de, es, pt or nl.");
        }
    }

    private String toOpenNlpLanguageCode(String langCode) {
        if (langCode == null) {
            return "en";
        }
        String lower = langCode.toLowerCase();
        return ISO3_TO_ISO2.getOrDefault(lower, lower);
    }

    private String toIso3LanguageCode(String langCode) {
        if (langCode == null) {
            return "eng";
        }

        String lower = langCode.toLowerCase();
        if (lower.length() == 3) {
            return lower;
        }

        for (Map.Entry<String, String> entry : ISO3_TO_ISO2.entrySet()) {
            if (entry.getValue().equals(lower)) {
                return entry.getKey();
            }
        }

        return lower;
    }

    private String resolveOmwLangCode(String detectedLangCode) {
        String lower = detectedLangCode == null ? "" : detectedLangCode.toLowerCase();
        String iso2 = toOpenNlpLanguageCode(lower);
        String iso3 = toIso3LanguageCode(lower);

        if (omwLanguageExists(lower)) {
            return lower;
        }
        if (omwLanguageExists(iso3)) {
            return iso3;
        }
        if (omwLanguageExists(iso2)) {
            return iso2;
        }
        return lower;
    }

    private boolean omwLanguageExists(String langCode) {
        if (langCode == null || langCode.isBlank()) {
            return false;
        }

        String sql = "SELECT 1 FROM synset_lemmas WHERE lang = ? LIMIT 1";
        try (var pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setString(1, langCode.toLowerCase());
            try (var rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private Set<String> getOrCreateStopwords(String langCode) {
        return stopwordsCache.computeIfAbsent(langCode, code -> {
            String path = String.format("stopwords-%s.txt", code);
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    Set<String> set = Stopwords.forLanguage(code);
                    try (var reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim().toLowerCase();
                            if (!line.isEmpty() && !line.startsWith("#")) {
                                set.add(line);
                            }
                        }
                    }
                    return set;
                }
            } catch (Exception ignored) {
            }
            return Stopwords.forLanguage(code);
        });
    }

    private List<NativeMultilingualPipeline.TransparencyResult> fetchAllLanguagesAndCalculateTransparency(
            String sourceWord, String offset, String pos) throws Exception {

        List<NativeMultilingualPipeline.TransparencyResult> results = new ArrayList<>();
        String sql = "SELECT lang, lemma FROM synset_lemmas WHERE synset_offset = ? AND pos = ?";

        try (var pstmt = sqliteConn.prepareStatement(sql)) {
            pstmt.setString(1, offset);
            pstmt.setString(2, pos);

            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String lang = rs.getString("lang");
                    String targetLemma = rs.getString("lemma");

                    double transparency = computeNormalizedLevenshtein(sourceWord, targetLemma);
                    results.add(new NativeMultilingualPipeline.TransparencyResult(lang, targetLemma, transparency));
                }
            }
        }

        results.sort(
                Comparator.comparingDouble((NativeMultilingualPipeline.TransparencyResult r) -> r.score).reversed());
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

    @Override
    public void close() throws Exception {
        if (sqliteConn != null && !sqliteConn.isClosed()) {
            sqliteConn.close();
        }
    }
}
