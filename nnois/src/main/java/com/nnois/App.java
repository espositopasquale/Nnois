package com.nnois;

import com.nnois.banner.Banner;
import com.nnois.nlp.*;
import com.nnois.nlp.NativeMultilingualPipeline.TransparencyResult;
import java.util.*;
import com.nnois.utils.TerminalColors;

public class App {
    private static final int PAGE_SIZE = 12;
    private static final String CYAN = "1;38;2;98;232;234";
    private static final String RED = "1;38;2;255;95;114";
    private static final String CREAM = "1;38;2;242;239;231";
    private static final String GREEN = "1;38;2;107;224;168";
    private static final String AMBER = "1;38;2;255;193;94";
    private static final String VIOLET = "1;38;2;190;142;255";
    private static final String MUTED = "38;2;132;145;148";

    interface AnalysisService {
        LanguageIdentification.Detection detect(String text);

        AutoTuningMultilingualPipeline.ContrastiveResult analyze(String text, String word, String language)
                throws Exception;

        List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses(String word, String language)
                throws Exception;

        default TextRewrite.Result rewrite(TextRewrite.Input input) throws Exception {
            var analysis = analyze(input.text(), null, input.sourceLanguage());
            return TextRewrite.rewrite(input, TextRewrite.plan(input, analysis.nominalSenseComparisons));
        }
    }

    private static final class EndSession extends RuntimeException {
    }

    static void runSession(AnalysisService service, Scanner input) {
        printWelcome();
        while (true) {
            String text = read(input, "Text");
            if (text == null || exit(text))
                break;
            if (text.equalsIgnoreCase("help")) {
                help();
                continue;
            }
            if (text.equalsIgnoreCase("demo") || text.toLowerCase(Locale.ROOT).startsWith("demo ")) {
                try {
                    String requested = text.length() > 4 ? text.substring(4).strip()
                            : read(input,
                                    "Demo language [Enter = all; en/it/fr/es/pt/nl; back]");
                    if (requested == null || exit(requested))
                        break;
                    if (requested.equalsIgnoreCase("back"))
                        continue;
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
                    if (line.equalsIgnoreCase("back") || exit(line))
                        break;
                    pasted.append(line).append('\n');
                }
                if (line == null || exit(line))
                    break;
                if (line.equalsIgnoreCase("back"))
                    continue;
                text = pasted.toString().strip();
            }
            if (!Stopwords.isWord(text)) {
                warning("Enter text containing words.");
                continue;
            }
            try {
                String language = chooseLanguage(service, input, text);
                if (language == null)
                    break;
                if (language.equals("back"))
                    continue;
                if (text.strip().split("\\s+").length == 1) {
                    activity("Opening the sense explorer");
                    showSenses(input, service.senses(text, language));
                    continue;
                }
                printModeMenu();
                while (true) {
                    String mode = read(input, "Mode [1]");
                    if (mode == null || exit(mode))
                        return;
                    if (mode.equalsIgnoreCase("back"))
                        break;
                    if (mode.isEmpty() || mode.equals("1")) {
                        activity("Mapping nominal vocabulary to shared senses");
                        showAnalysis(input, service.analyze(text, null, language));
                        break;
                    }
                    if (mode.equals("3")) {
                        activity("Finding same-sense alternatives");
                        showAlternatives(input, text, language, service.analyze(text, null, language));
                        break;
                    }
                    if (mode.equals("4")) {
                        showRewrite(input, text, language, service);
                        break;
                    }
                    if (!mode.equals("2")) {
                        warning("Choose 1, 2, 3 or 4, or use back.");
                        continue;
                    }
                    String word;
                    while (true) {
                        word = read(input, "Word in your text");
                        if (word == null || exit(word))
                            return;
                        if (word.equalsIgnoreCase("back"))
                            break;
                        if (word.isBlank() || word.split("\\s+").length != 1 || !Stopwords.isWord(word)) {
                            warning("Enter one word, or use back to choose a mode.");
                            continue;
                        }
                        if (Stopwords.forLanguage(language).contains(word.toLowerCase(Locale.ROOT))) {
                            warning("Stopwords are excluded. Choose a content word.");
                            continue;
                        }
                        String target = word;
                        if (Arrays.stream(text.split("(?U)[^\\p{L}\\p{M}'?-]+"))
                                .noneMatch(token -> token.equalsIgnoreCase(target))) {
                            warning("Choose a word that appears in your text.");
                            continue;
                        }
                        activity("Reading \"" + word + "\" in context");
                        showAnalysis(input, service.analyze(text, word, language));
                        break;
                    }
                    if (!word.equalsIgnoreCase("back"))
                        break;
                }
            } catch (EndSession end) {
                break;
            } catch (Exception error) {
                warning("Analysis could not finish: " + error.getMessage());
                System.out.println("Try another text or language. Your session is still open.");
            }
            System.out.println();
        }
        System.out.println(style("\n  Keep listening for the patterns between languages.", MUTED));
        System.out.println(style("  Goodbye.", CYAN));
    }

