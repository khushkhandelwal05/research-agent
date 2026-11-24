package com.researchagent.extract;

import com.researchagent.extract.CitationVerifier.Outcome;
import com.researchagent.model.Paragraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cases taken from real model output on the J.P. Morgan, Gloat and Yale pages. */
class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier();
    private final List<Paragraph> paragraphs = List.of(
            new Paragraph("P14", "On the other hand, this trend isn’t as apparent. “The most likely explanation for our "
                    + "non-result across non-tech industries is that AI is simply too recent to have materially changed "
                    + "business practices,” Feroli said."),
            new Paragraph("P15", "“All in all, we find little association between various measures of AI intensity and job "
                    + "growth outside of selected tech industries, and conclude that so far AI has not been a major driver "
                    + "of the composition of employment gains, except perhaps in tech,” Feroli added."),
            new Paragraph("P11", "McKinsey's latest analysis frames impact in terms of \"technical automation potential\" "
                    + "rather than jobs lost."));

    @Test
    void exactQuoteWithCurlyQuotesAndLabelIsVerified() {
        assertEquals(Outcome.VERIFIED, verifier.check(
                "[P15] All in all, we find little association between various measures of AI intensity", "P15", paragraphs).outcome());
    }

    @Test
    void quoteCutShortWithTrailingEllipsisIsVerified() {
        assertEquals(Outcome.VERIFIED, verifier.check(
                "All in all, we find little association between various measures of AI intensity and job growth "
                        + "outside of selected tech industries...", "P15", paragraphs).outcome());
    }

    @Test
    void quoteWithMiddleEllipsisIsVerifiedWhenEachPieceIsReal() {
        assertEquals(Outcome.VERIFIED, verifier.check(
                "we find little association between various measures… so far AI has not been a major driver", "P15", paragraphs).outcome());
    }

    @Test
    void singleVersusDoubleQuoteMarksDoNotMatter() {
        assertEquals(Outcome.VERIFIED, verifier.check(
                "frames impact in terms of 'technical automation potential' rather than jobs lost.", "P11", paragraphs).outcome());
    }

    @Test
    void realQuoteUnderTheWrongParagraphIsRelocated() {
        var check = verifier.check("AI is simply too recent to have materially changed business practices", "P15", paragraphs);
        assertEquals(Outcome.RELOCATED, check.outcome());
        assertEquals("P14", check.paragraphId());
    }

    @Test
    void reworded_orInventedQuotesAreRejected() {
        assertEquals(Outcome.NOT_FOUND, verifier.check(
                "Overall, we find little association between various measures of AI intensity", "P15", paragraphs).outcome());
        assertEquals(Outcome.NOT_FOUND, verifier.check(
                "AI is creating unprecedented demand for new positions in data science", "P15", paragraphs).outcome());
    }

    @Test
    void piecesMustAppearInOrder() {
        assertEquals(Outcome.NOT_FOUND, verifier.check(
                "so far AI has not been a major driver ... we find little association between", "P15", paragraphs).outcome());
    }

    @Test
    void tinyFragmentsAroundAnEllipsisDoNotCount() {
        assertEquals(Outcome.TOO_SHORT, verifier.check(
                "AI ... is simply too recent to have materially changed", "P14", paragraphs).outcome());
    }
}
