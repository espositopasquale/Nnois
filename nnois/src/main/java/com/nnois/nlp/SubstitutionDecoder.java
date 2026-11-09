package com.nnois.nlp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the optional local generative model; failures leave suggestions unverified. */
public final class SubstitutionDecoder {
    private static final Gson JSON = new Gson();
    private static final int LIMIT = 12;
    private SubstitutionDecoder() { }

    public static WordSubstitution.Scale validate(String text, WordSubstitution.Scale scale) {
        if (scale.candidates().isEmpty()) return scale;
        Path root = locateRoot();
        if (root == null) return scale;
        Path model = configuredPath("NNOIS_SUBSTITUTION_MODEL_DIR", root.resolve("models/substitution-decoder-onnx"));
        Path python = configuredPath("NNOIS_SUBSTITUTION_PYTHON", root.resolve(System.getProperty("os.name").toLowerCase().contains("win")
                ? ".venv-decoder/Scripts/python.exe" : ".venv-decoder/bin/python"));
        Path script = root.resolve("nnois/substitution_decoder.py");
        if (!Files.isRegularFile(model.resolve("genai_config.json")) || !Files.isRegularFile(python) || !Files.isRegularFile(script)) return scale;
        int count = Math.min(scale.candidates().size(), LIMIT);
        int begin = Math.max(0, scale.begin() - 1000), end = Math.min(text.length(), scale.end() + 1000);
        var request = Map.of("text", text.substring(begin, scale.begin()) + "[[TARGET]]" + scale.original() + "[[/TARGET]]" + text.substring(scale.end(), end), "original", scale.original(),
                "gloss", scale.gloss() == null ? "" : scale.gloss(),
                "candidates", scale.candidates().subList(0, count).stream().map(WordSubstitution.Candidate::replacement).toList());
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(python.toString(), script.toString(), model.toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            try (var stdin = process.getOutputStream()) { stdin.write(JSON.toJson(request).getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); return scale; }
            if (process.exitValue() != 0) return scale;
            String response = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonArray checks = JsonParser.parseString(response).getAsJsonArray();
            if (checks.size() != count) return scale;
            List<WordSubstitution.Candidate> updated = new ArrayList<>();
            for (int i = 0; i < scale.candidates().size(); i++) {
                var c = scale.candidates().get(i);
                String status = i < count ? checks.get(i).getAsString() : "unverified";
                if (!List.of("accepted", "rejected", "unverified").contains(status)) return scale;
                updated.add(new WordSubstitution.Candidate(c.replacement(), c.transparency(), c.change(), c.referenceForm(), status));
            }
            boolean any = updated.stream().anyMatch(c -> !c.validation().equals("unverified"));
            return new WordSubstitution.Scale(scale.original(), scale.begin(), scale.end(), scale.gloss(), scale.baseline(),
                    scale.referenceForm(), any ? "model-assessed" : "unverified", List.copyOf(updated));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return scale;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return scale;
        }
    }
    private static Path configuredPath(String key, Path fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : Path.of(value).toAbsolutePath().normalize();
    }
    private static Path locateRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("nnois/substitution_decoder.py"))) return path;
            path = path.getParent();
        }
        return null;
    }
}