    private static void showRewrite(Scanner input, String text, String language, AnalysisService service)
            throws Exception {
        String reference;
        while (true) {
            reference = read(input, "Comparison language [en/it/fr/es/pt/nl; back]");
            if (reference == null || exit(reference))
                throw new EndSession();
            if (reference.equalsIgnoreCase("back"))
                return;
            reference = LanguageIdentification.normalizeCode(reference);
            if (LanguageIdentification.supported(reference) && !sameLanguage(reference, language))
                break;
            warning("Choose a supported language different from the source language.");
        }
        String direction;
        while (true) {
            String choice = read(input, "Modification [1 = simpler transparency; 2 = more difficult; back]");
            if (choice == null || exit(choice))
                throw new EndSession();
            if (choice.equalsIgnoreCase("back"))
                return;
            if (choice.isEmpty() || choice.equals("1")) {
                direction = "more";
                break;
            }
            if (choice.equals("2")) {
                direction = "less";
                break;
            }
            warning("Choose 1 or 2, or use back.");
        }
        double threshold;
        while (true) {
            String value = read(input, "Minimum spelling change [0.10]");
            if (value == null || exit(value))
                throw new EndSession();
            if (value.equalsIgnoreCase("back"))
                return;
            try {
                threshold = value.isBlank() ? TextRewrite.DEFAULT_MINIMUM_CHANGE : Double.parseDouble(value);
            } catch (NumberFormatException e) {
                warning("Enter a number from 0 to 1.");
                continue;
            }
            if (Double.isFinite(threshold) && threshold >= 0 && threshold <= 1)
                break;
            warning("Enter a number from 0 to 1.");
        }
        activity("Testing same-sense replacements in context");
        var result = service.rewrite(new TextRewrite.Input(text, language, reference, direction, threshold));
        title("Modified text");
        System.out.println("  " + style(result.text(), CREAM));
        System.out.printf(Locale.ROOT, "\n  %s  %s  %s%n",
                metric("APPLIED", result.appliedChoices() + "/" + result.requestedChoices(), GREEN),
                metric("THRESHOLD", String.format(Locale.ROOT, "%.2f", result.minimumChange()), VIOLET),
                metric("UNITS", Integer.toString(result.consideredUnits()), CYAN));
        result.choices().forEach(c -> {
            String color = "accepted".equals(c.status()) ? GREEN : "rejected".equals(c.status()) ? RED : AMBER;
            System.out.printf(Locale.ROOT, "  %s %s %s %s%n", style(glyph("•", "*"), color),
                    style(c.source(), CREAM), style(glyph("→", "->"), MUTED), style(c.target(), color));
            System.out.printf(Locale.ROOT, "    change %+.2f  %s%n", c.delta(), style(c.status(), color));
        });
        result.warnings().forEach(App::warning);
    }

