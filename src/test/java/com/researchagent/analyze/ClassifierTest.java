package com.researchagent.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.researchagent.model.Brief;
import com.researchagent.model.Citation;
import com.researchagent.model.Claim;
import com.researchagent.model.Evidence;
import com.researchagent.model.Source;
import com.researchagent.model.SourceStatus;
import com.researchagent.model.Stance;
import com.researchagent.model.Statement;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.researchagent.model.Evidence.ASSERTION;
import static com.researchagent.model.Evidence.DATA;
import static com.researchagent.model.Evidence.EXPERT;
import static com.researchagent.model.Stance.MIXED;
import static com.researchagent.model.Stance.OPPOSES;
import static com.researchagent.model.Stance.SUPPORTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassifierTest {

    private final Classifier classifier = new Classifier();

    // S1, S2, S4, S5 usable; S3 blocked.
    private final List<Source> sources = List.of(
            source("S1", SourceStatus.OK),
            source("S2", SourceStatus.OK),
            source("S3", SourceStatus.BLOCKED),
            source("S4", SourceStatus.OK),
            source("S5", SourceStatus.PARTIAL));

    private final List<Statement> statements = List.of(
            new Statement("T1", "AI is already causing net job losses", "Sets the urgency"),
            new Statement("T2", "Entry-level workers are hit hardest", "Shapes who needs help"),
            new Statement("T3", "Reskilling can offset displacement", "Says whether training budgets work"),
            new Statement("T4", "Effects are concentrated in tech", "Shows which sectors to watch"));

    private final List<Claim> claims = new ArrayList<>();

    @Test
    void sourcesOnTheSameSideAreConsensus_andSilenceDoesNotBlockIt() {
        claim("S1", "T2", SUPPORTS, DATA);
        claim("S2", "T2", SUPPORTS, EXPERT);
        claim("S4", "T2", SUPPORTS, ASSERTION); // S5 silent on T2: must not matter

        Brief brief = run();

        Brief.ConsensusClaim c = brief.consensusClaims().getFirst();
        assertEquals("T2", c.statementId());
        assertEquals(SUPPORTS, c.stance());
        assertEquals(List.of("S1", "S2", "S4"), c.agreeingSources());
        assertEquals(DATA, c.citations().getFirst().evidence(), "strongest evidence listed first");
        assertTrue(brief.contradictedClaims().isEmpty());
    }

    @Test
    void supportVersusOppositionIsAContradiction_theYaleVsJpMorganCase() {
        claim("S1", "T1", SUPPORTS, DATA);  // "employment among young developers fell 20%"
        claim("S4", "T1", OPPOSES, DATA);   // "little association between AI intensity and job growth"

        Brief brief = run();

        assertEquals(1, brief.contradictedClaims().size());
        var sides = brief.contradictedClaims().getFirst().sides();
        assertEquals(SUPPORTS, sides.get(0).stance());
        assertEquals(List.of("S1"), sides.get(0).sources());
        assertEquals(OPPOSES, sides.get(1).stance());
        assertEquals(List.of("S4"), sides.get(1).sources());
        assertTrue(brief.consensusClaims().isEmpty());
    }

    @Test
    void sourcesAgreeingAStatementIsFalseIsConsensusToo() {
        claim("S1", "T4", OPPOSES, DATA);
        claim("S2", "T4", OPPOSES, EXPERT);

        Brief.ConsensusClaim c = run().consensusClaims().getFirst();
        assertEquals(OPPOSES, c.stance());
        assertTrue(c.rankReason().contains("oppose it"));
    }

    @Test
    void aStatementOnlyOneSourceAddressesIsSingleSource() {
        claim("S4", "T3", SUPPORTS, EXPERT);

        Brief.SingleSourceClaim s = run().singleSourceClaims().getFirst();
        assertEquals("T3", s.statementId());
        assertEquals("S4", s.sourceId());
        assertNull(s.note());
    }

    @Test
    void aSourceArguingBothSidesIsMixed_andNeitherConfirmsNorContradicts() {
        claim("S1", "T3", SUPPORTS, DATA);
        claim("S1", "T3", OPPOSES, DATA);   // S1 is MIXED on T3
        claim("S2", "T3", SUPPORTS, EXPERT); // only S2 clearly supports

        Brief brief = run();

        assertTrue(brief.contradictedClaims().isEmpty(), "MIXED must not count as opposition");
        assertTrue(brief.consensusClaims().isEmpty(), "MIXED must not count as agreement");
        Brief.SingleSourceClaim s = brief.singleSourceClaims().getFirst();
        assertEquals("S2", s.sourceId());
        assertNotNull(s.note());
        assertTrue(s.note().contains("S1"));
    }

    @Test
    void aClearMajorityDecidesASourcesStance_andTheMinorityIsReportedNotQuotedOnTheWrongSide() {
        for (int i = 0; i < 5; i++) claim("S1", "T1", SUPPORTS, DATA); // Yale-like: 5 points for...
        claim("S1", "T1", OPPOSES, DATA);                               // ...and one caveat
        claim("S4", "T1", OPPOSES, DATA);

        Brief brief = run();

        assertEquals("+", brief.matrix().getFirst().cells().get("S1"), "5:1 is SUPPORTS, not mixed");
        Brief.ContradictedClaim c = brief.contradictedClaims().getFirst();
        assertEquals(5, c.sides().get(0).citations().size(), "the caveat is not quoted as support");
        assertEquals(1, c.sides().get(1).citations().size(), "S1's caveat is not credited to S4's side either");
        assertTrue(c.rankReason().contains("S1 also makes 1 point the other way"), c.rankReason());
    }

    @Test
    void twoToOneCountsAsAStance_butThreeToTwoIsMixed() {
        claim("S1", "T1", SUPPORTS, DATA);
        claim("S1", "T1", SUPPORTS, DATA);
        claim("S1", "T1", OPPOSES, DATA);
        claim("S2", "T1", OPPOSES, DATA);
        claim("S2", "T1", OPPOSES, DATA);
        claim("S2", "T1", OPPOSES, DATA);
        claim("S2", "T1", SUPPORTS, DATA);
        claim("S2", "T1", SUPPORTS, DATA);

        Brief.MatrixRow row = run().matrix().getFirst();
        assertEquals("+", row.cells().get("S1"));
        assertEquals("±", row.cells().get("S2"));
    }

    @Test
    void statementsNoSourceAddressesAreUnaddressed() {
        claim("S1", "T1", SUPPORTS, DATA);

        Brief brief = run();

        assertEquals(List.of("T2", "T3", "T4"),
                brief.unaddressedClaims().stream().map(Brief.UnaddressedClaim::statementId).toList());
        assertEquals("Shapes who needs help", brief.unaddressedClaims().getFirst().whyItMatters());
    }

    @Test
    void claimsFromUnusableSourcesAreIgnored() {
        claim("S1", "T1", SUPPORTS, DATA);
        claim("S3", "T1", OPPOSES, DATA); // S3 is BLOCKED: must not create a contradiction

        Brief brief = run();

        assertTrue(brief.contradictedClaims().isEmpty());
        assertEquals("S1", brief.singleSourceClaims().getFirst().sourceId());
        assertEquals("⛔", brief.matrix().getFirst().cells().get("S3"));
    }

    @Test
    void claimsThatFitNoStatementAreSingleSourceAndFlagged() {
        claims.add(new Claim("S2-C9", "S2", "AI skills command a 56% wage premium", null, "wage premium", SUPPORTS, DATA,
                new Citation("S2", "P9", "AI skills now command a 56% wage premium", DATA), "fits no statement"));

        Brief brief = run();

        Brief.SingleSourceClaim s = brief.singleSourceClaims().getFirst();
        assertNull(s.statementId());
        assertEquals("wage premium", s.statement());
        assertTrue(s.note().contains("none of the topic statements"));
        assertEquals(4, brief.matrix().size(), "other-claims stay out of the statement matrix");
    }

    @Test
    void rankingUsesBreadthForConsensusAndEvidenceForSingleSource() {
        claim("S1", "T2", SUPPORTS, ASSERTION);
        claim("S2", "T2", SUPPORTS, ASSERTION);
        claim("S4", "T2", SUPPORTS, ASSERTION); // 3 sources, weak evidence
        claim("S1", "T1", SUPPORTS, DATA);
        claim("S2", "T1", SUPPORTS, DATA);      // 2 sources, strong evidence
        claim("S4", "T3", SUPPORTS, ASSERTION);
        claim("S5", "T4", OPPOSES, DATA);

        Brief brief = run();

        assertEquals(List.of("T2", "T1"), brief.consensusClaims().stream().map(Brief.ConsensusClaim::statementId).toList(),
                "3 agreeing sources outrank 2, whatever the evidence");
        assertEquals(List.of("T4", "T3"), brief.singleSourceClaims().stream().map(Brief.SingleSourceClaim::statementId).toList(),
                "a data-backed single-source claim comes before an assertion");
    }

    @Test
    void matrixFollowsStatementOrderAndShowsEveryState() {
        claim("S1", "T1", SUPPORTS, DATA);
        claim("S2", "T1", OPPOSES, DATA);
        claim("S4", "T1", SUPPORTS, DATA);
        claim("S4", "T1", OPPOSES, DATA);

        Brief brief = run();
        Brief.MatrixRow row = brief.matrix().getFirst();

        assertEquals(List.of("T1", "T2", "T3", "T4"), brief.matrix().stream().map(Brief.MatrixRow::statementId).toList());
        assertEquals("+", row.cells().get("S1"));
        assertEquals("−", row.cells().get("S2"));
        assertEquals("⛔", row.cells().get("S3"));
        assertEquals("±", row.cells().get("S4"));
        assertEquals("·", row.cells().get("S5"));
    }

    @Test
    void withFewerThanTwoUsableSourcesTheBriefSaysSo() {
        List<Source> mostlyBroken = List.of(source("S1", SourceStatus.OK), source("S2", SourceStatus.PAYWALL));
        Brief brief = classifier.classify("x", mostlyBroken, statements, List.of());

        assertEquals(1, brief.sourcesUsable());
        assertTrue(brief.limitations().stream().anyMatch(l -> l.startsWith("S2 excluded (PAYWALL)")));
        assertTrue(brief.limitations().stream().anyMatch(l -> l.startsWith("Fewer than 2 usable sources")));
    }

    /** Not an assertion: writes a full example brief to build/ so its JSON shape can be inspected. */
    @Test
    void writesSampleBrief() throws Exception {
        claim("S1", "T1", SUPPORTS, DATA);
        claim("S4", "T1", OPPOSES, DATA);
        claim("S1", "T2", SUPPORTS, DATA);
        claim("S2", "T2", SUPPORTS, EXPERT);
        claim("S5", "T3", SUPPORTS, ASSERTION);

        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writeValue(new File("build/sample-brief.json"), run());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Brief run() {
        return classifier.classify("Impact of AI on jobs", sources, statements, claims);
    }

    private void claim(String sourceId, String statementId, Stance stance, Evidence evidence) {
        int n = claims.size() + 1;
        claims.add(new Claim(sourceId + "-C" + n + "/" + statementId, sourceId, "claim " + n, statementId, null, stance, evidence,
                new Citation(sourceId, "P" + n, "quote " + n + " on " + statementId, evidence), "test"));
    }

    private static Source source(String id, SourceStatus status) {
        return new Source(id, "https://example.com/" + id, null, "https://example.com/" + id, false, status,
                status + " for test", 200, 0, "Title " + id, null, null, null, null, 500, List.of());
    }
}
