package com.nnois.nlp;

import com.nnois.nlp.AutoTuningMultilingualPipeline.NominalSenseComparison;
import com.nnois.nlp.NativeMultilingualPipeline.TransparencyResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Replaces words of a source text with their translations in another language. The translation is taken
 * from the sense recognized for the word in context, and chosen by spelling similarity to the original:
 * the most similar one lowers complexity for the reader, the least similar one raises it.
 */
public final class WordSubstitution {

    public enum Direction { MORE_SIMILAR, LESS_SIMILAR }

    public record Options(String targetLanguage, Direction direction, double minSimilarity,
            double minApplicability) {

        public Options {
            if (targetLanguage == null || !LanguageIdentification.supported(
                    LanguageIdentification.normalizeCode(targetLanguage))) {
                throw new IllegalArgumentException("Unsupported target language: " + targetLanguage);
            }
            targetLanguage = LanguageIdentification.normalizeCode(targetLanguage);
            direction = direction == null ? Direction.MORE_SIMILAR : direction;
        }
    }

    public record Substitution(int begin, int end, String original, String replacement, double similarity,
            String gloss) { }

    public record Result(String text, List<Substitution> applied, List<Substitution> rejected) { }

    /** Decides whether a candidate text is still grammatically coherent. */
    public interface CoherenceChecker {
        double score(String text) throws Exception;
    }

    public record Candidate(String replacement, double transparency, double change, String referenceForm, String validation) { }
    public record Scale(String original, int begin, int end, String gloss, double baseline,
            String referenceForm, String validation, List<Candidate> candidates) { }

    /** Scores same-language alternatives against the closest lexicalization of the selected sense. */
    public static Scale scale(String text, NominalSenseComparison sense, String sourceLanguage, String referenceLanguage) {
        if (sense.begin() < 0 || sense.end() > text.length() || sense.begin() >= sense.end())
            throw new IllegalArgumentException("Invalid word position.");
        String source = LanguageIdentification.normalizeCode(sourceLanguage);
        String reference = LanguageIdentification.normalizeCode(referenceLanguage);
        if (!LanguageIdentification.supported(reference) || source.equals(reference))
            throw new IllegalArgumentException("Choose a different supported comparison language.");
        String original = text.substring(sense.begin(), sense.end());
        var forms = sense.translations().stream().filter(r -> r.lang != null && r.lemma != null
                && LanguageIdentification.normalizeCode(r.lang).equals(reference))
                .map(r -> r.lemma.replace('_', ' ')).filter(s -> !s.isBlank()).distinct().sorted().toList();
        if (forms.isEmpty()) throw new IllegalArgumentException("No translation for this sense in the comparison language.");
        String baselineForm = closest(original, forms);
        double baseline = similarity(original, baselineForm);
        var candidates = sense.translations().stream().filter(r -> r.lang != null && r.lemma != null
                && LanguageIdentification.normalizeCode(r.lang).equals(source))
                .map(r -> matchCase(original, r.lemma.replace('_', ' '))).filter(s -> !s.isBlank() && !s.equalsIgnoreCase(original))
                .distinct().map(word -> { String form = closest(word, forms); double score = similarity(word, form);
                    return new Candidate(word, score, score - baseline, form, "unverified"); })
                .sorted(Comparator.comparingDouble(Candidate::transparency).reversed().thenComparing(Candidate::replacement)).toList();
        return new Scale(original, sense.begin(), sense.end(), sense.gloss(), baseline, baselineForm, "unverified", candidates);
    }