    private static void showAlternatives(Scanner input, String text, String language,
            AutoTuningMultilingualPipeline.ContrastiveResult result) {
        var units = result.nominalSenseComparisons.stream().filter(s -> s.selected() && s.begin() >= 0
                && s.end() <= text.length() && s.begin() < s.end())
                .sorted(Comparator.comparingInt(AutoTuningMultilingualPipeline.NominalSenseComparison::begin)).toList();
        if (units.isEmpty()) {
            warning("No nominal units with resolved senses were found.");
            return;
        }
        String reference;
        while (true) {
            reference = read(input, "Comparison language [en/it/fr/es/pt/nl; back]");
            if (reference == null || exit(reference))
                throw new EndSession();
            if (reference.equalsIgnoreCase("back"))
                return;
            reference = LanguageIdentification.normalizeCode(reference);
            if (LanguageIdentification.supported(reference) && !sameLanguage(reference, language))
                break;
            warning("Choose a supported language different from the source language.");
        }
        List<WordSubstitution.Scale> available = new ArrayList<>();
        for (var unit : units) {
            try {
                var scale = WordSubstitution.scale(text, unit, language, reference);
                if (!scale.candidates().isEmpty())
                    available.add(scale);
            } catch (IllegalArgumentException unavailable) {
            }
        }
        if (available.isEmpty()) {
            warning("No recognized occurrence has same-sense alternatives for this comparison language.");
            return;
        }
        title("Choose an occurrence");
        for (int i = 0; i < available.size(); i++) {
            var scale = available.get(i);
            System.out.printf(Locale.ROOT, "  %d. %s (characters %d-%d): %s | %d alternative%s%n", i + 1,
                    scale.original(), scale.begin(), scale.end(), scale.gloss(), scale.candidates().size(),
                    scale.candidates().size() == 1 ? "" : "s");
        }
        int index;
        while (true) {
            String choice = read(input, "Occurrence number [back]");
            if (choice == null || exit(choice))
                throw new EndSession();
            if (choice.equalsIgnoreCase("back"))
                return;
            try {
                index = Integer.parseInt(choice) - 1;
            } catch (NumberFormatException e) {
                warning("Choose one of the listed occurrence numbers.");
                continue;
            }
            if (index >= 0 && index < available.size())
                break;
            warning("Choose one of the listed occurrence numbers.");
        }
        var scale = SubstitutionDecoder.validate(text, available.get(index));
        System.out.printf(Locale.ROOT, "  %s %s  %s  reference %s%n", style("ORIGINAL", MUTED),
                style(scale.original(), CREAM), style(String.format(Locale.ROOT, "%.2f", scale.baseline()), CYAN),
                style(scale.referenceForm(), VIOLET));
        warning("Model judgments are suggestions, not guarantees; review grammar and meaning before use.");
        System.out.println("  Validation: " + scale.validation());
        for (int i = 0; i < scale.candidates().size(); i++) {
            var candidate = scale.candidates().get(i);
            System.out.printf(Locale.ROOT, "  %s %s  %s %s%n", numberBadge(i + 1),
                    style(candidate.replacement(), CREAM),
                    style(String.format(Locale.ROOT, "%.2f", candidate.transparency()),
                            TerminalColors.scoreColor(candidate.transparency())),
                    style(String.format(Locale.ROOT, "%+.2f", candidate.change()), VIOLET));
            System.out.println(style("     Context: ", MUTED) + candidate.validation());
        }
        String selected = read(input, "Apply candidate number [Enter = none]");
        if (selected == null || exit(selected))
            throw new EndSession();
        if (selected.isEmpty() || selected.equalsIgnoreCase("back"))
            return;
        try {
            index = Integer.parseInt(selected) - 1;
        } catch (NumberFormatException e) {
            warning("Invalid candidate.");
            return;
        }
        if (index < 0 || index >= scale.candidates().size()) {
            warning("Invalid candidate.");
            return;
        }
        System.out.println("\n  " + style("REVISED TEXT", GREEN));
        System.out.println("  " + style(text.substring(0, scale.begin()), MUTED)
                + style(scale.candidates().get(index).replacement(), GREEN)
                + style(text.substring(scale.end()), MUTED));
    }

