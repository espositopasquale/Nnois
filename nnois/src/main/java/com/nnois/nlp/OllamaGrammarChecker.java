package com.nnois.nlp;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Asks a local decoder model (Ollama) to rate the grammatical coherence of a text from 0 to 1. */
public final class OllamaGrammarChecker implements WordSubstitution.CoherenceChecker {

    private final URI endpoint;
    private final String model;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public OllamaGrammarChecker(URI endpoint, String model) {
        this.endpoint = endpoint;
        this.model = model;
    }

    /** Returns null unless NNOIS_GRAMMAR_MODEL is set; NNOIS_GRAMMAR_ENDPOINT overrides the default URL. */
    public static OllamaGrammarChecker configured() {
        String model = System.getenv("NNOIS_GRAMMAR_MODEL");
        if (model == null || model.isBlank()) return null;
        String endpoint = System.getenv("NNOIS_GRAMMAR_ENDPOINT");
        return new OllamaGrammarChecker(URI.create(endpoint == null || endpoint.isBlank()
                ? "http://localhost:11434/api/generate" : endpoint), model);
    }

    @Override
    public double score(String text) throws Exception {
        String prompt = "You are a strict grammar judge. Rate how grammatically correct, fluent and coherent the "
                + "following text is, even if it mixes languages. Answer only with JSON like {\"score\": 0.0} "
                + "where score is between 0 (incoherent) and 1 (perfectly coherent).\n\nText:\n" + text;
        String body = new Gson().toJson(Map.of("model", model, "prompt", prompt, "stream", false,
                "format", "json", "options", Map.of("temperature", 0)));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Decoder server returned HTTP " + response.statusCode());
        String generated = JsonParser.parseString(response.body()).getAsJsonObject().get("response").getAsString();
        double score = JsonParser.parseString(generated).getAsJsonObject().get("score").getAsDouble();
        if (!Double.isFinite(score)) throw new IOException("Invalid grammar score");
        return Math.max(0, Math.min(1, score));
    }
}
