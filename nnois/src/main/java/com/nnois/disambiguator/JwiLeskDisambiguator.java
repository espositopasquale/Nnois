package com.nnois.disambiguator;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
            "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
            "below", "between", "both", "but", "by", "can't", "cannot", "could", "did",
            "do", "does", "doing", "down", "during", "each", "few", "for", "from", "further",
            "had", "has", "have", "having", "he", "her", "here", "him", "himself", "his",
            "how", "i", "if", "in", "into", "is", "it", "its", "itself", "more", "most",
            "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other", "ought",
            "our", "ours", "out", "over", "own", "same", "she", "should", "so", "some",
            "such", "than", "that", "the", "their", "theirs", "them", "themselves", "then",
            "there", "these", "they", "this", "those", "through", "to", "too", "under",
            "until", "up", "very", "was", "we", "were", "what", "when", "where", "which",
            "while", "who", "whom", "why", "with", "would", "you", "your", "yours"));

    public JwiLeskDisambiguator(String wordnetDictPath) throws IOException {
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

        String cleaned = rawWord.toLowerCase().trim().replace(" ", "_");
        if (cleaned.isEmpty()) {
            return null;
        }

        if (pos != null) {
            List<String> stems = stemmer.findStems(cleaned, pos);
            return stems.isEmpty() ? cleaned : stems.get(0);
        }

        List<POS> posOrder = Arrays.asList(POS.NOUN, POS.VERB, POS.ADJECTIVE, POS.ADVERB);
        String bestStem = null;

        for (POS fallbackPos : posOrder) {
            List<String> stems = stemmer.findStems(cleaned, fallbackPos);
            if (!stems.isEmpty()) {
                String candidate = stems.get(0);
                if (bestStem == null || candidate.length() < bestStem.length()
                        || (candidate.length() == bestStem.length() && candidate.compareTo(bestStem) < 0)) {
                    bestStem = candidate;
                }
            }
        }

        return bestStem != null ? bestStem : cleaned;
    }

    private Map<String, Integer> buildContextFrequencies(List<String> sentenceTokens, String targetLemma) {
        Map<String, Integer> contextFreq = new HashMap<>();
        if (sentenceTokens == null) {
            return contextFreq;
        }

        for (String token : sentenceTokens) {
            String lemma = getLemma(token, null);
            if (!isUsableToken(lemma) || lemma.equals(targetLemma)) {
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

        for (String token : synset.getGloss().toLowerCase().split("\\W+")) {
            String lemma = getLemma(token, null);
            if (isUsableToken(lemma)) {
                terms.add(lemma);
            }
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

        for (String raw : text.toLowerCase().split("\\W+")) {
            String lemma = getLemma(raw, null);
            if (!isUsableToken(lemma)) {
                continue;
            }

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

    private boolean isUsableToken(String token) {
        if (token == null) {
            return false;
        }

        return token.length() > 2
                && !STOP_WORDS.contains(token)
                && token.chars().anyMatch(Character::isLetter);
    }

    @Override
    public void close() {
        if (dict != null && dict.isOpen()) {
            dict.close();
        }
    }
}