    private static String chooseLanguage(AnalysisService service, Scanner input, String text) {
        var detected = service.detect(text);
        title("Language");
        System.out.printf(Locale.ROOT, "  %s  %s  %s%n", style("SUGGESTED", MUTED),
                style(LanguageIdentification.label(detected.code()), GREEN),
                metric("MODEL", String.format(Locale.ROOT, "%.3f", detected.confidence()), CYAN));
        if (detected.uncertain()) {
            warning(detected.mixedLanguage()
                    ? "Several sentence languages detected. Analyze each language separately for best results."
                    : "Language is uncertain; short or mixed-language text may need a manual choice.");
            System.out.println("  " + style("ALTERNATIVES", MUTED) + "  " + detected.alternatives().stream()
                    .map(c -> style(LanguageIdentification.label(c.code()), languageColor(c.code())) + " "
                            + style(String.format(Locale.ROOT, "%.3f", c.confidence()), MUTED))
                    .collect(java.util.stream.Collectors.joining(style(glyph("  ·  ", "  |  "), MUTED))));
        }
        boolean supported = LanguageIdentification.supported(detected.code());
        if (!supported)
            warning("This language has no bundled NLP models. Select a supported language to continue.");
        System.out.println("  " + style("LANGUAGES", MUTED) + "  "
                + languageChip("en", "eng") + " " + languageChip("it", "ita") + " "
                + languageChip("fr", "fra") + " " + languageChip("es", "spa") + " "
                + languageChip("pt", "por") + " " + languageChip("nl", "nld"));
        while (true) {
            String choice = read(input, supported ? "Language [Enter = suggested]" : "Language code");
            if (choice == null || exit(choice))
                return null;
            if (choice.equalsIgnoreCase("back"))
                return "back";
            if (choice.isEmpty() && supported)
                return detected.code();
            String normalized = LanguageIdentification.normalizeCode(choice);
            if (LanguageIdentification.supported(normalized))
                return normalized;
            warning("Choose en, it, fr, es, pt or nl; use back to change the text.");
        }
    }

    private static void showAnalysis(Scanner input, AutoTuningMultilingualPipeline.ContrastiveResult result) {
        showAnalysis(input, result, true);
    }

