package com.nnois.disambiguator;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import com.nnois.nlp.LeskNlp;
import java.util.Map;
import java.util.Set;

import edu.mit.jwi.Dictionary;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IVerbFrame;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.item.Pointer;
import edu.mit.jwi.morph.IStemmer;
import edu.mit.jwi.morph.WordnetStemmer;

public class JwiLeskDisambiguator implements AutoCloseable {

    private static final double DIRECT_GLOSS_WEIGHT = 2.0;
    private static final double RELATED_GLOSS_WEIGHT = 1.0;
    private static final double EXAMPLE_WEIGHT = 1.5;

    private final IDictionary dict;
    private final IStemmer stemmer;
    private final Map<ISynsetID, Map<String, Double>> signatureCache = new HashMap<>();

    private final LeskNlp nlp;

    public JwiLeskDisambiguator(String wordnetDictPath) throws IOException {
        this(wordnetDictPath, new LeskNlp());
    }

    public JwiLeskDisambiguator(String wordnetDictPath, LeskNlp nlp) throws IOException {
        this.nlp = Objects.requireNonNull(nlp, "nlp");
        URL url = JwiLeskDisambiguator.class.getClassLoader().getResource("dict");
        if (url == null) {
            File dictDir = new File(wordnetDictPath);
            url = dictDir.toURI().toURL();
        }

        this.dict = new Dictionary(url);
        this.dict.open();

        this.stemmer = new WordnetStemmer(this.dict);
    }

    public ISynset disambiguate(String word, POS pos, List<String> sentenceTokens) {
        if (word == null || word.isBlank()) {
            return null;
        }
        if (pos == null) {
            throw new IllegalArgumentException("WordNet POS is required for the target word");
        }
        String targetLemma = getLemma(word, pos);
        IIndexWord idxWord = dict.getIndexWord(targetLemma, pos);

        if (idxWord == null || idxWord.getWordIDs().isEmpty()) {
            return null;
        }

        Map<String, Integer> contextFreq = buildContextFrequencies(sentenceTokens, targetLemma);

        ISynset bestSynset = null;
        double maxWeightedScore = -1.0;
        int bestDirectMatches = -1;
        int bestSenseNumber = Integer.MAX_VALUE;
        String bestSynsetIdKey = null;

        List<IWordID> wordIds = idxWord.getWordIDs();
        for (int i = 0; i < wordIds.size(); i++) {
            IWordID wordID = wordIds.get(i);
            IWord iWord = dict.getWord(wordID);
            ISynset synset = iWord.getSynset();

            Map<String, Double> weightedSignature = getOrBuildWeightedSignature(synset);
            Set<String> directGlossTerms = buildDirectGlossTerms(synset);

            double weightedScore = computeWeightedOverlapScore(contextFreq, weightedSignature);
            int directMatches = countDirectMatches(contextFreq, directGlossTerms);

            int senseNumber = i;
            String synsetIdKey = synset.getID().toString();

            if (weightedScore > maxWeightedScore
                    || (Double.compare(weightedScore, maxWeightedScore) == 0 && directMatches > bestDirectMatches)
                    || (Double.compare(weightedScore, maxWeightedScore) == 0
                    && directMatches == bestDirectMatches
                    && senseNumber < bestSenseNumber)
                    || (Double.compare(weightedScore, maxWeightedScore) == 0
                    && directMatches == bestDirectMatches
                    && senseNumber == bestSenseNumber
                    && (bestSynsetIdKey == null || synsetIdKey.compareTo(bestSynsetIdKey) < 0))) {
                maxWeightedScore = weightedScore;
                bestDirectMatches = directMatches;
                bestSenseNumber = senseNumber;
                bestSynsetIdKey = synsetIdKey;
                bestSynset = synset;
            }
        }

        return bestSynset;
    }

    private String getLemma(String rawWord, POS pos) {
        if (rawWord == null) {
            return null;
        }

        String cleaned = rawWord.toLowerCase(Locale.ROOT).trim().replace(" ", "_");
        if (cleaned.isEmpty()) {
            return null;
        }

        List<String> stems = stemmer.findStems(cleaned, pos);
        return stems.isEmpty() ? cleaned : stems.get(0);
    }

    private Map<String, Integer> buildContextFrequencies(List<String> sentenceTokens, String targetLemma) {
        Map<String, Integer> contextFreq = new HashMap<>();
        if (sentenceTokens == null) {
            return contextFreq;
        }

        for (LeskNlp.Term term : nlp.analyze(sentenceTokens.stream()
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")))) {
            String lemma = getLemma(term.lemma(), term.pos());
            if (lemma.equals(targetLemma)) {
                continue;
            }
            contextFreq.merge(lemma, 1, Integer::sum);
        }

        return contextFreq;
    }

    private Map<String, Double> getOrBuildWeightedSignature(ISynset synset) {
        return signatureCache.computeIfAbsent(synset.getID(), id -> buildWeightedSignature(synset));
    }

    private Map<String, Double> buildWeightedSignature(ISynset synset) {
        Map<String, Double> signature = new HashMap<>();

        addWeightedTerms(signature, synset.getGloss(), DIRECT_GLOSS_WEIGHT);
        addExamples(signature, synset, EXAMPLE_WEIGHT);

        for (ISynsetID relatedId : synset.getRelatedSynsets(Pointer.HYPERNYM)) {
            ISynset related = dict.getSynset(relatedId);
            if (related != null) {
                addWeightedTerms(signature, related.getGloss(), RELATED_GLOSS_WEIGHT);
            }
        }

        for (ISynsetID relatedId : synset.getRelatedSynsets(Pointer.HYPONYM)) {
            ISynset related = dict.getSynset(relatedId);
            if (related != null) {
                addWeightedTerms(signature, related.getGloss(), RELATED_GLOSS_WEIGHT);
            }
        }

        return signature;
    }

    private Set<String> buildDirectGlossTerms(ISynset synset) {
        Set<String> terms = new HashSet<>();
        if (synset == null) {
            return terms;
        }

        for (LeskNlp.Term term : nlp.analyze(synset.getGloss())) {
            terms.add(getLemma(term.lemma(), term.pos()));
        }

        return terms;
    }

    private void addExamples(Map<String, Double> signature, ISynset synset, double weight) {
        if (synset == null || synset.getWords() == null) {
            return;
        }

        for (IWord senseWord : synset.getWords()) {
            for (IVerbFrame frame : senseWord.getVerbFrames()) {
                if (frame != null) {
                    addWeightedTerms(signature, frame.getTemplate(), weight);
                }
            }
        }
    }

    private void addWeightedTerms(Map<String, Double> signature, String text, double weight) {
        if (text == null || text.isBlank()) {
            return;
        }

        for (LeskNlp.Term term : nlp.analyze(text)) {
            String lemma = getLemma(term.lemma(), term.pos());
            signature.merge(lemma, weight, Double::sum);
        }
    }

    private double computeWeightedOverlapScore(Map<String, Integer> contextFreq, Map<String, Double> signature) {
        double score = 0.0;
        for (Map.Entry<String, Integer> entry : contextFreq.entrySet()) {
            Double termWeight = signature.get(entry.getKey());
            if (termWeight != null) {
                score += termWeight * entry.getValue();
            }
        }
        return score;
    }

    private int countDirectMatches(Map<String, Integer> contextFreq, Set<String> directGlossTerms) {
        int matches = 0;
        for (String term : directGlossTerms) {
            if (contextFreq.containsKey(term)) {
                matches++;
            }
        }
        return matches;
    }

    @Override
    public void close() {
        if (dict != null && dict.isOpen()) {
            dict.close();
        }
    }
}
