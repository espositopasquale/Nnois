package com.nnois;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Scanner;

import com.nnois.banner.Banner;
import com.nnois.nlp.AutoTuningMultilingualPipeline;

public class App {

    private static final int MAX_LEMMAS_PER_LANGUAGE = 8;
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String DIM = "\u001B[2m";

    private static class LanguageChoice {

        private final boolean autoDetect;
        private final String languageCode;

        private LanguageChoice(boolean autoDetect, String languageCode) {
            this.autoDetect = autoDetect;
            this.languageCode = languageCode;
        }
    }

    private static void runInteractivePhase(AutoTuningMultilingualPipeline autoPipeline) throws Exception {
        try (Scanner scanner = new Scanner(System.in)) {
            printHeader("Interactive Session");
            System.out.println(colorize("Type your text, or type 'exit' to quit.", DIM));
            System.out.println();

            while (true) {
                prompt("Input text", CYAN);
                if (!scanner.hasNextLine()) {
                    printInfo("Input closed. Session ended.");
                    break;
                }
                String inputText = scanner.nextLine();

                if (inputText == null) {
                    continue;
                }

                String text = inputText.trim();
                if (text.equalsIgnoreCase("exit") || text.equalsIgnoreCase("quit")) {
                    printInfo("Session ended.");
                    break;
                }

                if (text.isEmpty()) {
                    printWarning("Please enter a non-empty text.");
                    continue;
                }

                LanguageChoice languageChoice = promptLanguageChoice(scanner);
                if (languageChoice == null) {
                    printInfo("Returning to text input.");
                    continue;
                }

                if (isSingleWord(text)) {
                    printHeader("Single-word sense explorer");
                    runSingleWordLookup(autoPipeline, text, languageChoice);
                    printFooter();
                    continue;
                }

                promptAndRunAnalysis(scanner, autoPipeline, text, languageChoice);

                printFooter();
            }
        }
    }

    private static LanguageChoice promptLanguageChoice(Scanner scanner) {
        printHeader("Language source");
        System.out.println(colorize("1) Auto-detect language", GREEN));
        System.out.println(colorize("2) Manually choose language code", GREEN));
        System.out.println(colorize("Type 'back' to enter different text.", DIM));

        while (true) {
            prompt("Language [1/2]", CYAN);
            if (!scanner.hasNextLine()) {
                return null;
            }
            String languageMode = scanner.nextLine().trim();

            if ("1".equals(languageMode)) {
                return new LanguageChoice(true, null);
            }

            if ("2".equals(languageMode)) {
                prompt("Manual code (eng, ita, fra, en, it, fr, pt, nl)", CYAN);
                if (!scanner.hasNextLine()) {
                    return null;
                }
                String manualCode = scanner.nextLine().trim().toLowerCase();
                if (manualCode.isEmpty()) {
                    printInfo("No manual code provided. Using auto-detect.");
                    return new LanguageChoice(true, null);
                }
                return new LanguageChoice(false, manualCode);
            }

            if ("back".equalsIgnoreCase(languageMode)) {
                return null;
            }

            printWarning("Please enter 1, 2, or 'back'.");
        }
    }

    private static void promptAndRunAnalysis(Scanner scanner,
            AutoTuningMultilingualPipeline autoPipeline,
            String text,
            LanguageChoice languageChoice) throws Exception {
        printHeader("Analysis mode");
        System.out.println(colorize("1) Chosen-word transparency in context", GREEN));
        System.out.println(colorize("2) Aggregate nominal transparency (no chosen word)", GREEN));

        while (true) {
            prompt("Mode [1/2]", CYAN);
            if (!scanner.hasNextLine()) {
                printInfo("Input closed. Session ended.");
                return;
            }
            String mode = scanner.nextLine().trim();

            if ("1".equals(mode)) {
                prompt("Target word", CYAN);
                if (!scanner.hasNextLine()) {
                    printInfo("Input closed. Session ended.");
                    return;
                }
                String chosenWord = scanner.nextLine().trim();

                if (chosenWord.isEmpty()) {
                    printWarning("No target word entered. Using nominal aggregate mode.");
                    runContrastive(autoPipeline, text, null, languageChoice);
                } else if (isSingleWord(chosenWord)) {
                    runContrastive(autoPipeline, text, chosenWord, languageChoice);
                } else {
                    printWarning("Target must be a single word. Using nominal aggregate mode.");
                    runContrastive(autoPipeline, text, null, languageChoice);
                }
                return;
            }

            if ("2".equals(mode)) {
                var analysis = runContrastive(autoPipeline, text, null, languageChoice);
                promptForAggregateDetailReport(scanner, analysis);
                return;
            }

            printWarning("Please enter 1 or 2.");
        }
    }