    private static void showAnalysis(Scanner input, AutoTuningMultilingualPipeline.ContrastiveResult result,
            boolean interactive) {
        title(result.chosenWord == null ? "Whole-text comparison" : "Word: " + result.chosenWord);
        var coverage = result.nominalAggregate;
        String coverageColor = TerminalColors.scoreColor(coverage.totalAggregateScore);
        System.out.println("  " + metric("COVERAGE", String.format(Locale.ROOT, "%.2f", coverage.totalAggregateScore), coverageColor)
                + "  " + style(bar(coverage.totalAggregateScore), coverageColor));
        System.out.printf("  %s  %s", metric("WORDS", coverage.consideredWords + "/" + coverage.totalWords, CYAN),
                result.chosenWord == null
                        ? metric("OMW UNITS", result.analyzedNominalUnits + "/" + coverage.totalNominalUnits, VIOLET)
                        : "");
        System.out.println();
        System.out.println(style("  Coverage counts nouns; transparency measures spelling similarity.\n", MUTED));
        var rows = result.transparencyResults.stream()
                .filter(r -> r.lang != null && !sameLanguage(r.lang, result.detectedLanguage))
                .sorted(Comparator.comparingDouble((TransparencyResult r) -> r.score).reversed()
                        .thenComparing(r -> r.lang).thenComparing(r -> r.lemma))
                .toList();
        if (result.chosenWord != null && result.chosenSense != null) {
            System.out.println("  " + style("RECOGNIZED SENSE", GREEN) + style("  OMW ", MUTED)
                    + style(result.chosenSense.offset + "-" + result.chosenSense.pos, CYAN));
            System.out.println("  " + style(result.chosenWord, VIOLET) + style(glyph("  —  ", "  -  "), MUTED)
                    + style(result.chosenSense.gloss, CREAM) + "\n");
        }
        if (rows.isEmpty())
            warning("No cross-language matches found. Try another word or language.");
        else {
            printRows(rows.subList(0, Math.min(PAGE_SIZE, rows.size())), result.chosenWord != null);
            if (interactive && rows.size() > PAGE_SIZE && yes(input, "Show all " + rows.size() + " results? [y/N]"))
                printRows(rows.subList(PAGE_SIZE, rows.size()), result.chosenWord != null);
        }
        if (interactive && result.chosenWord == null
                && (!result.nominalLanguageTransparencies.isEmpty() || !result.nominalSenseComparisons.isEmpty())
                && yes(input, "Show word-by-word details? [y/N]")) {
            title("Word-by-word details");
            if (!result.nominalSenseComparisons.isEmpty()) {
                printNominalSenses(result.nominalSenseComparisons, result.detectedLanguage);
            } else {
                String current = "";
                for (var detail : result.nominalLanguageTransparencies) {
                    if (!detail.nominalUnit.equals(current)) {
                        current = detail.nominalUnit;
                        System.out.println("\n  " + style(glyph("◆ ", "* ") + current, VIOLET));
                    }
                    System.out.printf("  %s %s %s%n", languageCell(detail.language),
                            style(String.format("%-28s", clip(detail.lemma, 28)), CREAM),
                            style(String.format(Locale.ROOT, "%.2f", detail.score),
                                    TerminalColors.scoreColor(detail.score)));
                }
            }
        }
    }

    private record SenseWords(AutoTuningMultilingualPipeline.NominalSenseComparison sense,
            List<TransparencyResult> words) {
    }

    private static void printNominalSenses(List<AutoTuningMultilingualPipeline.NominalSenseComparison> comparisons,
            String sourceLanguage) {
        Map<Integer, Map<String, List<SenseWords>>> units = new TreeMap<>();
        for (var sense : comparisons) {
            Map<String, List<TransparencyResult>> translations = new TreeMap<>();
            for (var row : sense.translations()) {
                if (row.lang != null && !sameLanguage(row.lang, sourceLanguage))
                    translations
                            .computeIfAbsent(LanguageIdentification.normalizeCode(row.lang), key -> new ArrayList<>())
                            .add(row);
            }
            for (var entry : translations.entrySet()) {
                units.computeIfAbsent(sense.begin(), key -> new TreeMap<>())
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(new SenseWords(sense, entry.getValue()));
            }
        }
        System.out.println(style("  Recognized senses first" + glyph(" · ", " | ")
                + "applicability measures context" + glyph(" · ", " | ") + "similarity measures spelling.", MUTED));
        for (var languages : units.values()) {
            System.out
                    .println("\n  " + style(glyph("◆ ", "* ")
                            + languages.values().iterator().next().get(0).sense().nominalUnit(), VIOLET));
            for (var entry : languages.entrySet()) {
                System.out.println("\n  " + languageCell(entry.getKey()));
                entry.getValue().sort(Comparator.comparing((SenseWords row) -> row.sense().selected()).reversed()
                        .thenComparing(Comparator.comparingDouble((SenseWords row) -> row.sense().applicabilityScore())
                                .reversed())
                        .thenComparing(row -> row.sense().synsetOffset())
                        .thenComparing(row -> row.sense().synsetPos()));
                for (var row : entry.getValue()) {
                    var sense = row.sense();
                    System.out.printf(Locale.ROOT, "    %s | applicability %.2f%n",
                            style(sense.selected() ? glyph("● ", "* ") + "Recognized sense"
                                            : glyph("○ ", "- ") + "Candidate sense",
                                    sense.selected() ? GREEN : MUTED),
                            sense.applicabilityScore());
                    System.out.println("    " + sense.gloss());
                    for (var word : row.words())
                        System.out.printf(Locale.ROOT, "      %-28s similarity %.2f%n", word.lemma, word.score);
                }
            }
        }
    }

