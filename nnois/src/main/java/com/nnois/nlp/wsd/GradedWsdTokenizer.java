package com.nnois.nlp.wsd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class GradedWsdTokenizer {

    private static final Pattern BYTE_LEVEL = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final char[] BYTE_TO_CHAR = byteAlphabet();
    private static final int MAX_LENGTH = 512;

    private record Pair(String left, String right) {

    }

    private record AddedToken(String text, int id, boolean leftStrip, boolean rightStrip) {

    }

    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Map<Pair, Integer> mergeRanks = new HashMap<>();
    private final List<AddedToken> addedTokens = new ArrayList<>();
    private final Map<String, List<Integer>> wordCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Integer>> entry) {
            return size() > 16_384;
        }
    };
    private final int beginning;
    private final int ending;
    private final int targetStart;
    private final int targetEnd;
    private final int padding;

    public GradedWsdTokenizer(Path tokenizerJson) throws IOException {
        JsonObject tokenizer;
        try (var input = java.nio.file.Files.newBufferedReader(tokenizerJson, StandardCharsets.UTF_8)) {
            tokenizer = JsonParser.parseReader(input).getAsJsonObject();
        }
        require(!tokenizer.has("normalizer") || tokenizer.get("normalizer").isJsonNull(), "A tokenizer normalizer is not supported");
        JsonObject pre = tokenizer.getAsJsonObject("pre_tokenizer");
        require("ByteLevel".equals(text(pre, "type")) && !flag(pre, "add_prefix_space", false)
                && flag(pre, "use_regex", true), "Expected RoBERTa ByteLevel pre-tokenization without add_prefix_space");
        JsonObject model = tokenizer.getAsJsonObject("model");
        require("BPE".equals(text(model, "type")) && (!model.has("dropout") || model.get("dropout").isJsonNull())
                && !flag(model, "byte_fallback", false) && !flag(model, "ignore_merges", false)
                && text(model, "continuing_subword_prefix").isEmpty() && text(model, "end_of_word_suffix").isEmpty(),
                "Expected deterministic byte-level BPE");
        model.getAsJsonObject("vocab").entrySet().forEach(entry -> vocabulary.put(entry.getKey(), entry.getValue().getAsInt()));
        require(!vocabulary.isEmpty(), "Missing BPE vocabulary");
        int rank = 0;
        for (var merge : model.getAsJsonArray("merges")) {
            String left, right;
            if (merge.isJsonArray() && merge.getAsJsonArray().size() == 2) {
                left = merge.getAsJsonArray().get(0).getAsString();
                right = merge.getAsJsonArray().get(1).getAsString();
            } else if (merge.isJsonPrimitive() && merge.getAsJsonPrimitive().isString()) {
                String[] pair = merge.getAsString().split(" ", -1);
                require(pair.length == 2, "Invalid BPE merge");
                left = pair[0];
                right = pair[1];
            } else {
                throw new IllegalArgumentException("Invalid BPE merge");
            }
            mergeRanks.put(new Pair(left, right), rank++);
        }
        for (var value : tokenizer.getAsJsonArray("added_tokens")) {
            JsonObject token = value.getAsJsonObject();
            require(!flag(token, "single_word", false), "single_word added tokens are not supported");
            String text = text(token, "content");
            int id = token.get("id").getAsInt();
            require(!text.isEmpty(), "Empty added token");
            addedTokens.add(new AddedToken(text, id, flag(token, "lstrip", false), flag(token, "rstrip", false)));
            vocabulary.put(text, id);
        }
        addedTokens.sort(Comparator.comparingInt((AddedToken t) -> t.text().length()).reversed());
        JsonObject post = tokenizer.getAsJsonObject("post_processor");
        require("RobertaProcessing".equals(text(post, "type")), "Expected RoBERTa special-token post-processing");
        beginning = required("<s>");
        ending = required("</s>");
        targetStart = required("<t>");
        targetEnd = required("</t>");
        padding = required("<pad>");
        require(post.getAsJsonArray("cls").get(1).getAsInt() == beginning
                && post.getAsJsonArray("sep").get(1).getAsInt() == ending,
                "RoBERTa special-token IDs differ from the vocabulary");
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static boolean flag(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    public long[] encode(String markedSentence, String definition) {
        Objects.requireNonNull(markedSentence, "markedSentence");
        Objects.requireNonNull(definition, "definition");
        if (definition.isBlank()) {
            throw new IllegalArgumentException("A candidate definition is required");
        }
        List<Integer> prefix = tokenize(markedSentence + " </s></s>");
        int open = prefix.indexOf(targetStart);
        int close = prefix.indexOf(targetEnd);
        require(open >= 0 && close > open + 1
                && prefix.lastIndexOf(targetStart) == open && prefix.lastIndexOf(targetEnd) == close,
                "Context must contain exactly one nonempty <t>target</t> occurrence");
        int remaining = MAX_LENGTH - 2 - prefix.size();
        require(remaining > 0,
                "WSD context exceeds 512 tokens; use a smaller window around the marked target");
        List<Integer> gloss = tokenize(" " + definition);
        int glossLength = Math.min(remaining, gloss.size());
        require(glossLength > 0, "The candidate definition has no tokens");
        long[] result = new long[prefix.size() + glossLength + 2];
        result[0] = beginning;
        int index = 1;
        for (int id : prefix) {
            result[index++] = id;
        }
        for (int i = 0; i < glossLength; i++) {
            result[index++] = gloss.get(i);
        }
        result[index] = ending;
        return result;
    }

    public int paddingId() {
        return padding;
    }

    synchronized List<Integer> tokenize(String text) {
        List<Integer> ids = new ArrayList<>();
        int position = 0;
        int plainStart = 0;
        while (position < text.length()) {
            AddedToken matched = null;
            for (AddedToken token : addedTokens) {
                if (text.startsWith(token.text(), position)) {
                    matched = token;
                    break;
                }
            }
            if (matched == null) {
                position += Character.charCount(text.codePointAt(position));
                continue;
            }
            int plainEnd = position;
            if (matched.leftStrip()) {
                while (plainEnd > plainStart && isWhitespace(text.codePointBefore(plainEnd))) {
                    plainEnd -= Character.charCount(text.codePointBefore(plainEnd));
                }
            }
            addPlain(text.substring(plainStart, plainEnd), ids);
            ids.add(matched.id());
            position += matched.text().length();
            if (matched.rightStrip()) {
                while (position < text.length() && isWhitespace(text.codePointAt(position))) {
                    position += Character.charCount(text.codePointAt(position));
                }
            }
            plainStart = position;
        }
        addPlain(text.substring(plainStart), ids);
        return ids;
    }

    private void addPlain(String text, List<Integer> ids) {
        Matcher matcher = BYTE_LEVEL.matcher(text);
        int consumed = 0;
        while (matcher.find()) {
            require(matcher.start() == consumed, "Unsupported Unicode in byte-level pre-tokenization");
            String piece = matcher.group();
            List<Integer> encoded = wordCache.get(piece);
            if (encoded == null) {
                encoded = bpe(piece);
                wordCache.put(piece, encoded);
            }
            ids.addAll(encoded);
            consumed = matcher.end();
        }
        require(consumed == text.length(), "Incomplete byte-level pre-tokenization");
    }

    private List<Integer> bpe(String word) {
        byte[] bytes = word.getBytes(StandardCharsets.UTF_8);
        List<String> symbols = new ArrayList<>(bytes.length);
        for (byte value : bytes) {
            symbols.add(String.valueOf(BYTE_TO_CHAR[value & 0xff]));
        }
        while (symbols.size() > 1) {
            Pair best = null;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < symbols.size() - 1; i++) {
                Pair pair = new Pair(symbols.get(i), symbols.get(i + 1));
                int rank = mergeRanks.getOrDefault(pair, Integer.MAX_VALUE);
                if (rank < bestRank) {
                    best = pair;
                    bestRank = rank;
                }
            }
            if (best == null) {
                break;
            }
            List<String> merged = new ArrayList<>(symbols.size());
            for (int i = 0; i < symbols.size(); i++) {
                if (i + 1 < symbols.size() && symbols.get(i).equals(best.left())
                        && symbols.get(i + 1).equals(best.right())) {
                    merged.add(best.left() + best.right());
                    i++;
                } else {
                    merged.add(symbols.get(i));
                }
            }
            symbols = merged;
        }
        List<Integer> ids = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            ids.add(required(symbol));
        }
        return List.copyOf(ids);
    }

    private int required(String token) {
        Integer id = vocabulary.get(token);
        if (id == null) {
            throw new IllegalArgumentException("Missing RoBERTa vocabulary token: " + token);
        }
        return id;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }

    private static char[] byteAlphabet() {
        char[] result = new char[256];
        int extra = 256;
        for (int value = 0; value < 256; value++) {
            if (value >= 33 && value <= 126 || value >= 161 && value <= 172 || value >= 174) {
                result[value] = (char) value;
            } else {
                result[value] = (char) extra++;
            }
        }
        return result;
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
