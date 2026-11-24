package com.researchagent.extract;

import com.researchagent.model.Paragraph;

import java.util.List;
import java.util.Locale;

/**
 * Checks that a quote the model returned really appears in the source, so the brief never cites
 * text that isn't there. Matching ignores case, curly vs straight quotes, dash styles and spacing.
 */
public final class CitationVerifier {

    /** Quotes shorter than this match too easily ("AI jobs") to count as evidence. */
    static final int MIN_QUOTE_WORDS = 5;

    /** Past this a "quote" is a passage, not a citation (the prompt asks for at most 40 words). */
    static final int MAX_QUOTE_WORDS = 80;

    public enum Outcome { VERIFIED, RELOCATED, TOO_SHORT, TOO_LONG, NOT_FOUND }

    /** {@code paragraphId} is where the quote was actually found (may differ from the claimed one). */
    public record Check(Outcome outcome, String paragraphId) {
        public boolean ok() {
            return outcome == Outcome.VERIFIED || outcome == Outcome.RELOCATED;
        }
    }

    /** Each piece of an elided quote ("A ... B") must be at least this long to count. */
    static final int MIN_FRAGMENT_WORDS = 3;

    /**
     * A quote may be shortened with "..." (at the start, end or middle), the usual way to quote. Each
     * piece must appear in the paragraph, in order; anything the model rewrote or invented fails.
     */
    public Check check(String quote, String claimedParagraphId, List<Paragraph> paragraphs) {
        String q = normalize(stripOuterQuotes(quote))
                .replaceAll("^(\\.{2,}\\s*)+", "")
                .replaceAll("(\\s*\\.{2,})+$", "")
                .strip();
        List<String> pieces = java.util.Arrays.stream(q.split("\\s*\\.{2,}\\s*"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        int words = pieces.stream().mapToInt(p -> p.split(" ").length).sum();
        if (words < MIN_QUOTE_WORDS
                || (pieces.size() > 1 && pieces.stream().anyMatch(p -> p.split(" ").length < MIN_FRAGMENT_WORDS))) {
            return new Check(Outcome.TOO_SHORT, claimedParagraphId);
        }
        if (words > MAX_QUOTE_WORDS) {
            return new Check(Outcome.TOO_LONG, claimedParagraphId);
        }
        for (Paragraph p : paragraphs) {
            if (p.id().equalsIgnoreCase(claimedParagraphId) && containsInOrder(normalize(p.text()), pieces)) {
                return new Check(Outcome.VERIFIED, p.id());
            }
        }
        // The model sometimes cites the wrong paragraph number for a real quote.
        for (Paragraph p : paragraphs) {
            if (containsInOrder(normalize(p.text()), pieces)) {
                return new Check(Outcome.RELOCATED, p.id());
            }
        }
        return new Check(Outcome.NOT_FOUND, claimedParagraphId);
    }

    private static boolean containsInOrder(String text, List<String> pieces) {
        int from = 0;
        for (String piece : pieces) {
            int at = text.indexOf(piece, from);
            if (at < 0) return false;
            from = at + piece.length();
        }
        return true;
    }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                // All quote marks compare equal: sources and models mix 'single', "double" and “curly”.
                .replaceAll("[‘’‛′`´“”„″\"]", "'")
                .replaceAll("[‐‑‒–—―]", "-")
                .replace("…", "...")
                .replace(' ', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String stripOuterQuotes(String s) {
        // Some models copy the "[P12]" label we put in front of each paragraph into the quote.
        String t = s.strip().replaceFirst("^\\[?P\\d+\\]\\s*", "");
        while (!t.isEmpty() && "\"'“”‘’".indexOf(t.charAt(0)) >= 0) t = t.substring(1);
        while (!t.isEmpty() && "\"'“”‘’".indexOf(t.charAt(t.length() - 1)) >= 0) t = t.substring(0, t.length() - 1);
        // A trailing period the model added (or dropped) shouldn't sink an otherwise exact quote.
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.strip();
    }
}
