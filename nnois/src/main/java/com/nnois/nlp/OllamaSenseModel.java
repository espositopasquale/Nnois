package com.nnois.nlp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Multilingual context/gloss embeddings from a local Ollama server. */
public final class OllamaSenseModel implements OmwSenseModel {
    private final URI endpoint;
    private final String model;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public OllamaSenseModel(URI endpoint, String model) {
        this.endpoint = endpoint;
        this.model = model;
    }

    public static OmwSenseModel configured() {
        String model = System.getenv("NNOIS_WSD_MODEL");
        if (model == null || model.isBlank()) return null;
        String endpoint = System.getenv("NNOIS_WSD_ENDPOINT");
        return new OllamaSenseModel(URI.create(endpoint == null || endpoint.isBlank()
                ? "http://localhost:11434/api/embed" : endpoint), model);
    }

    @Override
    public double[] score(String context, List<String> definitions) throws Exception {
        List<String> inputs = new ArrayList<>();
        inputs.add(context);
        inputs.addAll(definitions);
        String body = new Gson().toJson(Map.of("model", model, "input", inputs, "truncate", false));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Embedding server returned HTTP " + response.statusCode());
        JsonArray vectors = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("embeddings");
        if (vectors == null || vectors.size() != inputs.size()) throw new IOException("Invalid embedding count");
        double[] scores = new double[definitions.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = cosine(vectors.get(0).getAsJsonArray(), vectors.get(i + 1).getAsJsonArray());
        }
        return scores;
    }

    private static double cosine(JsonArray a, JsonArray b) throws IOException {
        if (a.size() == 0 || a.size() != b.size()) throw new IOException("Invalid embedding dimensions");
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i).getAsDouble(), y = b.get(i).getAsDouble();
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        double score = dot / Math.sqrt(normA * normB);
        if (!Double.isFinite(score)) throw new IOException("Invalid embedding values");
        return score;
    }
}
