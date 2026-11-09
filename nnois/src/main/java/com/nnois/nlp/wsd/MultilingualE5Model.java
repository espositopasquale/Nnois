package com.nnois.nlp.wsd;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.nnois.nlp.OmwSenseModel;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Pure-JVM deterministic multilingual E5 context-to-gloss scorer. */
public final class MultilingualE5Model implements OmwSenseModel {
    private static final int MAX_LENGTH = 256;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean tokenTypeIds;

    public static Path configuredDirectory() throws IOException {
        String configured = System.getProperty("nnois.multilingual.wsd.model.dir",
                System.getenv("NNOIS_MULTILINGUAL_WSD_MODEL_DIR"));
        if (configured != null && !configured.isBlank()) return requireDirectory(Path.of(configured));
        for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path candidate = base.resolve("models/multilingual-e5-small");
            if (Files.isRegularFile(candidate.resolve("model.onnx"))) return requireDirectory(candidate);
        }
        throw new IOException("multilingual E5 missing: set NNOIS_MULTILINGUAL_WSD_MODEL_DIR");
    }

    private static Path requireDirectory(Path path) throws IOException {
        for (String file : List.of("model.onnx", "tokenizer.json", "config.json"))
            if (!Files.isRegularFile(path.resolve(file))) throw new IOException("Missing multilingual E5 asset: " + path.resolve(file));
        return path.toAbsolutePath().normalize();
    }

    public MultilingualE5Model() throws Exception {
        Path directory = configuredDirectory();
        tokenizer = HuggingFaceTokenizer.newInstance(directory.resolve("tokenizer.json"), Map.of(
                "modelMaxLength", String.valueOf(MAX_LENGTH),
                "maxLength", String.valueOf(MAX_LENGTH),
                "truncation", "true"));
        OrtSession opened;
        try (var options = new OrtSession.SessionOptions()) {
            int threads = Integer.parseInt(System.getProperty("nnois.multilingual.wsd.threads", "2"));
            if (threads < 1) throw new IllegalArgumentException("multilingual.wsd.threads must be positive");
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            opened = environment.createSession(directory.resolve("model.onnx").toString(), options);
        }
        if (!opened.getInputNames().containsAll(Set.of("input_ids", "attention_mask"))
                || !opened.getOutputNames().contains("last_hidden_state")) {
            opened.close(); tokenizer.close();
            throw new IOException("Expected E5 token inputs and last_hidden_state output");
        }
        session = opened;
        tokenTypeIds = session.getInputNames().contains("token_type_ids");
    }

    private record Tokens(long[] ids, long[] mask, long[] types) { }

    private Tokens encode(String text, int length) {
        Encoding encoding = tokenizer.encode(text);
        long[] sourceIds = encoding.getIds(), sourceTypes = encoding.getTypeIds();
        int used = Math.min(sourceIds.length, MAX_LENGTH);
        long[] ids = new long[length], mask = new long[length], types = new long[length];
        System.arraycopy(sourceIds, 0, ids, 0, used);
        if (sourceTypes != null) System.arraycopy(sourceTypes, 0, types, 0, Math.min(used, sourceTypes.length));
        Arrays.fill(mask, 0, used, 1L);
        return new Tokens(ids, mask, types);
    }

    @Override public synchronized double[] score(OccurrenceContext context, List<String> definitions) throws Exception {
        if (definitions == null || definitions.isEmpty()) return new double[0];
        String marked = context.text().substring(0, context.begin()) + "[TARGET] " + context.surface()
                + " [/TARGET]" + context.text().substring(context.end());
        List<String> texts = new ArrayList<>();
        texts.add("query: Determine the meaning of the target word '" + context.surface() + "' in this context: " + marked);
        definitions.forEach(definition -> texts.add("passage: The target word '" + context.surface()
                + "' has this meaning: " + definition));
        int length = texts.stream().map(tokenizer::encode).mapToInt(e -> Math.min(e.getIds().length, MAX_LENGTH)).max().orElse(1);
        long[][] ids = new long[texts.size()][length], masks = new long[texts.size()][length], types = new long[texts.size()][length];
        for (int i = 0; i < texts.size(); i++) {
            Tokens encoded = encode(texts.get(i), length);
            ids[i] = encoded.ids(); masks[i] = encoded.mask(); types[i] = encoded.types();
        }
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, ids));
            inputs.put("attention_mask", OnnxTensor.createTensor(environment, masks));
            if (tokenTypeIds) inputs.put("token_type_ids", OnnxTensor.createTensor(environment, types));
            try (var output = session.run(inputs, Set.of("last_hidden_state"))) {
                Object raw = output.get("last_hidden_state").orElseThrow().getValue();
                if (!(raw instanceof float[][][] hidden) || hidden.length != texts.size())
                    throw new IOException("Invalid multilingual E5 hidden state");
                double[][] vectors = new double[hidden.length][];
                for (int row = 0; row < hidden.length; row++) vectors[row] = meanPool(hidden[row], masks[row]);
                double[] scores = new double[definitions.size()];
                for (int i = 0; i < scores.length; i++) scores[i] = dot(vectors[0], vectors[i + 1]);
                return scores;
            }
        } finally {
            for (OnnxTensor tensor : inputs.values()) tensor.close();
        }
    }

    private static double[] meanPool(float[][] hidden, long[] mask) throws IOException {
        if (hidden.length != mask.length || hidden.length == 0) throw new IOException("Invalid E5 sequence dimensions");
        double[] vector = new double[hidden[0].length];
        int count = 0;
        for (int token = 0; token < hidden.length; token++) if (mask[token] != 0) {
            if (hidden[token].length != vector.length) throw new IOException("Invalid E5 embedding dimensions");
            for (int i = 0; i < vector.length; i++) vector[i] += hidden[token][i];
            count++;
        }
        double norm = 0;
        for (int i = 0; i < vector.length; i++) { vector[i] /= Math.max(1, count); norm += vector[i] * vector[i]; }
        norm = Math.sqrt(norm);
        if (!Double.isFinite(norm) || norm == 0) throw new IOException("Invalid E5 embedding norm");
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private static double dot(double[] first, double[] second) {
        double value = 0;
        for (int i = 0; i < first.length; i++) value += first[i] * second[i];
        return value;
    }

    @Override public void close() throws Exception {
        try { session.close(); } finally { tokenizer.close(); }
    }
}
