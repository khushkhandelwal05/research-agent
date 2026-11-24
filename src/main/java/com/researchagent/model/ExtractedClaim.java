package com.researchagent.model;

/**
 * Step A output: one point a source makes, stated neutrally, with a verified quote. Not yet tied to any
 * topic statement; step B (ClaimJudge) decides which statements it bears on and how.
 */
public record ExtractedClaim(
        String id,          // e.g. "S2-C5"
        String sourceId,
        String text,        // one standalone sentence restating what the source asserts
        String topicLabel,  // 2-5 words; used if the claim fits no statement
        Evidence evidence,
        Citation citation) {
}
