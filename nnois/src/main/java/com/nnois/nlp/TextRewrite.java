package com.nnois.nlp;

import com.google.gson.*;
import com.nnois.nlp.AutoTuningMultilingualPipeline.NominalSenseComparison;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Selects same-sense synonyms in the source language, then checks each substitution in context. */
public final class TextRewrite {
    private static final Gson JSON = new Gson();
    private static final int MAX_TEXT = 1000;
    private static final int MAX_CHOICES = 100;
    public static final double DEFAULT_MINIMUM_CHANGE = 0.10;
    private TextRewrite() { }
    public record Input(String text, String sourceLanguage, String targetLanguage, String direction, Double minimumChange) {
        public Input(String text, String sourceLanguage, String targetLanguage, String direction) {
            this(text, sourceLanguage, targetLanguage, direction, null);
        }
    }
    public record Choice(String source, String target, double score, double baseline, double delta,
                         String referenceForm, String originalReferenceForm, String gloss, String status,
                         int alternatives, int begin, int end) { }
    public record Result(String text, String sourceLanguage, String targetLanguage, String direction, double minimumChange,
                         List<Choice> choices, int requestedChoices, int appliedChoices,
                         int consideredUnits, int unavailableUnits, List<String> warnings, String model) { }
    public record Plan(List<Choice> choices, int consideredUnits, int unavailableUnits, boolean truncated) { }
    private record Scored(String text, String reference, double score) { }

    public static Input validate(Input input) {
        if (input == null || input.text() == null || input.text().isBlank() || input.text().length() > MAX_TEXT
                || !input.text().matches("(?s).*\\p{L}.*"))
            throw new IllegalArgumentException("Enter text with words (maximum " + MAX_TEXT + " characters).");
        String source = LanguageIdentification.normalizeCode(input.sourceLanguage());
        String target = LanguageIdentification.normalizeCode(input.targetLanguage());
        if (!LanguageIdentification.supported(source))
            throw new IllegalArgumentException("Choose a supported source language.");
        if (!LanguageIdentification.supported(target)) throw new IllegalArgumentException("Choose a supported comparison language.");
        if (source.equals(target)) throw new IllegalArgumentException("Source and comparison languages must be different.");
        if (!"more".equals(input.direction()) && !"less".equals(input.direction()))
            throw new IllegalArgumentException("Choose more or less similarity.");
        double minimumChange = input.minimumChange() == null ? DEFAULT_MINIMUM_CHANGE : input.minimumChange();
        if (!Double.isFinite(minimumChange) || minimumChange < 0 || minimumChange > 1)
            throw new IllegalArgumentException("Minimum change must be between 0 and 1.");
        return new Input(input.text(), source, target, input.direction(), minimumChange);
    }

