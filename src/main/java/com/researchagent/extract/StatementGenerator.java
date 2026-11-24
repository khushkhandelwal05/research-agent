package com.researchagent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.researchagent.llm.LlmClient;
import com.researchagent.model.Statement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2: turns the topic into the statements every source is judged against.
 * Runs on the topic alone, before any source is read, so "unaddressed" isn't biased toward what the
 * sources happen to say. An analyst can replace the list with --statements file.txt.
 */
public final class StatementGenerator {

    static final int MIN_STATEMENTS = 6;
    static final int MAX_STATEMENTS = 10;

    private static final String SYSTEM_PROMPT = """
            You prepare the comparison framework for a research brief, BEFORE any sources are read.

            Topic: %s

            Write %d to %d statements that sources on this topic are likely to argue for or against.
            Rules for each statement:
            - ONE assertion that a source could clearly SUPPORT or OPPOSE. Not a neutral topic label, and never \
            two ideas joined by "and", "but" or "while".
              Bad: "AI's impact on jobs". Bad: "AI raises productivity but widens inequality".
              Good: "AI is already causing net job losses".
            - At most 15 words, plain language, no hedges such as "may" or "might".
            - Distinct: no two statements assert the same thing.

            Cover each of these, phrased for this topic:
            - that the effect is already visible today
            - the skeptic's view: that the effect is small, absent or not yet measurable
            - how large the effect will become
            - which groups are most affected, one statement per group, for example by age or experience level, \
            by skill or education level, or by occupation
            - which sectors or areas are most affected
            - what can reduce the harm or increase the benefit

            For each statement also give why_it_matters: one sentence on why a reader of the brief would care.
            """;

    private static final JsonNode SCHEMA = ClaimExtractor.Schemas.parse("""
            {"type":"object","additionalProperties":false,"required":["statements"],"properties":{
              "statements":{"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["text","why_it_matters"],
                "properties":{"text":{"type":"string"},"why_it_matters":{"type":"string"}}}}}}
            """);

    private final LlmClient llm;

    public StatementGenerator(LlmClient llm) {
        this.llm = llm;
    }

    public List<Statement> generate(String topic) throws Exception {
        JsonNode reply = llm.chatJson(SYSTEM_PROMPT.formatted(topic, MIN_STATEMENTS, MAX_STATEMENTS),
                "Write the statements.", SCHEMA);
        List<Statement> out = new ArrayList<>();
        for (JsonNode s : reply.path("statements")) {
            String text = s.path("text").asText("").strip();
            if (text.isEmpty() || out.size() == MAX_STATEMENTS) continue;
            out.add(new Statement("T" + (out.size() + 1), text, s.path("why_it_matters").asText("").strip()));
        }
        if (out.isEmpty()) throw new IllegalStateException("The model returned no topic statements.");
        return out;
    }

    /** One statement per line; blank lines and lines starting with # are ignored. */
    public static List<Statement> fromFile(Path file) throws IOException {
        List<Statement> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            out.add(new Statement("T" + (out.size() + 1), text, "Provided by the analyst"));
        }
        return out;
    }
}
