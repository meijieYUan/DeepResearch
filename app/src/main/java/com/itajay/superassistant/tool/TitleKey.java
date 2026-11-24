package com.itajay.superassistant.tool;

import java.text.Normalizer;
import java.util.Locale;

/**
 * A comparison key for paper titles: Unicode-normalised, case-folded, with everything
 * except letters and digits removed.
 *
 * <p>Equality of keys, not similarity. Two spellings of the same title differ in spacing,
 * punctuation, line breaks, hyphenation and ligatures far more often than in wording, and
 * all of those vanish here. A fuzzy match would instead quietly accept a <em>different</em>
 * paper with a similar title — which surfaces only at close reading, when it is expensive.</p>
 *
 * <p>Letters are kept by Unicode category, not by an ASCII class, so Chinese titles keep
 * their characters instead of normalising to an empty key.</p>
 */
final class TitleKey {

    private TitleKey() {
    }

    /** The key for plain text: a model-supplied title, or text extracted from a PDF. */
    static String of(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** How much a key says, counted in Latin letters: one Han character is worth about two. */
    static int weight(String key) {
        return key.length() + (int) key.codePoints()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
    }

    /**
     * The key for a title that may carry markup, as Crossref titles do
     * ({@code <i>…</i>}, {@code <scp>…</scp>}, {@code &amp;}). Stripped before keying so the
     * tag names do not become part of the title.
     */
    static String ofMarkup(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String text = title.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return of(text);
    }
}
