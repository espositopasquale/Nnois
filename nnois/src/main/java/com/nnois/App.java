package com.nnois;

import com.nnois.banner.Banner;
import com.nnois.nlp.*;
import com.nnois.nlp.NativeMultilingualPipeline.TransparencyResult;
import java.util.*;
import com.nnois.utils.TerminalColors;

public class App {
    private static final int PAGE_SIZE = 12;
    interface AnalysisService {
        LanguageIdentification.Detection detect(String text);
        AutoTuningMultilingualPipeline.ContrastiveResult analyze(String text, String word, String language) throws Exception;
        List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses(String word, String language) throws Exception;
    }
    private static final class EndSession extends RuntimeException { }
    static void runSession(AnalysisService service, Scanner input) {
        System.out.println(style("Compare words across languages using shared OMW senses.", "1;97"));
        System.out.println(style("Enter text. Commands: demo, help, paste, exit. Use back to cancel a step.\n", "90"));
        while (true) {
            String text = read(input, "Text");
            if (text == null || exit(text)) break;
            if (text.equalsIgnoreCase("help")) { help(); continue; }
            if (text.equalsIgnoreCase("demo") || text.toLowerCase(Locale.ROOT).startsWith("demo ")) {
                try {
                    String requested = text.length() > 4 ? text.substring(4).strip() : read(input,
                            "Demo language [Enter = all; en/it/fr/de/es/pt/nl; back]");
                    if (requested == null || exit(requested)) break;
                    if (requested.equalsIgnoreCase("back")) continue;
                    runDemo(service, requested.isEmpty() || requested.equalsIgnoreCase("all") ? null : requested);
                } catch (Exception error) {
                    warning("Demo could not finish: " + error.getMessage());
                }
                System.out.println();
                continue;
            }
            if (text.equalsIgnoreCase("paste")) {
                System.out.println("Paste multiple lines; enter a single '.' to finish (back cancels).");
                StringBuilder pasted = new StringBuilder();
                String line;
                while ((line = read(input, "...")) != null && !line.equals(".")) {
                    if (line.equalsIgnoreCase("back") || exit(line)) break;
                    pasted.append(line).append('\n');
                }
                if (line == null || exit(line)) break;
                if (line.equalsIgnoreCase("back")) continue;
                text = pasted.toString().strip();
            }
            if (!Stopwords.isWord(text)) { warning("Enter text containing words."); continue; }
            try {
                String language = chooseLanguage(service, input, text);
                if (language == null) break;
                if (language.equals("back")) continue;
                if (text.strip().split("\\s+").length == 1) {
                    showSenses(input, service.senses(text, language));
                    continue;
                }
                System.out.println("\n  " + style("1", "1;96") + "  " + style("Whole-text comparison (default)", "92")
                        + "\n  " + style("2", "1;96") + "  " + style("One word in context", "95"));
                while (true) {
                    String mode = read(input, "Mode [1]");
                    if (mode == null || exit(mode)) return;
                    if (mode.equalsIgnoreCase("back")) break;
                    if (mode.isEmpty() || mode.equals("1")) {
                        showAnalysis(input, service.analyze(text, null, language)); break;
                    }
                    if (!mode.equals("2")) { warning("Choose 1 or 2, or use back."); continue; }
                    String word;
                    while (true) {
                        word = read(input, "Word in your text");
                        if (word == null || exit(word)) return;
                        if (word.equalsIgnoreCase("back")) break;
                        if (word.isBlank() || word.split("\\s+").length != 1 || !Stopwords.isWord(word)) {
                            warning("Enter one word, or use back to choose a mode."); continue;
                        }
                        if (Stopwords.forLanguage(language).contains(word.toLowerCase(Locale.ROOT))) {
                            warning("Stopwords are excluded. Choose a content word."); continue;
                        }
                        String target = word;
                        if (Arrays.stream(text.split("(?U)[^\\p{L}\\p{M}'?-]+"))
                                .noneMatch(token -> token.equalsIgnoreCase(target))) {
                            warning("Choose a word that appears in your text."); continue;
                        }
                        showAnalysis(input, service.analyze(text, word, language)); break;
                    }
                    if (!word.equalsIgnoreCase("back")) break;
                }
            } catch (EndSession end) {
                break;
            } catch (Exception error) {
                warning("Analysis could not finish: " + error.getMessage());
                System.out.println("Try another text or language. Your session is still open.");
            }
            System.out.println();
        }
        System.out.println("Goodbye.");
    }
    private static String chooseLanguage(AnalysisService service, Scanner input, String text) {
        var detected = service.detect(text);
        title("Language");
        System.out.printf(Locale.ROOT, "  Suggested  %s | model score %.3f%n",
                style(LanguageIdentification.label(detected.code()), "1;92"), detected.confidence());
        if (detected.uncertain()) {
            warning(detected.mixedLanguage() ? "Several sentence languages detected. Analyze each language separately for best results."
                    : "Language is uncertain; short or mixed-language text may need a manual choice.");
            System.out.println("  Alternatives: " + detected.alternatives().stream()
                    .map(c -> String.format(Locale.ROOT, "%s %.3f", LanguageIdentification.label(c.code()), c.confidence()))
                    .collect(java.util.stream.Collectors.joining(" | ")));
        }
        boolean supported = LanguageIdentification.supported(detected.code());
        if (!supported) warning("This language has no bundled NLP models. Select a supported language to continue.");
        System.out.println("  Languages: en / it / fr / de / es / pt / nl (names and ISO codes accepted)");
        while (true) {
            String choice = read(input, supported ? "Language [Enter = suggested]" : "Language code");
            if (choice == null || exit(choice)) return null;
            if (choice.equalsIgnoreCase("back")) return "back";
            if (choice.isEmpty() && supported) return detected.code();
            String normalized = LanguageIdentification.normalizeCode(choice);
            if (LanguageIdentification.supported(normalized)) return normalized;
            warning("Choose en, it, fr, de, es, pt or nl; use back to change the text.");
        }
    }
    private static void showAnalysis(Scanner input, AutoTuningMultilingualPipeline.ContrastiveResult result) {
        showAnalysis(input, result, true);
    }
    private static void showAnalysis(Scanner input, AutoTuningMultilingualPipeline.ContrastiveResult result, boolean interactive) {
        title(result.chosenWord == null ? "Whole-text comparison" : "Word: " + result.chosenWord);
        var coverage = result.nominalAggregate;
        System.out.println("  " + style("Nominal coverage", "1;97") + "  "
                + style(String.format(Locale.ROOT, "%.2f  %s", coverage.totalAggregateScore, bar(coverage.totalAggregateScore)),
                        TerminalColors.scoreColor(coverage.totalAggregateScore)));
        System.out.printf("  Words considered  %d / %d (stopwords excluded)%n", coverage.consideredWords, coverage.totalWords);
        if (result.chosenWord == null) System.out.printf("  OMW units matched %d / %d%n", result.analyzedNominalUnits, coverage.totalNominalUnits);
        System.out.println(style("  Coverage counts nouns; transparency measures spelling similarity.\n", "90"));
        var rows = result.transparencyResults.stream().filter(r -> r.lang != null && !sameLanguage(r.lang, result.detectedLanguage))
                .sorted(Comparator.comparingDouble((TransparencyResult r) -> r.score).reversed()
                        .thenComparing(r -> r.lang).thenComparing(r -> r.lemma)).toList();
        if (rows.isEmpty()) { warning("No cross-language matches found. Try another word or language."); return; }
        printRows(rows.subList(0, Math.min(PAGE_SIZE, rows.size())), result.chosenWord != null);
        if (interactive && rows.size() > PAGE_SIZE && yes(input, "Show all " + rows.size() + " results? [y/N]"))
            printRows(rows.subList(PAGE_SIZE, rows.size()), result.chosenWord != null);
        if (interactive && result.chosenWord == null && !result.nominalLanguageTransparencies.isEmpty()
                && yes(input, "Show word-by-word details? [y/N]")) {
            title("Word-by-word details");
            String current = "";
            for (var detail : result.nominalLanguageTransparencies) {
                if (!detail.nominalUnit.equals(current)) { current = detail.nominalUnit; System.out.println("\n  " + style(current, "1;95")); }
                System.out.printf("  %s %s %s%n", languageCell(detail.language), style(String.format("%-28s", clip(detail.lemma, 28)), "97"),
                        style(String.format(Locale.ROOT, "%.2f", detail.score), TerminalColors.scoreColor(detail.score)));
            }
        }
    }
    private static void printRows(List<TransparencyResult> rows, boolean showLemma) {
        System.out.println(style(String.format("  %-21s %6s  %s", "LANGUAGE", "SCORE", showLemma ? "TRANSLATION" : "TRANSPARENCY"), "1;96"));
        System.out.println(style("  " + "-".repeat(65), "90"));
        for (var row : rows) System.out.printf("  %s %s  %s%n", languageCell(row.lang),
                style(String.format(Locale.ROOT, "%6.2f", row.score), TerminalColors.scoreColor(row.score)),
                showLemma ? style(clip(row.lemma, 32), "97") : style(bar(row.score), TerminalColors.scoreColor(row.score)));
    }
    private static void showSenses(Scanner input, List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses) {
        if (senses.isEmpty()) { warning("No OMW senses found. Try another language or word form."); return; }
        title("Sense explorer | " + senses.size() + " senses");
        for (int i = 0; i < senses.size(); i++) {
            if (i > 0 && i % 3 == 0 && !yes(input, "Show more senses? [y/N]")) break;
            var sense = senses.get(i);
            System.out.println("\n  " + style("Sense " + (i + 1), "1;95") + " | "
                    + style("OMW " + sense.synsetOffset + "-" + sense.synsetPos, "96"));
            System.out.println("  " + (sense.gloss == null || sense.gloss.isBlank() ? "No definition available." : sense.gloss));
            Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
            sense.translations.stream().filter(r -> r.lang != null && !sameLanguage(r.lang, sense.sourceLanguage))
                    .sorted(Comparator.comparing(r -> r.lang))
                    .forEach(r -> grouped.computeIfAbsent(r.lang, key -> new LinkedHashSet<>()).add(r.lemma));
            var entries = grouped.entrySet().stream().toList();
            printTranslations(entries.subList(0, Math.min(PAGE_SIZE, entries.size())));
            if (entries.size() > PAGE_SIZE && yes(input, "Show all translation languages? [y/N]")) printTranslations(entries.subList(PAGE_SIZE, entries.size()));
        }
    }
    private static void printTranslations(List<Map.Entry<String, LinkedHashSet<String>>> entries) {
        for (var entry : entries) System.out.printf("  %s %s%n", languageCell(entry.getKey()), style(clip(String.join(", ", entry.getValue()), 46), "97"));
    }
    private static String languageCell(String code) {
        String color = switch (LanguageIdentification.normalizeCode(code)) {
            case "eng" -> "96"; case "ita" -> "92"; case "fra" -> "94";
            case "deu" -> "93"; case "spa" -> "91"; case "por" -> "95";
            case "nld" -> "36"; default -> "97";
        };
        // Pad before styling so escape codes do not disturb table alignment.
        return style(String.format("%-21s", clip(LanguageIdentification.label(code), 21)), color);
    }
    private static boolean sameLanguage(String a, String b) { return LanguageIdentification.normalizeCode(a).equals(LanguageIdentification.normalizeCode(b)); }
    static String bar(double score) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, score)) * 16);
        return "[" + "#".repeat(filled) + ".".repeat(16 - filled) + "]";
    }
    static String clip(String value, int width) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        return clean.length() <= width ? clean : clean.substring(0, width - 3) + "...";
    }
    private static String read(Scanner input, String prompt) {
        System.out.print(style(prompt, "36") + " > "); System.out.flush();
        return input.hasNextLine() ? input.nextLine().strip() : null;
    }
    private static boolean yes(Scanner input, String prompt) {
        while (true) {
            String answer = read(input, prompt);
            if (exit(answer)) throw new EndSession();
            if (answer == null || answer.isEmpty() || "n".equalsIgnoreCase(answer) || "no".equalsIgnoreCase(answer)
                    || "back".equalsIgnoreCase(answer)) return false;
            if ("y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer)) return true;
            warning("Enter y or n, or press Enter for no.");
        }
    }
    private static boolean exit(String text) { return "exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text); }
    private static void title(String title) { System.out.println("\n" + style(title, "1;96") + "\n" + style("-".repeat(72), "90")); }
    private static void warning(String text) { System.out.println(style("  " + text, "33")); }
    private static String style(String text, String code) { return TerminalColors.style(text, code); }
    private static void help() {
        System.out.println("\n  Enter a sentence for whole-text or chosen-word comparison.");
        System.out.println("  Enter a single word to explore its possible OMW senses.");
        System.out.println("  demo   Run Java demos for all seven languages or choose one (also: demo it)");
        System.out.println("  paste  Multi-line input, finished by a single '.'");
        System.out.println("  back   Cancel language, mode or target selection");
        System.out.println("  exit   End the session (also quit)\n");
    }
    static void runDemo(AnalysisService service, String language) throws Exception {
        var samples = DemoTexts.SAMPLES.stream().filter(s -> language == null
                || s.language().equals(LanguageIdentification.normalizeCode(language))).toList();
        if (samples.isEmpty()) throw new IllegalArgumentException("Demo language must be en, it, fr, de, es, pt or nl.");
        title("European languages demo");
        System.out.println("Parallel passages: nature, education, markets, and the future of a community.");
        System.out.println("Each sample shows language detection, whole-text coverage, and one word's OMW sense.");
        int correct = 0;
        List<String> summary = new ArrayList<>();
        try (Scanner noPrompts = new Scanner("")) {
            for (var sample : samples) {
                title(LanguageIdentification.label(sample.language()));
                StringBuilder line = new StringBuilder();
                for (String word : sample.text().split("\\s+")) {
                    if (line.length() + word.length() + 1 > 70) {
                        System.out.println("  " + line); line.setLength(0);
                    }
                    if (!line.isEmpty()) line.append(' ');
                    line.append(word);
                }
                if (!line.isEmpty()) System.out.println("  " + line);
                var detected = service.detect(sample.text());
                boolean matched = sameLanguage(sample.language(), detected.code());
                if (matched) correct++;
                System.out.printf(Locale.ROOT, "\n  Detected: %s | model score %.3f | expected: %s%n",
                        style(LanguageIdentification.label(detected.code()), matched ? "1;92" : "1;93"),
                        detected.confidence(), sample.language());
                if (detected.uncertain()) warning("Detection is uncertain; inspect the language choice.");
                if (!LanguageIdentification.supported(detected.code())) {
                    warning("Unsupported detection; skipping analysis for this sample."); continue;
                }
                var aggregate = service.analyze(sample.text(), null, detected.code());
                showAnalysis(noPrompts, aggregate, false);
                showAnalysis(noPrompts, service.analyze(sample.text(), sample.target(), detected.code()), false);
                summary.add(String.format(Locale.ROOT, "  %-21s %.2f coverage | %d/%d words | %d/%d OMW units",
                        LanguageIdentification.label(sample.language()), aggregate.nominalAggregate.totalAggregateScore,
                        aggregate.nominalAggregate.consideredWords, aggregate.nominalAggregate.totalWords,
                        aggregate.analyzedNominalUnits, aggregate.nominalAggregate.totalNominalUnits));
            }
        }
        title("Demo summary | " + correct + "/" + samples.size() + " languages detected correctly");
        summary.forEach(System.out::println);
        System.out.println("These examples demonstrate behavior; they are not an accuracy benchmark.");
    }
    public static void main(String[] args) {
        if (args.length > 0 && (!args[0].equals("--demo") || args.length > 2)) {
            System.err.println("Usage: Nnois [--demo [en|it|fr|de|es|pt|nl]]"); return;
        }
        Banner.print();
        try (var pipeline = new AutoTuningMultilingualPipeline("omw/omw_multilingual.db", "langdetect-183.bin");
                Scanner input = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8)) {
            AnalysisService service = new AnalysisService() {
                public LanguageIdentification.Detection detect(String text) { return pipeline.detectLanguage(text); }
                public AutoTuningMultilingualPipeline.ContrastiveResult analyze(String text, String word, String language) throws Exception { return pipeline.processContrastive(text, word, language, false); }
                public List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses(String word, String language) throws Exception { return pipeline.lookupWordSensesAndTranslations(word, language); }
            };
            if (args.length > 0) runDemo(service, args.length == 2 ? args[1] : null);
            else runSession(service, input);
        } catch (Exception error) { System.err.println("Nnois could not start: " + error.getMessage()); }
    }
}
