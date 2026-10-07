package com.nnois.nlp;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;

public class NativeLanguageNLP {

    public static class TaggedToken {

        public final String token;
        public final String posTag;

        public TaggedToken(String token, String posTag) {
            this.token = token;
            this.posTag = posTag;
        }
    }

    private TokenizerME tokenizer;
    private POSTaggerME posTagger;
    private DictionaryLemmatizer lemmatizer;
    private LemmatizerME statisticalLemmatizer;

    public NativeLanguageNLP(String tokenizerModelPath, String posModelPath, String lemmatizerDictPath) throws Exception {
        try (InputStream tokenIn = NlpModelResources.open(tokenizerModelPath, "tokens"); InputStream posIn = NlpModelResources.open(posModelPath, "pos"); InputStream lemmaIn = getClass().getClassLoader().getResourceAsStream(lemmatizerDictPath)) {

            if (tokenIn != null) {
                this.tokenizer = new TokenizerME(new TokenizerModel(tokenIn));
            }
            if (posIn != null) {
                this.posTagger = new POSTaggerME(new POSModel(posIn));
            }
            if (lemmaIn != null) {
                this.lemmatizer = new DictionaryLemmatizer(lemmaIn);
            }
        }
        if (lemmatizer == null) {
            try (InputStream in = NlpModelResources.open(lemmatizerDictPath, "lemmas")) {
                if (in != null) statisticalLemmatizer = new LemmatizerME(new LemmatizerModel(in));
            }
        }
    }

    public List<String> tokenize(String sentence) {
        if (tokenizer != null) {
            return Arrays.asList(tokenizer.tokenize(sentence));
        }
        return Arrays.asList(sentence.trim().split("\\s+"));
    }

    public List<String> lemmatizeTokens(List<String> tokens) {
        if (posTagger != null && (lemmatizer != null || statisticalLemmatizer != null)) {
            String[] tokenArray = tokens.toArray(String[]::new);
            String[] tags = posTagger.tag(tokenArray);
            String[] lemmas = lemmatizer != null ? lemmatizer.lemmatize(tokenArray, tags)
                    : statisticalLemmatizer.lemmatize(tokenArray, tags);

            List<String> result = new ArrayList<>();
            for (int i = 0; i < lemmas.length; i++) {

                result.add("O".equals(lemmas[i]) ? tokenArray[i].toLowerCase() : lemmas[i].toLowerCase());
            }
            return result;
        }
        return tokens;
    }

    public List<TaggedToken> tagTokens(List<String> tokens) {
        List<TaggedToken> tagged = new ArrayList<>();
        if (tokens == null || tokens.isEmpty()) {
            return tagged;
        }

        String[] tokenArray = tokens.toArray(String[]::new);
        if (posTagger == null) {
            for (String token : tokenArray) {
                tagged.add(new TaggedToken(token, ""));
            }
            return tagged;
        }

        String[] tags = posTagger.tag(tokenArray);
        for (int i = 0; i < tokenArray.length; i++) {
            tagged.add(new TaggedToken(tokenArray[i], tags[i]));
        }

        return tagged;
    }

    public String inferPreferredOmwPos(String sentence, String targetWord) {
        if (sentence == null || sentence.trim().isEmpty() || targetWord == null || targetWord.trim().isEmpty()) {
            return null;
        }

        List<String> tokens = tokenize(sentence);
        if (tokens.isEmpty()) {
            return null;
        }

        List<TaggedToken> tagged = tagTokens(tokens);
        String normalizedTarget = targetWord.trim().toLowerCase();

        for (TaggedToken taggedToken : tagged) {
            if (taggedToken.token != null && taggedToken.token.toLowerCase().equals(normalizedTarget)) {
                return mapToOmwPos(taggedToken.posTag);
            }
        }

        return null;
    }

    private String mapToOmwPos(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }

        String upper = tag.toUpperCase();
        if (upper.startsWith("NN") || upper.equals("NOUN") || upper.equals("PROPN")) {
            return "n";
        }
        if (upper.startsWith("VB") || upper.equals("VERB")) {
            return "v";
        }
        if (upper.startsWith("JJ") || upper.equals("ADJ")) {
            return "a";
        }
        if (upper.startsWith("RB") || upper.equals("ADV")) {
            return "r";
        }

        return null;
    }
}
