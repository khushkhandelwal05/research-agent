package com.researchagent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.llm.LlmClient;
import com.researchagent.model.Citation;
import com.researchagent.model.Evidence;
import com.researchagent.model.ExtractedClaim;
import com.researchagent.model.Paragraph;
import com.researchagent.model.Source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step A of claim extraction: one LLM call per chunk of a source pulls out the points it makes,
 * stated neutrally with a quote and an evidence type. No statements, no stance: judging is step B
 * (ClaimJudge), because asking one call to do both made a 7B and a 14B model mislabel ~40-50% of claims.
 * Every quote is verified against the source and near-duplicates are dropped.
 */
public final class ClaimExtractor {

    /** ~1,200 words keeps prompt + output well inside an 8k context and keeps a local model focused. */
    static final int CHUNK_WORDS = 1200;
    static final int MAX_CLAIMS_PER_CHUNK = 8;

    private static final String SYSTEM_PROMPT = """
            You extract claims from ONE source for a research brief.

            Topic: %s

            A claim is one point the source makes about the topic: a finding, a figure, a forecast or an argument.
            For each claim return:
            - claim: one plain sentence restating what the source asserts, understandable on its own. Name who or \
            what is affected and the direction and size of the effect when the source gives them.
            - topic_label: 2-5 words naming what the claim is about.
            - evidence: what backs it.
                data      = cites numbers, a study, a survey or a dataset
                expert    = a named expert's or institution's judgment without data
                assertion = unsupported opinion, prediction, slogan or marketing
            - paragraph_id: the [P#] label of the paragraph the quote comes from.
            - quote: copied EXACTLY, character for character, from that paragraph. One sentence or part of one, \
            at most 40 words. Never paraphrase, never join text from two paragraphs.

            Rules:
            - Include findings of small, no or not-yet effects. They are often the most important claims, even \
            when the rest of the source argues the opposite.
            - Ignore author bios, navigation, ads, product pitches, calls to action and legal disclaimers.
            - Ignore what the source says about its own study: its methods, data, index or measure, scope and \
            limitations ("our index measures...", "this study focuses on...", "these results may not capture..."). \
            Those sentences describe the research, not the topic.
            - One claim per distinct point. Do not return the same sentence twice.
            - Return at most %d claims. If the text makes no claims about the topic, return an empty list.
            """;

    private static final JsonNode SCHEMA = Schemas.parse("""
            {"type":"object","additionalProperties":false,"required":["claims"],"properties":{
              "claims":{"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["claim","topic_label","evidence","paragraph_id","quote"],
                "properties":{
                  "claim":{"type":"string"},
                  "topic_label":{"type":"string"},
                  "evidence":{"type":"string","enum":["data","expert","assertion"]},
                  "paragraph_id":{"type":"string"},
                  "quote":{"type":"string"}}}}}}
            """);

    private final LlmClient llm;
    private final CitationVerifier verifier = new CitationVerifier();

    public ClaimExtractor(LlmClient llm) {
        this.llm = llm;
    }

    /** A model-proposed claim that was not kept, and why. */
    public record Rejected(String paragraphId, String quote, String why) {
    }

    public record Stats(String sourceId, int chunks, int proposed, int kept, int relocated, int rejected, long millis) {
    }

    public record Result(List<ExtractedClaim> claims, List<Rejected> rejected, Stats stats) {
    }

    public Result extract(Source source, String topic) throws Exception {
        long start = System.currentTimeMillis();
        String system = SYSTEM_PROMPT.formatted(topic, MAX_CLAIMS_PER_CHUNK);
        List<ExtractedClaim> claims = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        int proposed = 0;
        int relocated = 0;

        List<List<Paragraph>> chunks = chunk(source.paragraphs());
        for (List<Paragraph> chunk : chunks) {
            StringBuilder text = new StringBuilder("Source text:\n");
            for (Paragraph p : chunk) text.append('[').append(p.id()).append("] ").append(p.text()).append("\n\n");

            JsonNode reply = llm.chatJson(system, text.toString(), SCHEMA);
            for (JsonNode c : reply.path("claims")) {
                proposed++;
                String quote = c.path("quote").asText("").strip().replaceFirst("^\\[P\\d+\\]\\s*", "");
                String paragraphId = c.path("paragraph_id").asText("").strip().replaceAll("[\\[\\]]", "");
                String claimText = c.path("claim").asText("").strip();

                CitationVerifier.Check check = verifier.check(quote, paragraphId, chunk);
                if (!check.ok()) {
                    rejected.add(new Rejected(paragraphId, quote, check.outcome().name()));
                    continue;
                }
                if (claimText.isEmpty()) {
                    rejected.add(new Rejected(paragraphId, quote, "EMPTY_CLAIM"));
                    continue;
                }
                if (isDuplicate(quote, claims)) {
                    rejected.add(new Rejected(paragraphId, quote, "DUPLICATE"));
                    continue;
                }
                if (check.outcome() == CitationVerifier.Outcome.RELOCATED) relocated++;
                Evidence evidence = Evidence.parse(c.path("evidence").asText(""));
                String label = c.path("topic_label").asText("").strip().toLowerCase(Locale.ROOT);
                claims.add(new ExtractedClaim(source.id() + "-C" + (claims.size() + 1), source.id(), claimText,
                        label.isEmpty() ? "unlabeled" : label, evidence,
                        new Citation(source.id(), check.paragraphId(), quote, evidence)));
            }
        }
        return new Result(claims, rejected, new Stats(source.id(), chunks.size(), proposed, claims.size(), relocated,
                rejected.size(), System.currentTimeMillis() - start));
    }

    /** The same (or a contained) quote as a claim we already kept from this source. */
    private static boolean isDuplicate(String quote, List<ExtractedClaim> kept) {
        String q = CitationVerifier.normalize(quote);
        for (ExtractedClaim k : kept) {
            String other = CitationVerifier.normalize(k.citation().quote());
            if (other.contains(q) || q.contains(other)) return true;
        }
        return false;
    }

    /** Consecutive paragraphs, up to ~CHUNK_WORDS words each; a paragraph is never split. */
    static List<List<Paragraph>> chunk(List<Paragraph> paragraphs) {
        List<List<Paragraph>> chunks = new ArrayList<>();
        List<Paragraph> current = new ArrayList<>();
        int words = 0;
        for (Paragraph p : paragraphs) {
            int w = p.text().split("\\s+").length;
            if (!current.isEmpty() && words + w > CHUNK_WORDS) {
                chunks.add(current);
                current = new ArrayList<>();
                words = 0;
            }
            current.add(p);
            words += w;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    /** Small helper so prompt classes can declare their JSON schema as a text block. */
    static final class Schemas {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        static JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (Exception e) {
                throw new IllegalStateException("Bad schema", e);
            }
        }
    }
}
