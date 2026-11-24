package com.itajay.superassistant.tool;

import com.itajay.superassistant.tool.PdfIdentity.TitleEvidence;

/**
 * How a PDF relates to the paper being asked for.
 *
 * <p>The costs are lopsided, and the rules follow them. Wrongly deciding SAME hands over
 * another paper under this paper's name, and nothing notices until close reading. Wrongly
 * deciding anything else costs one more download. So SAME needs positive evidence, and "the
 * file happens to have the right name" is not evidence.</p>
 *
 * <p>Requests come from a model, so their identifiers can be wrong too. The title is what the
 * paper was selected on; an identifier that leads to an unrelated title is not believed.</p>
 */
enum PaperMatch {

    /** An identifier agrees, and the title does not object. */
    SAME_ID,
    /** Exactly this title, and no identifier that outweighs it. */
    SAME_TITLE,
    /** Evidence of another paper, or only a similar title. */
    DIFFERENT,
    /** Nothing the two sides know can be compared. */
    UNKNOWN;

    boolean isSame() {
        return this == SAME_ID || this == SAME_TITLE;
    }

    /**
     * Judges a file against the paper being asked for.
     *
     * @param wanted   the paper being asked for
     * @param recorded what this content was fetched by, when this application fetched it
     * @param printed  what the file's first page says
     * @param baseName the file's name without extension
     */
    static PaperMatch of(PaperIdentity wanted, PaperIdentity recorded, PdfIdentity printed, String baseName) {
        if (wanted.isEmpty()) {
            return UNKNOWN;
        }
        // The stamp arXiv prints on the page outranks whatever id a request once claimed.
        String arxiv = printed.arxivId() != null ? printed.arxivId() : recorded.arxivId();
        boolean comparable = wanted.arxivId() != null && arxiv != null;
        boolean disagree = comparable && !wanted.arxivId().equals(arxiv);

        String key = wanted.titleKey();
        // Naming a file by the paper's identifier or title is a deliberate statement — how a
        // human settles what the page cannot — so only the page's own arXiv stamp overrules it.
        if (!disagree && (wanted.isNamedBy(baseName) || (!key.isEmpty() && key.equals(TitleKey.of(baseName))))) {
            return SAME_ID;
        }
        boolean agree = !disagree && (comparable
                || (wanted.doi() != null && (wanted.doi().equals(recorded.doi()) || wanted.doi().equals(printed.doi())))
                || (wanted.url() != null && wanted.url().equals(recorded.url())));

        TitleEvidence title = !key.isEmpty() && key.equals(recorded.titleKey())
                ? TitleEvidence.EXACT : printed.evidenceFor(wanted.title());
        return switch (title) {
            case EXACT -> {
                boolean otherDoi = wanted.doi() != null && (recorded.doi() != null || printed.doi() != null);
                // Two works can share a short title; one long title under two identifiers is
                // one work in two versions, or a mistyped identifier.
                boolean outweighed = (disagree || otherDoi)
                        && TitleKey.weight(key) < PaperIdentity.UNAMBIGUOUS_TITLE_WEIGHT;
                yield agree ? SAME_ID : outweighed ? DIFFERENT : SAME_TITLE;
            }
            // Reworded, e.g. a preprint retitled for publication: only with an identifier.
            case PARTIAL -> agree ? SAME_ID : DIFFERENT;
            case CONTRADICTS -> DIFFERENT;
            // Differing DOIs alone are no contradiction: a preprint and its published version
            // carry one each.
            case NONE -> agree ? SAME_ID : disagree ? DIFFERENT : UNKNOWN;
        };
    }

    /**
     * Whether a file that has the requested <em>name</em> may be used for the request.
     *
     * <p>UNKNOWN is accepted only where the name is all there is to go on: the caller gave
     * nothing but a name, or the file says nothing about itself and was not fetched by this
     * application — a scan a human put there and named.</p>
     */
    boolean acceptsName(PaperIdentity wanted, PaperIdentity recorded, PdfIdentity printed) {
        return switch (this) {
            case SAME_ID, SAME_TITLE -> true;
            case DIFFERENT -> false;
            case UNKNOWN -> wanted.isEmpty() || (!printed.saysAnything() && recorded.isEmpty());
        };
    }

    /**
     * Whether a freshly downloaded PDF is visibly not the paper that was asked for.
     *
     * <p>Looser than {@link #of}: the source already vouches for the file, so here the page
     * has to object. A shortened or reworded title is let through.</p>
     */
    static boolean contradicts(PaperIdentity wanted, PdfIdentity printed) {
        return switch (printed.evidenceFor(wanted.title())) {
            case EXACT -> false;
            case CONTRADICTS -> true;
            case PARTIAL, NONE -> wanted.arxivId() != null && printed.arxivId() != null
                    && !wanted.arxivId().equals(printed.arxivId());
        };
    }
}
