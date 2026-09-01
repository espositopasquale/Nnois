package com.nnois.nlp;

import edu.mit.jwi.item.POS;

public class PosMapper {

    public static POS mapToWordNetPOS(String tag) {
        if (tag == null) {
            return null;
        }

        tag = tag.toUpperCase();

        if (tag.startsWith("NN") || tag.equals("NOUN") || tag.equals("PROPN")) {
            return POS.NOUN;
        } else if (tag.startsWith("VB") || tag.equals("VERB")) {
            return POS.VERB;
        } else if (tag.startsWith("JJ") || tag.equals("ADJ")) {
            return POS.ADJECTIVE;
        } else if (tag.startsWith("RB") || tag.equals("ADV")) {
            return POS.ADVERB;
        }

        return null;
    }
}