    private static void printRows(List<TransparencyResult> rows, boolean showLemma) {
        System.out.println(style(String.format("  %-21s %6s  %s", "LANGUAGE", "SCORE",
                showLemma ? "TRANSLATION" : "TRANSPARENCY"), CYAN));
        System.out.println(style("  " + glyph("─", "-").repeat(65), MUTED));
        for (var row : rows)
            System.out.printf("  %s %s  %s%n", languageCell(row.lang),
                    style(String.format(Locale.ROOT, "%6.2f", row.score), TerminalColors.scoreColor(row.score)),
                    showLemma ? style(clip(row.lemma, 32), CREAM)
                            : style(bar(row.score), TerminalColors.scoreColor(row.score)));
    }

    private static void showSenses(Scanner input, List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses) {
        if (senses.isEmpty()) {
            warning("No OMW senses found. Try another language or word form.");
            return;
        }
        title("Sense explorer | " + senses.size() + " senses");
        for (int i = 0; i < senses.size(); i++) {
            if (i > 0 && i % 3 == 0 && !yes(input, "Show more senses? [y/N]"))
                break;
            var sense = senses.get(i);
            System.out.println("\n  " + numberBadge(i + 1) + " " + style("SENSE", VIOLET) + "  "
                    + style("OMW " + sense.synsetOffset + "-" + sense.synsetPos, CYAN));
            System.out.println(
                    "  " + (sense.gloss == null || sense.gloss.isBlank() ? "No definition available." : sense.gloss));
            Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
            sense.translations.stream().filter(r -> r.lang != null && !sameLanguage(r.lang, sense.sourceLanguage))
                    .sorted(Comparator.comparing(r -> r.lang))
                    .forEach(r -> grouped.computeIfAbsent(r.lang, key -> new LinkedHashSet<>()).add(r.lemma));
            var entries = grouped.entrySet().stream().toList();
            printTranslations(entries.subList(0, Math.min(PAGE_SIZE, entries.size())));
            if (entries.size() > PAGE_SIZE && yes(input, "Show all translation languages? [y/N]"))
                printTranslations(entries.subList(PAGE_SIZE, entries.size()));
        }
    }

    private static void printTranslations(List<Map.Entry<String, LinkedHashSet<String>>> entries) {
        for (var entry : entries)
            System.out.printf("  %s %s%n", languageCell(entry.getKey()),
                    style(clip(String.join(", ", entry.getValue()), 46), CREAM));
    }

    private static String languageCell(String code) {
        return style(String.format("%-21s", clip(LanguageIdentification.label(code), 21)), languageColor(code));
    }

    private static String languageColor(String code) {
        return switch (LanguageIdentification.normalizeCode(code)) {
            case "eng" -> CYAN;
            case "ita" -> GREEN;
            case "fra" -> "1;38;2;111;174;255";
            case "spa" -> RED;
            case "por" -> VIOLET;
            case "nld" -> "1;38;2;255;153;102";
            default -> CREAM;
        };
    }

    private static boolean sameLanguage(String a, String b) {
        return LanguageIdentification.normalizeCode(a).equals(LanguageIdentification.normalizeCode(b));
    }

