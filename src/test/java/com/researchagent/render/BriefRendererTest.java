package com.researchagent.render;

import com.researchagent.model.Brief;
import com.researchagent.model.Citation;
import com.researchagent.model.Evidence;
import com.researchagent.model.SourceStatus;
import com.researchagent.model.Stance;
import com.researchagent.model.Statement;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefRendererTest {

    private final Citation yale = new Citation("S1", "P8",
            "A recent Goldman Sachs analysis estimates AI is already reducing U.S. employment by roughly 16,000 jobs per month.", Evidence.DATA);
    private final Citation jpm = new Citation("S2", "P15",
            "“All in all, we find little association between various measures of AI intensity and job growth", Evidence.EXPERT);

    @Test
    void rendersEverySectionWithQuotesLinksAndTheMatrix() {
        String md = new BriefRenderer().render(brief(), "ollama:qwen3:14b", "2026-11-25 10:00");

        for (String heading : List.of("# Research brief: AI and jobs", "## At a glance", "## Where sources agree",
                "## Where sources disagree", "## Claims only one source makes", "## What no source addresses",
                "## Evidence matrix", "## Sources", "## Limitations and method")) {
            assertTrue(md.contains(heading), "missing " + heading);
        }
        assertTrue(md.contains("### T1 · AI is already causing net job losses"));
        assertTrue(md.contains("**Supports** (S1)"));
        assertTrue(md.contains("“All in all, we find little association"), "outer quote marks normalised");
        assertTrue(md.contains("[S2 ¶15](https://example.com/jpm#:~:text="), "citation links to the passage");
        assertTrue(md.contains("| T1 AI is already causing net job losses | + | − | ⛔ |"));
        assertTrue(md.contains("S3 excluded"), "broken source is listed under limitations");
        assertTrue(md.contains("T3 · Reskilling offsets displacement"), "unaddressed statement listed");
    }

    @Test
    void textFragmentUsesTheFirstWordsAndEncodesDashes() {
        String url = BriefRenderer.textFragmentUrl("https://example.com/a",
                "Early-career employment across the most AI-exposed occupations has declined by 16% since late 2022.");
        assertEquals("https://example.com/a#:~:text=Early%2Dcareer%20employment%20across%20the%20most%20AI%2Dexposed%20occupations%20has", url);
    }

    @Test
    void textFragmentStopsBeforeQuoteMarksAndSkipsTinyFragments() {
        String url = BriefRenderer.textFragmentUrl("https://example.com/a", "“Disturbingly,” he said “this is new”");
        assertEquals("https://example.com/a", url, "fewer than 3 safe words: plain link");
        assertFalse(BriefRenderer.textFragmentUrl("file:/tmp/x.html", "some words here").contains("#:~:text="));
    }

    private Brief brief() {
        List<Statement> statements = List.of(
                new Statement("T1", "AI is already causing net job losses", "Sets urgency"),
                new Statement("T2", "Entry-level workers are hit hardest", "Who needs help"),
                new Statement("T3", "Reskilling offsets displacement", "Whether training budgets work"));
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("S1", "+");
        cells.put("S2", "−");
        cells.put("S3", "⛔");
        return new Brief("AI and jobs", 2, 3,
                List.of(new Brief.SourceSummary("S1", "Yale | Insights", "https://example.com/yale", SourceStatus.OK, "3008 words"),
                        new Brief.SourceSummary("S2", "J.P. Morgan", "https://example.com/jpm", SourceStatus.OK, "1063 words"),
                        new Brief.SourceSummary("S3", "Blocked page", "https://example.com/b", SourceStatus.BLOCKED, "HTTP 403")),
                statements,
                List.of(new Brief.MatrixRow("T1", "AI is already causing net job losses", cells)),
                List.of(),
                List.of(new Brief.ContradictedClaim("T1", "AI is already causing net job losses", List.of(
                        new Brief.ContradictedClaim.Side(Stance.SUPPORTS, List.of("S1"), List.of(yale)),
                        new Brief.ContradictedClaim.Side(Stance.OPPOSES, List.of("S2"), List.of(jpm))),
                        "S1 support vs S2 oppose; strongest evidence data")),
                List.of(new Brief.SingleSourceClaim(null, "wage premium", Stance.SUPPORTS, "S1", List.of(yale), Evidence.DATA,
                        "Fits none of the topic statements", "only S1")),
                List.of(new Brief.UnaddressedClaim("T3", "Reskilling offsets displacement", "Whether training budgets work")),
                List.of("S3 excluded (BLOCKED): HTTP 403"));
    }
}
