package com.nnois.nlp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SimilarityGraph(List<Node> nodes, List<Link> links, double minimumScore, boolean truncated) {

    public static final double MINIMUM_SCORE = 0;
    public static final int MAX_NODES = 120;

    public record Node(String id, String word, String language, String nominalUnit, boolean source) {}

    public record Link(String source, String target, double score) {}

    public static SimilarityGraph from(AutoTuningMultilingualPipeline.ContrastiveResult result) {
        Map<String, List<NativeMultilingualPipeline.TransparencyResult>> units = new LinkedHashMap<>();
        if (result.chosenWord != null) {
            units.put(result.chosenWord, result.transparencyResults);
        } else {
            for (var row : result.nominalLanguageTransparencies) {
                units.computeIfAbsent(row.nominalUnit, key -> new ArrayList<>()).add(new NativeMultilingualPipeline.TransparencyResult(row.language, row.lemma, row.score));
            }
        }
        return build(result.detectedLanguage, units);
    }

    public static SimilarityGraph build(String language, Map<String, List<NativeMultilingualPipeline.TransparencyResult>> units) {
        List<Node> nodes = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        boolean truncated = false;
        int index = 0;
        for (var unit : units.entrySet()) {
            if (Stopwords.forLanguage(language).contains(unit.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Map<String, NativeMultilingualPipeline.TransparencyResult> best = new LinkedHashMap<>();
            for (var row : unit.getValue()) {
                String lang = LanguageIdentification.normalizeCode(row.lang);
                if (lang.equals(LanguageIdentification.normalizeCode(language)) || !Double.isFinite(row.score) || row.score < 0 || row.score > 1) {
                    continue;

                }
                if (!best.containsKey(lang) || best.get(lang).score < row.score) {
                    best.put(lang, row);

                }
            }
            if (best.isEmpty()) {
                continue;
            }
            if (nodes.size() + 2 > MAX_NODES) {
                truncated = true;
                break;
            }
            String source = "source-" + index++;
            nodes.add(new Node(source, unit.getKey(), language, unit.getKey(), true));
            for (var row : best.entrySet()) {
                if (nodes.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                String target = source + "-" + row.getKey();
                nodes.add(new Node(target, row.getValue().lemma, row.getKey(), unit.getKey(), false));
                links.add(new Link(source, target, row.getValue().score));
            }
        }
        return new SimilarityGraph(List.copyOf(nodes), List.copyOf(links), MINIMUM_SCORE, truncated);
    }
}
