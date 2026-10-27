package com.nnois.disambiguator;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.nnois.nlp.wsd.GradedWsdModel;
import com.nnois.nlp.wsd.OccurrenceContext;
import com.nnois.nlp.wsd.WsdSelection;

import edu.mit.jwi.Dictionary;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public final class JwiGradedWsdDisambiguator implements AutoCloseable {

    private final IDictionary dictionary;
    private final boolean ownsDictionary;
    private final WordnetStemmer stemmer;
    private final WsdSelection selection = WsdSelection.configured();
    private GradedWsdModel model;

    public JwiGradedWsdDisambiguator(String dictionaryPath) throws Exception {
        var url = getClass().getClassLoader().getResource("dict");
        dictionary = new Dictionary(url == null ? Path.of(dictionaryPath).toUri().toURL() : url);
        if (!dictionary.open()) {
            throw new java.io.IOException("Cannot open WordNet: " + dictionaryPath);
        }
        ownsDictionary = true;
        stemmer = new WordnetStemmer(dictionary);
    }

    public JwiGradedWsdDisambiguator(IDictionary dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary);
        ownsDictionary = false;
        stemmer = new WordnetStemmer(dictionary);
    }

    public ISynset disambiguate(String word, POS pos, OccurrenceContext context) throws Exception {
        if (word == null || word.isBlank()) {
            return null;
        }
        Objects.requireNonNull(pos, "WordNet POS is required");
        Objects.requireNonNull(context, "Original context and target occurrence are required");
        String normalized = word.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        var forms = new java.util.TreeSet<>(stemmer.findStems(normalized, pos));
        forms.add(normalized);
        var candidates = new LinkedHashMap<String, ISynset>();
        for (String form : forms) {
            var index = dictionary.getIndexWord(form, pos);
            if (index == null) {
                continue;
            }
            for (var id : index.getWordIDs()) {
                var synset = dictionary.getWord(id).getSynset();
                candidates.putIfAbsent(synset.getID().toString(), synset);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        var senses = candidates.values().stream().sorted(java.util.Comparator.comparing(s -> s.getID().toString())).toList();
        if (model == null) {
            model = new GradedWsdModel(GradedWsdModel.configuredDirectory());
        }
        double[] scores = model.score(context, senses.stream().map(ISynset::getGloss).toList());
        int selected = selection.select(scores);
        return selected < 0 ? null : senses.get(selected);
    }

    public ISynset disambiguate(String word, POS pos, List<String> tokens) throws Exception {
        return disambiguate(word, pos, OccurrenceContext.uniqueTarget(String.join(" ", tokens), word));
    }

    @Override
    public void close() throws Exception {
        try {
            if (model != null) {
                model.close();
        
            }} finally {
            if (ownsDictionary && dictionary.isOpen()) {
                dictionary.close();
        
            }}
    }
}