    private static void runSingleWordLookup(AutoTuningMultilingualPipeline autoPipeline,
            String word,
            LanguageChoice languageChoice) throws Exception {
        String languageHint = languageChoice.autoDetect ? null : languageChoice.languageCode;
        var senses = autoPipeline.lookupWordSensesAndTranslations(word, languageHint);

        if (senses.isEmpty()) {
            printWarning(String.format("No OMW senses found for '%s'.", word));
            return;
        }

        printInfo(String.format("Found %d sense(s) for '%s'.", senses.size(), word));

        int index = 1;
        for (var sense : senses) {
            System.out.printf("%n%sSense %d%s | Synset: %s-%s%n", BOLD + CYAN, index, RESET, sense.synsetOffset,
                    sense.synsetPos);
            System.out.printf("%sGloss:%s %s%n", BOLD, RESET,
                    (sense.gloss == null || sense.gloss.isBlank()) ? "(no gloss)" : sense.gloss);

            Map<String, LinkedHashSet<String>> translationsByLanguage = new LinkedHashMap<>();

            sense.translations.stream()
                    .filter(result -> result.lang != null)
                    .filter(result -> !result.lang.equalsIgnoreCase(sense.sourceLanguage))
                    .forEach(result -> translationsByLanguage
                    .computeIfAbsent(result.lang, key -> new LinkedHashSet<>())
                    .add(result.lemma));

            if (translationsByLanguage.isEmpty()) {
                printInfo("Translations: none");
            } else {
                System.out.println(colorize("Translations from OMW:", BOLD + GREEN));
                for (Map.Entry<String, LinkedHashSet<String>> entry : translationsByLanguage.entrySet()) {
                    StringBuilder lemmas = new StringBuilder();
                    int printed = 0;
                    for (String lemma : entry.getValue()) {
                        if (printed >= MAX_LEMMAS_PER_LANGUAGE) {
                            lemmas.append(" ...");
                            break;
                        }

                        if (printed > 0) {
                            lemmas.append(", ");
                        }

                        lemmas.append(lemma);
                        printed++;
                    }

                    System.out.printf("  %s | Translations: %s%n", languageLabel(entry.getKey()), lemmas);
                }
            }

            index++;
        }
    }

    private static boolean isSingleWord(String text) {
        return text != null && !text.trim().isEmpty() && text.trim().split("\\s+").length == 1;
    }

    private static AutoTuningMultilingualPipeline.ContrastiveResult runContrastive(
            AutoTuningMultilingualPipeline autoPipeline,
            String sentence,
            String chosenWord,
            LanguageChoice languageChoice)
            throws Exception {

        var analysis = autoPipeline.processContrastive(
                sentence,
                chosenWord,
                languageChoice.languageCode,
                languageChoice.autoDetect);

        if (analysis.mode == AutoTuningMultilingualPipeline.AnalysisMode.CHOSEN_WORD) {
            printInfo(String.format("[Contrastive] Chosen word mode | Word: %s", analysis.chosenWord));
        } else {
            printInfo(String.format("[Contrastive] Nominal aggregate mode | Nominal units: %d | Analyzed units: %d",
                    analysis.nominalAggregate.totalNominalUnits,
                    analysis.analyzedNominalUnits));
        }

        System.out.printf("%s[Nominal Aggregate]%s Total score: %.2f | Units: %d%n",
                BOLD + GREEN,
                RESET,
                analysis.nominalAggregate.totalAggregateScore,
                analysis.nominalAggregate.totalNominalUnits);

        analysis.transparencyResults.stream()
                .filter(result -> result.lang != null)
                .filter(result -> !result.lang.equalsIgnoreCase(analysis.detectedLanguage))
                .forEach(result -> {
                    if (analysis.mode == AutoTuningMultilingualPipeline.AnalysisMode.NOMINAL_AGGREGATE_SUM) {
                        System.out.printf("%s | Transparency: %.2f%n", languageLabel(result.lang), result.score);
                    } else {
                        System.out.printf("%s | Lemma: %-20s | Transparency: %.2f%n",
                                languageLabel(result.lang), result.lemma, result.score);
                    }
                });

        return analysis;
    }

