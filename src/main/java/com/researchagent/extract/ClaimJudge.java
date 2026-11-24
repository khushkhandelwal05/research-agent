package com.researchagent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.researchagent.llm.LlmClient;
import com.researchagent.model.Claim;
import com.researchagent.model.ExtractedClaim;
import com.researchagent.model.Paragraph;
import com.researchagent.model.Source;
import com.researchagent.model.Stance;
import com.researchagent.model.Statement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step B of claim extraction: one short LLM call per claim decides which topic statements it is evidence
 * for or against (at most two, often none). A small, single-decision prompt is far easier for a local
 * model than judging a whole chunk at once.
 */
public final class ClaimJudge {

    static final int MAX_JUDGMENTS = 2;

    private static final String SYSTEM_PROMPT = """
            You judge ONE claim from a source against a fixed list of statements about a topic.

            Topic: %s

            Statements:
            %s
            Decide which statements the claim is direct evidence for or against. Most claims bear on one \
            statement and many on none. Return a second judgment only when the claim clearly settles that \
            statement too, never more than 2, and an empty list when no statement fits. Do not force a fit.

            For each judgment give the stance on THAT statement:
              SUPPORTS = the claim is evidence that the statement is true
              OPPOSES  = the claim is evidence that the statement is false
              MIXED    = the claim itself points both ways
            Rules:
            - Judge the meaning, not the tone. A finding of "little", "no" or "not yet" an effect OPPOSES a \
            statement that the effect exists or is large, and SUPPORTS a statement that it is small.
            - Match the time. A forecast, prediction or expectation is evidence only about statements on the \
            future. A statement about what is happening now or "already" needs evidence about the present or past.
            - Risk is not the outcome. A claim that something could happen, or that a group is exposed, at risk \
            or less able to adapt, is evidence only about statements on risk or the future, not about what is \
            happening now. A measure of how able people are to adapt is not evidence about a program or policy.
            - Match the group. A claim about one group is evidence only about statements on that same group or on \
            everyone. Related groups are not the same group: new, young or entry-level people are not \
            "low-skilled", and graduates or professionals are evidence against a statement about low-skilled people.

            Example on a different topic. Statements: A "Remote work lowers productivity", \
            B "Remote work has no measurable effect on productivity", C "Remote work hurts new hires most", \
            D "Remote work hurts low-skilled staff most", E "Remote work will lower productivity over the next five years".
              "Output per hour fell 4%% after teams went remote" -> A SUPPORTS, B OPPOSES
              "We found no measurable change in output after the switch" -> A OPPOSES, B SUPPORTS
              "New hires took twice as long to ramp up when remote" -> C SUPPORTS (not D: new is not low-skilled)
              "Analysts expect remote teams to lose 10%% of output by 2030" -> E SUPPORTS (not A: a forecast says \
            nothing about today)
              "Half of call-centre roles are highly exposed to remote outsourcing" -> no judgments (exposure is \
            not lower productivity, and not about new hires)
              "Remote workers report higher job satisfaction" -> no judgments

            First write reasoning: one or two sentences on what the claim actually asserts and which statement, \
            if any, it bears on. Then the judgments.
            """;

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaimJudge(LlmClient llm) {
        this.llm = llm;
    }

    /** Judges every claim of one source. Claims fitting no statement come back with statementId == null. */
    public List<Claim> judge(Source source, List<ExtractedClaim> claims, String topic, List<Statement> statements)
            throws Exception {
        String system = SYSTEM_PROMPT.formatted(topic, statementList(statements));
        JsonNode schema = schema(statements);
        Set<String> validIds = statements.stream().map(Statement::id).collect(Collectors.toSet());
        Map<String, String> paragraphText = source.paragraphs().stream()
                .collect(Collectors.toMap(Paragraph::id, Paragraph::text, (a, b) -> a));

        List<Claim> out = new ArrayList<>();
        for (ExtractedClaim c : claims) {
            String user = "Claim: " + c.text()
                    + "\nQuote: \"" + c.citation().quote() + "\""
                    + "\nParagraph it comes from: " + paragraphText.getOrDefault(c.citation().paragraphId(), "");
            JsonNode reply = llm.chatJson(system, user, schema);
            String reasoning = reply.path("reasoning").asText("").strip();

            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode j : reply.path("judgments")) {
                String id = j.path("statement_id").asText("").strip();
                if (!validIds.contains(id) || !seen.add(id) || seen.size() > MAX_JUDGMENTS) continue;
                out.add(new Claim(c.id() + "/" + id, c.sourceId(), c.text(), id, null,
                        Stance.valueOf(j.path("stance").asText("MIXED")), c.evidence(), c.citation(), reasoning));
            }
            if (seen.isEmpty()) {
                // Fits no statement: kept as the source's own claim, compared only with identically labelled ones.
                out.add(new Claim(c.id(), c.sourceId(), c.text(), null, c.topicLabel(), Stance.SUPPORTS,
                        c.evidence(), c.citation(), reasoning));
            }
        }
        return out;
    }

    private static String statementList(List<Statement> statements) {
        StringBuilder sb = new StringBuilder();
        for (Statement s : statements) sb.append(s.id()).append(": ").append(s.text()).append('\n');
        return sb.toString();
    }

    private JsonNode schema(List<Statement> statements) {
        ObjectNode root = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        root.putArray("required").add("reasoning").add("judgments");
        ObjectNode props = root.putObject("properties");
        props.putObject("reasoning").put("type", "string");
        ObjectNode item = props.putObject("judgments").put("type", "array")
                .putObject("items").put("type", "object").put("additionalProperties", false);
        item.putArray("required").add("statement_id").add("stance");
        ObjectNode itemProps = item.putObject("properties");
        ArrayNode ids = itemProps.putObject("statement_id").put("type", "string").putArray("enum");
        statements.forEach(s -> ids.add(s.id()));
        itemProps.putObject("stance").put("type", "string").putArray("enum").add("SUPPORTS").add("OPPOSES").add("MIXED");
        return root;
    }
}
