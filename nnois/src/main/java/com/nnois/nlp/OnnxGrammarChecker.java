package com.nnois.nlp;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Embedded decoder-only language model (GPT-2 style, ONNX) scoring grammatical coherence as the
 * per-token probability exp(mean log P(token | previous tokens)), in (0, 1].
 *
 * Expected assets in the model directory: model.onnx (exported without past key values, e.g.
 * `optimum-cli export onnx --task text-generation`) and tokenizer.json.
 */
public final class OnnxGrammarChecker implements WordSubstitution.CoherenceChecker, AutoCloseable {

    private static final int MAX_TOKENS = 512;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final GrammarTokenizer tokenizer;
    private final long boundaryId;
    private final boolean positionIds;

    /** Returns null when no model directory is configured or found. */
    public static OnnxGrammarChecker configured() throws Exception {
        String configured = System.getProperty("nnois.grammar.model.dir", System.getenv("NNOIS_GRAMMAR_MODEL_DIR"));
        if (configured != null && !configured.isBlank()) return new OnnxGrammarChecker(Path.of(configured));
        for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path candidate = base.resolve("models").resolve("grammar");
            if (Files.isRegularFile(candidate.resolve("model.onnx"))
                    && Files.isRegularFile(candidate.resolve("tokenizer.json"))) {
                return new OnnxGrammarChecker(candidate);
            }
        }
        return null;
    }

    public OnnxGrammarChecker(Path directory) throws Exception {
        Path model = directory.resolve("model.onnx"), tokens = directory.resolve("tokenizer.json");
        if (!Files.isRegularFile(model) || !Files.isRegularFile(tokens)) {
            throw new IOException("Grammar model directory needs model.onnx and tokenizer.json: " + directory);
        }
        tokenizer = new GrammarTokenizer(tokens);
        Integer boundary = null;
        for (String name : List.of("<|endoftext|>", "</s>", "<s>")) {
            if ((boundary = tokenizer.tokenId(name)) != null) break;
        }
        if (boundary == null) throw new IOException("No beginning-of-text token found in tokenizer.json");
        boundaryId = boundary;
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Integer.parseInt(System.getProperty("nnois.grammar.threads", "2")));
            session = environment.createSession(model.toString(), options);
        }
        Set<String> inputs = session.getInputNames();
        if (!inputs.contains("input_ids") || !Set.of("input_ids", "attention_mask", "position_ids").containsAll(inputs)) {
            session.close();
            throw new IOException("Unsupported inputs " + inputs
                    + "; export a decoder without past key values (inputs: input_ids, attention_mask)");
        }
        positionIds = inputs.contains("position_ids");
    }

    @Override
    public double score(String text) throws Exception {
        List<Integer> body = tokenizer.encode(text);
        if (body.isEmpty()) return 1;
        int length = Math.min(body.size(), MAX_TOKENS - 1) + 1;
        long[] ids = new long[length];
        ids[0] = boundaryId;
        for (int i = 1; i < length; i++) ids[i] = body.get(i - 1);
        long[] mask = new long[length];
        Arrays.fill(mask, 1);
        long[] positions = new long[length];
        Arrays.setAll(positions, i -> i);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, new long[][] { ids }));
            if (session.getInputNames().contains("attention_mask")) {
                inputs.put("attention_mask", OnnxTensor.createTensor(environment, new long[][] { mask }));
            }
            if (positionIds) inputs.put("position_ids", OnnxTensor.createTensor(environment, new long[][] { positions }));
            try (var result = session.run(inputs)) {
                Object raw = result.get(0).getValue();
                if (!(raw instanceof float[][][] logits) || logits.length != 1 || logits[0].length != length) {
                    throw new IOException("Unexpected decoder output; expected float logits [1, tokens, vocab]");
                }
                double total = 0;
                for (int i = 0; i < length - 1; i++) total += logProbability(logits[0][i], (int) ids[i + 1]);
                return Math.exp(total / (length - 1));
            }
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static double logProbability(float[] logits, int token) {
        double max = Double.NEGATIVE_INFINITY;
        for (float value : logits) max = Math.max(max, value);
        double sum = 0;
        for (float value : logits) sum += Math.exp(value - max);
        return logits[token] - max - Math.log(sum);
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