    private static void promptForAggregateDetailReport(Scanner scanner,
            AutoTuningMultilingualPipeline.ContrastiveResult analysis) {
        if (analysis.mode != AutoTuningMultilingualPipeline.AnalysisMode.NOMINAL_AGGREGATE_SUM
                || analysis.nominalLanguageTransparencies.isEmpty()) {
            return;
        }

        prompt("Show detailed report [y/N]", CYAN);
        if (!scanner.hasNextLine() || !"y".equalsIgnoreCase(scanner.nextLine().trim())) {
            return;
        }

        printHeader("Aggregate nominal detail report");
        String currentUnit = null;
        for (var detail : analysis.nominalLanguageTransparencies) {
            if (!detail.nominalUnit.equals(currentUnit)) {
                currentUnit = detail.nominalUnit;
                System.out.printf("%s%s%s (%s)%n", BOLD, currentUnit, RESET, detail.unitType);
            }
            System.out.printf("  %s | Lemma: %-20s | Transparency: %.2f%n",
                    languageLabel(detail.language), detail.lemma, detail.score);
        }
    }

    private static String languageLabel(String languageCode) {
        String code = languageCode == null ? "unknown" : languageCode.toLowerCase();
        return colorize("Language: " + code, languageColor(code));
    }

    private static String languageColor(String languageCode) {
        return switch (languageCode) {
            case "ita", "it", "por", "pt", "bra" ->
                GREEN;
            case "fra", "fr", "nld", "nl", "rus", "ru", "ukr", "uk", "pol", "pl", "ces", "cs", "slk", "sk", "ell", "el", "swe", "sv", "fin", "fi", "nor", "no", "dan", "da" ->
                BLUE;
            case "eng", "en", "usa", "jpn", "ja", "zho", "zh", "kor", "ko", "vie", "vi", "ara", "ar", "heb", "he", "tur", "tr", "hun", "hu" ->
                RED;
            case "deu", "de", "bel", "be", "esp", "es", "spa" ->
                YELLOW;
            default ->
                MAGENTA;
        };
    }

    private static void printHeader(String title) {
        System.out.println(colorize("\n" + "-".repeat(74), DIM));
        System.out.println(colorize(title, BOLD + CYAN));
        System.out.println(colorize("-".repeat(74), DIM));
    }

    private static void printFooter() {
        System.out.println(colorize("-".repeat(74), DIM));
        System.out.println();
    }

    private static void prompt(String label, String color) {
        System.out.print(colorize(label + " > ", BOLD + color));
    }

    private static void printInfo(String message) {
        System.out.println(colorize(message, GREEN));
    }

    private static void printWarning(String message) {
        System.out.println(colorize(message, YELLOW));
    }

    private static void printError(String message) {
        System.err.println(colorize(message, RED));
    }

    private static String colorize(String text, String ansi) {
        return ansi + text + RESET;
    }

    public static void main(String[] args) {
        Banner.print();

        try (AutoTuningMultilingualPipeline autoPipeline = new AutoTuningMultilingualPipeline(
                "omw/omw_multilingual.db",
                "target/classes/models/langdetect-183.bin"
        )) {
            runInteractivePhase(autoPipeline);

        } catch (Exception e) {
            printError("Unexpected error: " + e.getMessage());
        }
    }
}
