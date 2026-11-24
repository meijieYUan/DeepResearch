package com.itajay.superassistant.tool;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a PDF's first page says about which paper it is. Read by {@link PdfIdentityReader}.
 *
 * @param arxivId    from the arXiv stamp, without version, or null
 * @param doi        the one DOI printed on the page, lower-cased, or null when none or several
 * @param metaTitle  {@link TitleKey} of the document's Title property, or empty
 * @param titleLines the lines above the abstract as printed, top to bottom
 */
record PdfIdentity(String arxivId, String doi, String metaTitle, List<String> titleLines) {

    static final PdfIdentity NONE = new PdfIdentity(null, null, "", List.of());

    /** How a page's title text relates to a title someone asked for. */
    enum TitleEvidence {
        /** The page shows exactly this title. */
        EXACT,
        /** A shortened, extended or reworded form of it. */
        PARTIAL,
        /** The page shows some other title. */
        CONTRADICTS,
        /** Nothing to compare. */
        NONE
    }

    /**
     * Agreement, counted both ways, from which two titles are variants of one. Measured on 13
     * papers: sloppy forms of the right title ("ResNet: Deep Residual …", a dropped subtitle)
     * score 0.60 and up, titles of other papers in the same field 0.50 at most. Counting one
     * way only does not separate them — neighbours share up to 0.69 of their words.
     */
    private static final double SIMILAR = 0.55;

    /**
     * Share of a longer title that, found anywhere in the title area, also makes a variant:
     * some layouts set a sidebar beside the title, and its words end up in the same lines.
     */
    private static final double CONTAINED = 0.8;
    private static final int CONTAINED_MIN_WORDS = 4;

    private static final int MAX_TITLE_LINES = 4;

    PdfIdentity {
        metaTitle = metaTitle == null ? "" : metaTitle;
        titleLines = List.copyOf(titleLines);
    }

    /**
     * Compares the page with a requested title.
     *
     * <p>The Title property only breaks ties: authors reuse templates, so it can name another
     * paper, and what is printed on the page wins.</p>
     */
    TitleEvidence evidenceFor(String title) {
        String key = TitleKey.of(title);
        if (TitleKey.weight(key) < PaperIdentity.MIN_TITLE_WEIGHT) {
            return TitleEvidence.NONE;
        }
        if (printsTitle(key)) {
            return TitleEvidence.EXACT;
        }
        TitleEvidence printed = resemblance(title);
        return printed != TitleEvidence.CONTRADICTS && key.equals(metaTitle) ? TitleEvidence.EXACT : printed;
    }

    /**
     * True when the title is made of whole consecutive lines. That finds it wherever it sits —
     * below a licence notice or a conference banner, across two lines, in capitals — without
     * guessing which line is the title, and does not mistake "Improved Denoising Diffusion
     * Probabilistic Models" for the paper whose title it contains.
     */
    private boolean printsTitle(String key) {
        List<String> lines = keys();
        for (int from = 0; from < lines.size(); from++) {
            StringBuilder run = new StringBuilder();
            for (int to = from; to < lines.size() && run.length() < key.length(); to++) {
                run.append(lines.get(to));
                if (run.length() == key.length() && key.contentEquals(run)) {
                    return true;
                }
            }
        }
        return false;
    }

    private TitleEvidence resemblance(String title) {
        List<Map<String, Integer>> lines = titleLines.stream().map(PdfIdentity::words).toList();
        Map<String, Integer> page = new HashMap<>();
        lines.forEach(page::putAll);
        Map<String, Integer> asked = words(title);
        // A title in a script the page does not use is a translation; it says nothing either way.
        boolean pageHasHan = page.keySet().stream().anyMatch(PdfIdentity::isHan);
        boolean pageHasOther = page.keySet().stream().anyMatch(word -> !isHan(word));
        asked.keySet().removeIf(word -> isHan(word) ? !pageHasHan : !pageHasOther);
        if (!showsAnyTitle() || weight(asked) < PaperIdentity.MIN_TITLE_WEIGHT) {
            return TitleEvidence.NONE;
        }
        if (asked.size() >= CONTAINED_MIN_WORDS && shared(asked, page) >= CONTAINED * weight(asked)) {
            return TitleEvidence.PARTIAL;
        }
        for (int from = 0; from < lines.size(); from++) {
            Map<String, Integer> run = new HashMap<>();
            for (int to = from; to < Math.min(lines.size(), from + MAX_TITLE_LINES); to++) {
                run.putAll(lines.get(to));
                if (2.0 * shared(asked, run) >= SIMILAR * (weight(asked) + weight(run))) {
                    return TitleEvidence.PARTIAL;
                }
            }
        }
        return TitleEvidence.CONTRADICTS;
    }

    /** Words with their weights; Han text has no spaces, so it is taken in pairs of characters. */
    private static Map<String, Integer> words(String text) {
        Map<String, Integer> words = new HashMap<>();
        for (String word : Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (isHan(word)) {
                for (int i = 0; i + 2 <= word.length(); i++) {
                    words.put(word.substring(i, i + 2), 2);
                }
            } else if (word.length() >= 4) {
                // Shorter words ("for", "with" aside) say nothing about which paper this is.
                words.put(word, word.length());
            }
        }
        return words;
    }

    private static boolean isHan(String word) {
        return word.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    private static int weight(Map<String, Integer> words) {
        return words.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int shared(Map<String, Integer> asked, Map<String, Integer> found) {
        return asked.entrySet().stream().filter(e -> found.containsKey(e.getKey())).mapToInt(Map.Entry::getValue).sum();
    }

    /** The page has readable text where a title would be, so an absent title means something. */
    private boolean showsAnyTitle() {
        return keys().stream().mapToInt(TitleKey::weight).sum() >= PaperIdentity.MIN_TITLE_WEIGHT;
    }

    /** The Title property is left out on purpose: scanners fill it with a file or program name. */
    boolean saysAnything() {
        return arxivId != null || doi != null || showsAnyTitle();
    }

    /** What a reader would see first, for messages. */
    String heading() {
        String text = String.join(" / ", titleLines.subList(0, Math.min(3, titleLines.size())));
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    private List<String> keys() {
        return titleLines.stream().map(TitleKey::of).filter(key -> !key.isEmpty()).toList();
    }
}