    static String bar(double score) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, score)) * 16);
        return TerminalColors.unicodeEnabled()
                ? "▰".repeat(filled) + "▱".repeat(16 - filled)
                : "[" + "#".repeat(filled) + ".".repeat(16 - filled) + "]";
    }

    static String clip(String value, int width) {
        if (value == null)
            return "";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        return clean.length() <= width ? clean : clean.substring(0, width - 3) + "...";
    }

    private static String read(Scanner input, String prompt) {
        System.out.print("  " + style(glyph("›", ">"), RED) + " " + style(prompt, CYAN) + "  ");
        System.out.flush();
        return input.hasNextLine() ? input.nextLine().strip() : null;
    }

    private static boolean yes(Scanner input, String prompt) {
        while (true) {
            String answer = read(input, prompt);
            if (exit(answer))
                throw new EndSession();
            if (answer == null || answer.isEmpty() || "n".equalsIgnoreCase(answer) || "no".equalsIgnoreCase(answer)
                    || "back".equalsIgnoreCase(answer))
                return false;
            if ("y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer))
                return true;
            warning("Enter y or n, or press Enter for no.");
        }
    }

    private static boolean exit(String text) {
        return "exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text);
    }

    private static void title(String title) {
        int tail = Math.max(4, 66 - Math.min(62, title.length()));
        if (TerminalColors.unicodeEnabled()) {
            System.out.println("\n  " + style("╭─", MUTED) + " " + style(title, CYAN)
                    + " " + style("─".repeat(tail) + "╮", MUTED));
        } else {
            System.out.println("\n  -- " + title + " " + "-".repeat(tail));
        }
    }

    private static void warning(String text) {
        System.out.println(style("  " + glyph("⚠", "!") + " ", AMBER) + style(text, AMBER));
    }

    private static String style(String text, String code) {
        return TerminalColors.style(text, code);
    }

    private static String glyph(String rich, String plain) {
        return TerminalColors.unicodeEnabled() ? rich : plain;
    }

    private static void printWelcome() {
        System.out.println("  " + pill("EXPLORE", CYAN) + " "
                + style("Compare written forms through shared meanings.", CREAM));
        System.out.println(style("  Paste text to begin, or use a shortcut below.", MUTED));
        String separator = style(glyph(" · ", " | "), MUTED);
        System.out.println("  " + style("demo", GREEN) + separator
                + style("paste", CYAN) + separator
                + style("help", VIOLET) + separator + style("exit", RED) + "\n");
    }

    private static void printModeMenu() {
        title("Choose an exploration");
        System.out.println("  " + numberBadge(1) + " " + style("Compare the whole text", GREEN)
                + style("  default", MUTED));
        System.out.println("      " + style("Map nominal vocabulary across languages.", MUTED));
        System.out.println("  " + numberBadge(2) + " " + style("Focus on one word", CYAN));
        System.out.println("      " + style("Use its sentence to select a meaning.", MUTED));
        System.out.println("  " + numberBadge(3) + " " + style("Explore alternatives", VIOLET));
        System.out.println("      " + style("Compare same-sense forms and spelling distance.", MUTED));
        System.out.println("  " + numberBadge(4) + " " + style("Reshape the text", RED));
        System.out.println("      " + style("Try contextual same-language replacements.", MUTED));
    }

    private static void activity(String text) {
        System.out.println("\n  " + style(glyph("◌", "*"), CYAN) + " " + style(text, CREAM)
                + style(glyph("  ·  ", "  |  ") + "this may take a moment", MUTED));
    }

    private static String metric(String label, String value, String color) {
        return style(label + " ", MUTED) + style(value, color);
    }

    private static String pill(String text, String color) {
        return style(" " + text + " ", color);
    }

    private static String numberBadge(int number) {
        return style("[" + number + "]", CYAN);
    }

    private static String languageChip(String label, String code) {
        return style(" " + label + " ", languageColor(code));
    }

    private static void helpRow(String command, String explanation, String color) {
        System.out.println("  " + style(String.format("%-7s", command), color) + style(explanation, MUTED));
    }

    private static void help() {
        title("Help & shortcuts");
        System.out.println("  " + style("Write naturally", CREAM) + style("  Enter a passage, sentence, or one word.", MUTED));
        helpRow("demo", "Run all six demos, or choose one: demo it", GREEN);
        helpRow("paste", "Paste multiple lines; finish with a single '.'", CYAN);
        helpRow("back", "Return from the current choice", VIOLET);
        helpRow("exit", "End the session; 'quit' also works", RED);
        System.out.println(style("\n  Tip: press Enter whenever the highlighted default feels right.\n", MUTED));
    }

    static void runDemo(AnalysisService service, String language) throws Exception {
        var samples = DemoTexts.SAMPLES.stream().filter(s -> language == null
                || s.language().equals(LanguageIdentification.normalizeCode(language))).toList();
        if (samples.isEmpty())
            throw new IllegalArgumentException("Demo language must be en, it, fr, es, pt or nl.");
        title("European languages demo");
        System.out.println(style("  Parallel passages about nature, education, markets, and community.", CREAM));
        System.out.println(style("  Each sample reveals detection, coverage, and one contextual OMW sense.", MUTED));
        int correct = 0;
        List<String> summary = new ArrayList<>();
        try (Scanner noPrompts = new Scanner("")) {
            for (var sample : samples) {
                title(LanguageIdentification.label(sample.language()));
                StringBuilder line = new StringBuilder();
                for (String word : sample.text().split("\\s+")) {
                    if (line.length() + word.length() + 1 > 70) {
                        System.out.println("  " + style(line.toString(), CREAM));
                        line.setLength(0);
                    }
                    if (!line.isEmpty())
                        line.append(' ');
                    line.append(word);
                }
                if (!line.isEmpty())
                    System.out.println("  " + style(line.toString(), CREAM));
                var detected = service.detect(sample.text());
                boolean matched = sameLanguage(sample.language(), detected.code());
                if (matched)
                    correct++;
                System.out.printf(Locale.ROOT, "\n  %s %s  %s  %s%n", style("DETECTED", MUTED),
                        style(LanguageIdentification.label(detected.code()), matched ? GREEN : AMBER),
                        metric("MODEL", String.format(Locale.ROOT, "%.3f", detected.confidence()), CYAN),
                        metric("EXPECTED", sample.language(), VIOLET));
                if (detected.uncertain())
                    warning("Detection is uncertain; inspect the language choice.");
                if (!LanguageIdentification.supported(detected.code())) {
                    warning("Unsupported detection; skipping analysis for this sample.");
                    continue;
                }
                activity("Tracing the " + LanguageIdentification.label(sample.language()) + " passage");
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
        summary.forEach(row -> System.out.println(style(row, CREAM)));
        System.out.println(style("  These examples demonstrate behavior; they are not an accuracy benchmark.", MUTED));
    }

    public static void main(String[] args) {
        if (args.length > 0 && (!args[0].equals("--demo") || args.length > 2)) {
            System.err.println("Usage: Nnois [--demo [en|it|fr|es|pt|nl]]");
            return;
        }
        Banner.print();
        try (var pipeline = new AutoTuningMultilingualPipeline("omw/omw_multilingual.db", "langdetect-183.bin");
                Scanner input = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8)) {
            AnalysisService service = new AnalysisService() {
                public LanguageIdentification.Detection detect(String text) {
                    return pipeline.detectLanguage(text);
                }

                public AutoTuningMultilingualPipeline.ContrastiveResult analyze(String text, String word,
                        String language) throws Exception {
                    return pipeline.processContrastive(text, word, language, false);
                }

                public List<AutoTuningMultilingualPipeline.SenseTranslationResult> senses(String word, String language)
                        throws Exception {
                    return pipeline.lookupWordSensesAndTranslations(word, language);
                }
            };
            if (args.length > 0)
                runDemo(service, args.length == 2 ? args[1] : null);
            else
                runSession(service, input);
        } catch (Exception error) {
            System.err.println("Nnois could not start: " + error.getMessage());
        }
    }
}
