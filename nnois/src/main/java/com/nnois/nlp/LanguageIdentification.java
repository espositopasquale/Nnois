package com.nnois.nlp;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import opennlp.tools.langdetect.LanguageDetectorME;
import opennlp.tools.langdetect.LanguageDetectorModel;

/** Shared language detection with alternatives and explicit uncertainty. */
public final class LanguageIdentification {
    private static final Map<String, String> NAMES = Map.of("eng", "English", "ita", "Italian",
            "fra", "French", "deu", "German", "spa", "Spanish", "por", "Portuguese", "nld", "Dutch");
    private final LanguageDetectorME detector;
    public record Candidate(String code, double confidence) { }
    public record Detection(String code, double confidence, List<Candidate> alternatives,
            boolean uncertain, int wordCount, boolean mixedLanguage) {
        public Detection(String code, double confidence, List<Candidate> alternatives, boolean uncertain, int wordCount) {
            this(code, confidence, alternatives, uncertain, wordCount, false);
        }
    }

    public LanguageIdentification(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing language detection model: " + resource);
            detector = new LanguageDetectorME(new LanguageDetectorModel(in));
        }
    }

    public Detection detect(String text) {
        String cleaned = clean(text);
        int words = (int) Arrays.stream(cleaned.split("\\s+"))
                .filter(Stopwords::isWord).count();
        if (words == 0) throw new IllegalArgumentException("Enter text containing words to detect its language.");
        var predictions = detector.predictLanguages(cleaned);
        List<Candidate> candidates = Arrays.stream(predictions).limit(3)
                .map(p -> new Candidate(normalizeCode(p.getLang()), p.getConfidence())).toList();
        Candidate first = candidates.get(0);
        double runnerUp = candidates.size() < 2 ? 0 : candidates.get(1).confidence();
        boolean closeCandidates = runnerUp > 0 && first.confidence() / runnerUp < 1.5;
        // Compare substantial sentences instead of treating one confident
        // prediction as evidence that every part of a passage is monolingual.
        java.util.Set<String> sentenceLanguages = new java.util.HashSet<>();
        Arrays.stream(cleaned.split("(?<=[.!?])\\s+")).limit(16).forEach(sentence -> {
            long sentenceWords = Arrays.stream(sentence.split("\\s+")).filter(Stopwords::isWord).count();
            if (sentenceWords >= 6) {
                var sentencePredictions = detector.predictLanguages(sentence);
                var prediction = sentencePredictions[0];
                if (prediction.getConfidence() >= 0.02 && (sentencePredictions.length < 2
                        || prediction.getConfidence() >= sentencePredictions[1].getConfidence() * 1.5)) {
                    sentenceLanguages.add(normalizeCode(prediction.getLang()));
                }
            }
        });
        boolean mixed = sentenceLanguages.size() > 1;
        boolean uncertain = mixed || words < 4 || first.confidence() < 0.02 || closeCandidates || !supported(first.code());
        return new Detection(first.code(), first.confidence(), candidates, uncertain, words, mixed);
    }

    static String clean(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("(?i)https?://\\S+|www\\.\\S+|\\S+@\\S+\\.\\S+", " ")
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
    }

    public static String normalizeCode(String value) {
        String code = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (code) {
            case "en", "english" -> "eng"; case "it", "italian" -> "ita";
            case "fr", "fre", "french" -> "fra"; case "de", "ger", "german" -> "deu";
            case "es", "spanish" -> "spa"; case "pt", "portuguese" -> "por";
            case "nl", "dut", "dutch" -> "nld"; default -> code;
        };
    }
    public static boolean supported(String code) { return NAMES.containsKey(normalizeCode(code)); }
    public static String label(String code) {
        String normalized = normalizeCode(code);
        return NAMES.getOrDefault(normalized, normalized) + " (" + normalized + ")";
    }
}