    private static String closest(String word, List<String> forms) {
        return forms.stream().max(Comparator.comparingDouble((String form) -> similarity(word, form))
                .thenComparing(Comparator.reverseOrder())).orElseThrow();
    }
    private static double similarity(String left, String right) {
        int[] a = left.toLowerCase(Locale.ROOT).codePoints().toArray();
        int[] b = right.toLowerCase(Locale.ROOT).codePoints().toArray();
        int[] previous = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) previous[j] = j;
        for (int i = 1; i <= a.length; i++) {
            int[] next = new int[b.length + 1]; next[0] = i;
            for (int j = 1; j <= b.length; j++) next[j] = Math.min(Math.min(next[j-1] + 1, previous[j] + 1), previous[j-1] + (a[i-1] == b[j-1] ? 0 : 1));
            previous = next;
        }
        return Math.max(a.length, b.length) == 0 ? 1 : 1.0 - (double) previous[b.length] / Math.max(a.length, b.length);
    }

    private static final double GRAMMAR_TOLERANCE = 0.05;

    private WordSubstitution() { }

    /** Proposes at most one replacement per recognized nominal unit, without touching the text. */
    public static List<Substitution> propose(String text, List<NominalSenseComparison> senses, Options options) {
        List<Substitution> proposals = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        for (var sense : senses) {
            if (!sense.selected() || sense.applicabilityScore() < options.minApplicability()
                    || sense.begin() < 0 || sense.end() > text.length() || sense.begin() >= sense.end()
                    || overlaps(taken, sense.begin(), sense.end())) {
                continue;
            }
            String original = text.substring(sense.begin(), sense.end());
            best(sense, original, options).ifPresent(choice -> {
                taken.add(new int[] { sense.begin(), sense.end() });
                proposals.add(new Substitution(sense.begin(), sense.end(), original,
                        matchCase(original, choice.lemma.replace('_', ' ')), choice.score, sense.gloss()));
            });
        }
        proposals.sort(Comparator.comparingInt(Substitution::begin));
        return proposals;
    }

    /** Applies proposals; each one is kept only if the checker (when given) finds the text still coherent. */
    public static Result apply(String text, List<Substitution> proposals, CoherenceChecker checker) {
        List<Substitution> applied = new ArrayList<>();
        List<Substitution> rejected = new ArrayList<>();
        String current = text;
        double baseline = Double.NaN;
        for (var proposal : proposals) {
            List<Substitution> trial = new ArrayList<>(applied);
            trial.add(proposal);
            String candidate = render(text, trial);
            if (checker != null) {
                try {
                    if (Double.isNaN(baseline)) baseline = checker.score(current);
                    double score = checker.score(candidate);
                    if (score + GRAMMAR_TOLERANCE < baseline) {
                        rejected.add(proposal);
                        continue;
                    }
                    baseline = Math.max(baseline, score);
                } catch (Exception unavailable) {
                    checker = null;
                }
            }
            applied.add(proposal);
            current = candidate;
        }
        return new Result(current, applied, rejected);
    }

    static String render(String text, List<Substitution> substitutions) {
        StringBuilder out = new StringBuilder(text);
        substitutions.stream().sorted(Comparator.comparingInt(Substitution::begin).reversed())
                .forEach(s -> out.replace(s.begin(), s.end(), s.replacement()));
        return out.toString();
    }

    private static Optional<TransparencyResult> best(NominalSenseComparison sense, String original,
            Options options) {
        var candidates = sense.translations().stream()
                .filter(r -> r.lang != null && r.lemma != null && !r.lemma.isBlank()
                        && LanguageIdentification.normalizeCode(r.lang).equals(options.targetLanguage())
                        && !r.lemma.replace('_', ' ').equalsIgnoreCase(original));
        if (options.direction() == Direction.MORE_SIMILAR) {
            return candidates.filter(r -> r.score >= options.minSimilarity())
                    .max(Comparator.comparingDouble((TransparencyResult r) -> r.score).thenComparing(r -> r.lemma));
        }
        return candidates.filter(r -> r.score <= options.minSimilarity())
                .min(Comparator.comparingDouble((TransparencyResult r) -> r.score).thenComparing(r -> r.lemma));
    }

    private static boolean overlaps(List<int[]> taken, int begin, int end) {
        return taken.stream().anyMatch(range -> begin < range[1] && range[0] < end);
    }

    private static String matchCase(String original, String replacement) {
        if (replacement.isEmpty()) return replacement;
        boolean letters = original.chars().anyMatch(Character::isLetter);
        if (letters && original.equals(original.toUpperCase(Locale.ROOT)) && original.length() > 1) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (!original.isEmpty() && Character.isUpperCase(original.codePointAt(0))) {
            return replacement.substring(0, replacement.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT)
                    + replacement.substring(replacement.offsetByCodePoints(0, 1));
        }
        return replacement;
    }
}
