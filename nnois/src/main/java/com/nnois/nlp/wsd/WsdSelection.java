package com.nnois.nlp.wsd;

public record WsdSelection(double threshold, double margin) {

    public WsdSelection {
        if (!Double.isFinite(threshold) || !Double.isFinite(margin) || margin < 0) {
            throw new IllegalArgumentException("WSD threshold must be finite and margin nonnegative");
        }
    }

    public static WsdSelection configured() {
        return new WsdSelection(Double.parseDouble(System.getProperty("nnois.wsd.threshold", "2.5")),
                Double.parseDouble(System.getProperty("nnois.wsd.margin", "0.25")));
    }

    public int select(double[] scores) {
        int best = -1;
        double runnerUp = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < scores.length; i++) {
            if (!Double.isFinite(scores[i])) {
                throw new IllegalArgumentException("Invalid WSD applicability score");
            }
            if (best == -1 || scores[i] > scores[best]) {
                if (best != -1) {
                    runnerUp = Math.max(runnerUp, scores[best]);
                }
                best = i;
            } else {
                runnerUp = Math.max(runnerUp, scores[i]);
            }
        }
        return best == -1 || scores[best] < threshold || scores[best] <= runnerUp
                || scores[best] - runnerUp < margin ? -1 : best;
    }

    public static void requireEnglish(String language) {
        if (!"eng".equals(com.nnois.nlp.LanguageIdentification.normalizeCode(language))) {
            throw new IllegalArgumentException("graded-wsd supports English context only. Select English for contextual "
                    + "analysis; multilingual OMW sense lookup and translations remain available.");
        }
    }
}
