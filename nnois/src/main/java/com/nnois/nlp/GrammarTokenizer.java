package com.nnois.nlp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Byte-level BPE tokenizer (GPT-2 family) read from a Hugging Face tokenizer.json. */
final class GrammarTokenizer {

    private static final Pattern PRE_TOKEN = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final char[] BYTE_TO_CHAR = byteAlphabet();

    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Map<String, Integer> mergeRanks = new HashMap<>();
    private final Map<String, List<Integer>> cache = new HashMap<>();

    GrammarTokenizer(Path tokenizerJson) throws IOException {
        JsonObject root;
        try (var input = Files.newBufferedReader(tokenizerJson, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(input).getAsJsonObject();
        }
        JsonObject model = root.getAsJsonObject("model");
        if (model == null || !"BPE".equals(model.get("type").getAsString())) {
            throw new IOException("Expected a byte-level BPE tokenizer.json");
        }
        model.getAsJsonObject("vocab").entrySet().forEach(e -> vocabulary.put(e.getKey(), e.getValue().getAsInt()));
        int rank = 0;
        for (var merge : model.getAsJsonArray("merges")) {
            String key = merge.isJsonArray()
                    ? merge.getAsJsonArray().get(0).getAsString() + " " + merge.getAsJsonArray().get(1).getAsString()
                    : merge.getAsString();
            mergeRanks.put(key, rank++);
        }
        if (root.has("added_tokens")) {
            for (var token : root.getAsJsonArray("added_tokens")) {
                vocabulary.put(token.getAsJsonObject().get("content").getAsString(),
                        token.getAsJsonObject().get("id").getAsInt());
            }
        }
    }

    Integer tokenId(String token) {
        return vocabulary.get(token);
    }

    List<Integer> encode(String text) {
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = PRE_TOKEN.matcher(text);
        while (matcher.find()) {
            ids.addAll(cache.computeIfAbsent(matcher.group(), this::bpe));
        }
        return ids;
    }

    private List<Integer> bpe(String word) {
        List<String> symbols = new ArrayList<>();
        for (byte value : word.getBytes(StandardCharsets.UTF_8)) {
            symbols.add(String.valueOf(BYTE_TO_CHAR[value & 0xff]));
        }
        while (symbols.size() > 1) {
            int best = -1, bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < symbols.size() - 1; i++) {
                int rank = mergeRanks.getOrDefault(symbols.get(i) + " " + symbols.get(i + 1), Integer.MAX_VALUE);
                if (rank < bestRank) {
                    best = i;
                    bestRank = rank;
                }
            }
            if (best < 0) break;
            String left = symbols.get(best), right = symbols.get(best + 1);
            List<String> merged = new ArrayList<>(symbols.size());
            for (int i = 0; i < symbols.size(); i++) {
                if (i + 1 < symbols.size() && symbols.get(i).equals(left) && symbols.get(i + 1).equals(right)) {
                    merged.add(left + right);
                    i++;
                } else {
                    merged.add(symbols.get(i));
                }
            }
            symbols = merged;
        }
        List<Integer> ids = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            Integer id = vocabulary.get(symbol);
            if (id == null) throw new IllegalArgumentException("Token missing from vocabulary: " + symbol);
            ids.add(id);
        }
        return ids;
    }

    private static char[] byteAlphabet() {
        char[] result = new char[256];
        int extra = 256;
        for (int value = 0; value < 256; value++) {
            result[value] = value >= 33 && value <= 126 || value >= 161 && value <= 172 || value >= 174
                    ? (char) value : (char) extra++;
        }
        return result;
    }
}
