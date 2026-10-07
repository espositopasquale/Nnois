package com.nnois.nlp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Built-in function words; optional classpath lists extend these defaults. */
public final class Stopwords {
    private Stopwords() { }
    private static final Map<String, String> WORDS = Map.of(
        "en", "a an the and or but if then than of to in on at by for from with without as is am are was were be been being have has had do does did not no this that these those it its i me my we us our you your he him his she her they them their who which what when where why how all any some each both also",
        "it", "il lo la i gli le un uno una e o ma se di del dello della dei degli delle a al allo alla ai agli alle da dal dallo dalla dai dagli dalle in nel nello nella nei negli nelle con su sul sullo sulla sui sugli sulle per tra fra è sono era essere che chi cui non si mi ti ci vi io tu lui lei noi voi loro questo questa questi queste quello quella quelli quelle mio tuo suo nostro vostro",
        "fr", "le la les un une des de du au aux à et ou mais si dans sur pour par avec sans en est sont être ce cette ces qui que quoi ne pas je tu il elle nous vous ils elles mon ton son leur ses se on",
        "de", "der die das ein eine einer eines den dem des und oder aber wenn von zu in an auf für mit ohne aus ist sind war waren sein nicht ich du er sie es wir ihr ihnen mein dein sein unser dieser diese dieses",
        "es", "el la los las un una unos unas de del al a y o pero si en por para con sin es son ser no que quien se yo tú él ella nosotros vosotros ellos ellas mi tu su sus este esta estos estas",
        "pt", "o a os as um uma uns umas de do da dos das ao aos e ou mas se em no na nos nas por para com sem é são ser não que quem eu tu ele ela nós vós eles elas meu sua seu seus suas este esta estes estas",
        "nl", "de het een en of maar als van te in op aan voor met zonder uit is zijn was waren niet ik je jij hij zij ze wij we jullie hun dit dat deze die mijn jouw ons onze");

    public static Set<String> forLanguage(String code) {
        String iso2 = switch (code.toLowerCase(Locale.ROOT)) {
            case "eng" -> "en"; case "ita" -> "it"; case "fra" -> "fr";
            case "deu" -> "de"; case "spa" -> "es"; case "por" -> "pt";
            case "nld" -> "nl"; default -> code.toLowerCase(Locale.ROOT);
        };
        String words = WORDS.getOrDefault(iso2, "");
        return words.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(words.split(" ")));
    }

    public static boolean isWord(String value) {
        return value != null && value.codePoints().anyMatch(Character::isLetter);
    }
}