    /** Calculates the closest reference form per source synonym from one selected OMW synset. */
    public static Plan plan(Input supplied, List<NominalSenseComparison> senses) {
        Input input = validate(supplied);
        List<NominalSenseComparison> selected = senses.stream().filter(s -> s.selected() && s.begin() >= 0
                && s.end() <= input.text().length() && s.begin() < s.end())
                .sorted(Comparator.comparingInt(NominalSenseComparison::begin)
                        .thenComparing(Comparator.comparingInt((NominalSenseComparison s) -> s.end() - s.begin()).reversed())
                        .thenComparing(s -> Objects.toString(s.synsetOffset(), ""))).toList();
        List<Choice> choices = new ArrayList<>();
        int previousEnd = -1, considered = 0, unavailable = 0;
        for (var sense : selected) {
            if (sense.begin() < previousEnd) continue;
            previousEnd = sense.end();
            considered++;
            String original = input.text().substring(sense.begin(), sense.end());
            Map<String,String> sourceForms = new TreeMap<>(), referenceForms = new TreeMap<>();
            for (var row : sense.translations()) {
                if (row.lang == null || row.lemma == null) continue;
                String form = row.lemma.replace('_', ' ').strip().replaceAll("\\s+", " ");
                if (form.isBlank()) continue;
                String code = LanguageIdentification.normalizeCode(row.lang);
                Map<String,String> dest = code.equals(input.sourceLanguage()) ? sourceForms
                        : code.equals(input.targetLanguage()) ? referenceForms : null;
                if (dest != null) dest.merge(normalize(form), form, (a,b) -> a.compareTo(b) <= 0 ? a : b);
            }
            if (referenceForms.isEmpty()) { unavailable++; continue; }
            Scored originalScore = score(original, referenceForms.values());
            List<Scored> ranked = sourceForms.values().stream().filter(form -> !normalize(form).equals(normalize(original)))
                    .map(form -> score(form, referenceForms.values()))
                    .filter(c -> {
                        double change = "more".equals(input.direction())
                                ? c.score() - originalScore.score() : originalScore.score() - c.score();
                        return change > 1e-9 && change + 1e-9 >= input.minimumChange();
                    })
                    .sorted((a,b) -> {
                        int order = Double.compare(a.score(), b.score());
                        if ("more".equals(input.direction())) order = -order;
                        return order != 0 ? order : normalize(a.text()).compareTo(normalize(b.text()));
                    }).toList();
            if (ranked.isEmpty()) { unavailable++; continue; }
            Scored best = ranked.get(0);
            String replacement = matchCase(original, best.text());
            choices.add(new Choice(original, replacement, best.score(), originalScore.score(),
                    best.score() - originalScore.score(), best.reference(), originalScore.reference(),
                    Objects.toString(sense.gloss(), ""), "unverified", ranked.size(), sense.begin(), sense.end()));
        }
        boolean truncated = choices.size() > MAX_CHOICES;
        return new Plan(List.copyOf(choices.subList(0, Math.min(MAX_CHOICES, choices.size()))), considered, unavailable, truncated);
    }
    private static Scored score(String form, Collection<String> references) {
        return references.stream().map(ref -> new Scored(form, ref, similarity(form, ref)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> normalize(s.reference())))
                .findFirst().orElseThrow();
    }
    private static String matchCase(String original, String value) {
        if (original.codePoints().anyMatch(Character::isLetter) && original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT))) return value.toUpperCase(Locale.ROOT);
        if (!original.isEmpty() && Character.isUpperCase(original.codePointAt(0)) && !value.isEmpty()) {
            int end = value.offsetByCodePoints(0, 1); return value.substring(0, end).toUpperCase(Locale.ROOT) + value.substring(end);
        }
        return value;
    }

    public static Result rewrite(Input supplied, Plan plan) {
        Input input = validate(supplied);
        if (plan.choices().isEmpty()) return complete(input, plan, List.of(), null);
        Path root = locateRoot();
        if (root == null) return complete(input, plan, null, "Context checker script not found; no substitutions were applied.");
        Path script = Files.isRegularFile(root.resolve("nnois/rewrite_decoder.py")) ? root.resolve("nnois/rewrite_decoder.py") : root.resolve("rewrite_decoder.py");
        Path model = configured("NNOIS_SUBSTITUTION_MODEL_DIR", root.resolve("models/substitution-decoder-onnx"));
        Path python = configured("NNOIS_SUBSTITUTION_PYTHON", root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".venv-decoder/Scripts/python.exe" : ".venv-decoder/bin/python"));
        if (!Files.isRegularFile(model.resolve("genai_config.json")) || !Files.isRegularFile(python))
            return complete(input, plan, null, "Context checker model or Python is unavailable; no substitutions were applied.");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(python.toString(), script.toString(), model.toString());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            final Process active = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try { return active.getInputStream().readNBytes(100_001); }
                catch (java.io.IOException e) { throw new CompletionException(e); }
            });
            List<Map<String,Object>> terms = plan.choices().stream().map(c -> Map.<String,Object>of(
                    "source", c.source(), "target", c.target(), "gloss", c.gloss(),
                    "begin", input.text().codePointCount(0, c.begin()), "end", input.text().codePointCount(0, c.end()))).toList();
            try (var stdin = process.getOutputStream()) {
                stdin.write(JSON.toJson(Map.of(
                        "text", input.text(),
                        "source_language", input.sourceLanguage(),
                        "choices", terms)).getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(240, TimeUnit.SECONDS)) throw new IllegalStateException("Context checker timed out; no substitutions were applied.");
            if (process.exitValue() != 0) throw new IllegalStateException("Context checker failed; no substitutions were applied.");
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (bytes.length > 100_000) throw new IllegalStateException("Context checker returned too much data; no substitutions were applied.");
            return complete(input, plan, parse(new String(bytes, StandardCharsets.UTF_8), plan.choices().size()), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return complete(input, plan, null, "Context checking was interrupted; no substitutions were applied.");
        } catch (Exception e) {
            return complete(input, plan, null, "Context checker unavailable or returned an invalid response; no substitutions were applied.");
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    static List<String> parse(String raw, int expected) {
        try {
            JsonArray items = JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("statuses");
            if (items == null || items.size() != expected) throw new IllegalStateException();
            List<String> results = new ArrayList<>();
            for (var item : items) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalStateException();
                String status = item.getAsString();
                if (!List.of("accepted", "rejected", "unverified").contains(status)) throw new IllegalStateException();
                results.add(status);
            }
            return results;
        } catch (Exception e) { throw new IllegalStateException("Invalid checker response.", e); }
    }
    public static Result complete(Input supplied, Plan plan, List<String> statuses, String issue) {
        Input input = validate(supplied);
        boolean valid = statuses != null && statuses.size() == plan.choices().size()
                && statuses.stream().allMatch(s -> List.of("accepted", "rejected", "unverified").contains(s));
        List<Choice> choices = new ArrayList<>();
        StringBuilder revised = new StringBuilder(input.text());
        for (int i = 0; i < plan.choices().size(); i++) {
            Choice c = plan.choices().get(i);
            String status = valid ? statuses.get(i) : "unverified";
            choices.add(new Choice(c.source(), c.target(), c.score(), c.baseline(), c.delta(), c.referenceForm(),
                    c.originalReferenceForm(), c.gloss(), status, c.alternatives(), c.begin(), c.end()));
        }
        for (int i = choices.size() - 1; i >= 0; i--) {
            Choice c = choices.get(i);
            if ("accepted".equals(c.status())) revised.replace(c.begin(), c.end(), c.target());
        }
        int applied = (int) choices.stream().filter(c -> c.status().equals("accepted")).count();
        List<String> warnings = new ArrayList<>();
        warnings.add("Context judgments and sense selection are model estimates; review meaning and grammar.");
        warnings.add("Scores compare spelling to forms of the selected sense, not overall reading difficulty.");
        if (issue != null) warnings.add(issue);
        else if (!valid) warnings.add("Invalid context judgments; no substitutions were applied.");
        if (choices.stream().anyMatch(c -> c.status().equals("unverified"))) warnings.add("Unverified candidates were not applied.");
        if (plan.consideredUnits() == 0) warnings.add("No nominal units with resolved senses were found.");
        if (plan.unavailableUnits() > 0) warnings.add("Some units have no reference forms or no same-sense synonym that reaches the minimum change in the requested direction.");
        if (plan.truncated()) warnings.add("Only the first " + MAX_CHOICES + " eligible units were checked.");
        return new Result(revised.toString(), input.sourceLanguage(), input.targetLanguage(), input.direction(), input.minimumChange(), List.copyOf(choices),
                choices.size(), applied, plan.consideredUnits(), plan.unavailableUnits(), List.copyOf(warnings), "Local ONNX decoder (default: Qwen2.5-1.5B-Instruct)");
    }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    static double similarity(String first, String second) {
        int[] a = normalize(first).codePoints().toArray(), b = normalize(second).codePoints().toArray(), previous = new int[b.length+1];
        for (int j = 0; j <= b.length; j++) previous[j] = j;
        for (int i = 1; i <= a.length; i++) {
            int[] next = new int[b.length+1]; next[0] = i;
            for (int j = 1; j <= b.length; j++) next[j] = Math.min(Math.min(next[j-1]+1,previous[j]+1),previous[j-1]+(a[i-1]==b[j-1]?0:1));
            previous = next;
        }
        return Math.max(a.length,b.length)==0 ? 1 : 1.0-(double) previous[b.length]/Math.max(a.length,b.length);
    }
    private static Path configured(String name, Path fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : Path.of(value).toAbsolutePath().normalize();
    }
    private static Path locateRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            if (Files.isRegularFile(root.resolve("nnois/rewrite_decoder.py")) || Files.isRegularFile(root.resolve("rewrite_decoder.py"))) return root;
            root = root.getParent();
        }
        return null;
    }
}
