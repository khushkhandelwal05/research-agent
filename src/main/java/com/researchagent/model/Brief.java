package com.researchagent.model;

import java.util.List;
import java.util.Map;

/**
 * The final structured brief (written as 04-brief.json).
 *
 * Rows are the topic statements, written before any source was read. Per statement, in plain code:
 *   consensusClaims    - 2+ usable sources take the same side and none take the other
 *   contradictedClaims - claims one source makes that others contradict (some SUPPORT, some OPPOSE)
 *   singleSourceClaims - claims only one source makes (including claims that fit no statement)
 *   unaddressedClaims  - statements no usable source supports or opposes
 * Every claim carries the citations that back it. A source that is silent on a statement counts for
 * nothing: silence is neither agreement nor contradiction.
 */
public record Brief(
        String topic,
        int sourcesUsable,
        int sourcesTotal,
        List<SourceSummary> sources,
        List<Statement> statements,
        List<MatrixRow> matrix,
        List<ConsensusClaim> consensusClaims,
        List<ContradictedClaim> contradictedClaims,
        List<SingleSourceClaim> singleSourceClaims,
        List<UnaddressedClaim> unaddressedClaims,
        List<String> limitations) {

    /** One row per input, including the broken ones, so the reader sees what the brief is built on. */
    public record SourceSummary(String id, String title, String url, SourceStatus status, String reason) {
    }

    /**
     * One statement and each source's position on it, in input order.
     * Cells: "+" supports, "−" opposes, "±" mixed, "·" silent, "⛔" source unusable.
     */
    public record MatrixRow(String statementId, String statement, Map<String, String> cells) {
    }

    /** Several sources take the same side. {@code rankReason} says why it sits where it does. */
    public record ConsensusClaim(
            String statementId,
            String statement,
            Stance stance,
            List<String> agreeingSources,
            List<String> mixedSources,     // also discuss it, arguing both sides; they neither confirm nor oppose
            int usableSources,
            List<Citation> citations,
            String rankReason) {
    }

    /** One source's claim that other sources contradict: each side lists its sources and evidence. */
    public record ContradictedClaim(
            String statementId,
            String statement,
            List<Side> sides,
            String rankReason) {

        public record Side(Stance stance, List<String> sources, List<Citation> citations) {
        }
    }

    /**
     * Only one source makes this claim, so it is unconfirmed. {@code statementId} is null when the claim
     * fits none of the topic statements; {@code statement} then holds the claim's own short label.
     */
    public record SingleSourceClaim(
            String statementId,
            String statement,
            Stance stance,
            String sourceId,
            List<Citation> citations,
            Evidence strongestEvidence,
            String note,
            String rankReason) {
    }

    /** A statement no usable source supports or opposes. No citations: nothing in the sources covers it. */
    public record UnaddressedClaim(String statementId, String statement, String whyItMatters) {
    }
}
