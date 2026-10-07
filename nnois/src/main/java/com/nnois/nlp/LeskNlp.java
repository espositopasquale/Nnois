package com.nnois.nlp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import edu.mit.jwi.item.POS;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;

/** Model-based English content-word analysis for WordNet contexts and glosses. */
public final class LeskNlp {
    private final TokenizerME tokenizer;
    private final POSTaggerME tagger;
    private final LemmatizerME lemmatizer;

    public record Term(String lemma, POS pos) { }

    public LeskNlp() throws IOException {
        this("opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin",
                "opennlp-en-ud-ewt-pos-1.3-2.5.4.bin",
                "opennlp-en-ud-ewt-lemmas-1.3-2.5.4.bin");
    }

    /** Resource names can point to replacement models on the classpath. */
    public LeskNlp(String tokenizerResource, String posResource, String lemmaResource) throws IOException {
        try (InputStream tokens = open(tokenizerResource);
                InputStream pos = open(posResource);
                InputStream lemmas = open(lemmaResource)) {
            tokenizer = new TokenizerME(new TokenizerModel(tokens));
            tagger = new POSTaggerME(new POSModel(pos));
            lemmatizer = new LemmatizerME(new LemmatizerModel(lemmas));
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream stream = LeskNlp.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Missing Lesk NLP model on classpath: " + resource);
        }
        return stream;
    }

    public List<Term> analyze(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] tokens = tokenizer.tokenize(text);
        if (tokens.length == 0) {
            return List.of();
        }
        String[] tags = tagger.tag(tokens);
        String[] lemmas = lemmatizer.lemmatize(tokens, tags);
        List<Term> result = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            POS pos = PosMapper.mapToWordNetPOS(tags[i]);
            if (pos == null || tokens[i].codePoints().noneMatch(Character::isLetter)) {
                continue;
            }
            String lemma = lemmas[i];
            if (lemma == null || lemma.isBlank() || "O".equals(lemma) || "_".equals(lemma)) {
                lemma = tokens[i];
            }
            result.add(new Term(lemma.toLowerCase(Locale.ROOT), pos));
        }
        return result;
    }
}
