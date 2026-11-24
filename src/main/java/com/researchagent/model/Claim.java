package com.researchagent.model;

/**
 * A judged claim: one source's position on one topic statement, with a verified quote. One extracted claim
 * can yield up to two of these (e.g. "little effect" supports "effect is small" and opposes "effect is large").
 * A claim that fits no statement has {@code statementId == null} and uses {@code otherLabel}.
 */
public record Claim(
        String id,            // e.g. "S2-C5/T3", or "S2-C5" when it fits no statement
        String sourceId,
        String text,          // the claim restated in one sentence
        String statementId,   // "T3", or null when it fits no statement
        String otherLabel,    // 2-5 word label, only when statementId is null
        Stance stance,
        Evidence evidence,
        Citation citation,
        String rationale) {   // the judge's one- or two-sentence reason

    public boolean matched() {
        return statementId != null;
    }
}
