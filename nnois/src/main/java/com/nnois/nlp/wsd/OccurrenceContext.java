package com.nnois.nlp.wsd;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

public record OccurrenceContext(String text, int begin, int end) {

    public OccurrenceContext {
        if (text == null || begin < 0 || end <= begin || end > text.length()) {
            throw new IllegalArgumentException("Invalid WSD target span");
        }
        if (text.substring(begin, end).isBlank()) {
            throw new IllegalArgumentException("Empty target text");
        }
        if (text.contains("<t>") || text.contains("</t>")) {
            throw new IllegalArgumentException("Original sentence contains reserved WSD target markers");
        }
        if (splitsSurrogate(text, begin) || splitsSurrogate(text, end)) {
            throw new IllegalArgumentException("WSD target splits a Unicode code point");
        }
    }

    public static OccurrenceContext of(String text, int begin, int end) {
        return new OccurrenceContext(text, begin, end);
    }

    public String markedText() {
        return text.substring(0, begin) + "<t>" + text.substring(begin, end) + "</t>" + text.substring(end);
    }

    public String surface() {
        return text.substring(begin, end);
    }

    public static OccurrenceContext fromDocument(String document, int begin, int end) {
        of(document, begin, end);
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        sentences.setText(document);
        int start = sentences.first();
        for (int finish = sentences.next(); finish != BreakIterator.DONE; start = finish, finish = sentences.next()) {
            if (begin >= start && end <= finish) {
                return of(document.substring(start, finish), begin - start, end - start);
            }
        }
        throw new IllegalArgumentException("The WSD target must be inside one sentence");
    }

    public static OccurrenceContext uniqueTarget(String text, String target) {
        if (text == null || target == null || target.isBlank()) {
            throw new IllegalArgumentException("Original text and a target occurrence are required for WSD");
        }
        var matcher = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}\\p{M}_])"
                + Pattern.quote(target.strip().replace('_', ' ')) + "(?![\\p{L}\\p{N}\\p{M}_])").matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("WSD target is not present in the original text: " + target);
        }
        int begin = matcher.start(), end = matcher.end();
        if (matcher.find()) {
            throw new IllegalArgumentException("WSD target occurs more than once; supply its character offsets");
        }
        return fromDocument(text, begin, end);
    }

    private static boolean splitsSurrogate(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
    }
}
