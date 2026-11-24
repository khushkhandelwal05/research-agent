package com.researchagent.model;

/**
 * Evidence for a claim: a verbatim quote and where it is ("S2:P7"), plus what backs it.
 * The quote is checked against the paragraph before the claim is kept.
 */
public record Citation(String sourceId, String paragraphId, String quote, Evidence evidence) {
}
