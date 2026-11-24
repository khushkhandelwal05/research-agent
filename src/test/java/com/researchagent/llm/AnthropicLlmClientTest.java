package com.researchagent.llm;

import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds the Claude request without sending it, so it runs with no API key and no network. */
class AnthropicLlmClientTest {

    private final JsonNode schema = read("""
            {"type":"object","additionalProperties":false,"required":["claims"],
             "properties":{"claims":{"type":"array","items":{"type":"string"}}}}
            """);

    @Test
    void requestCarriesTheSchemaEffortAndRefusalFallback() {
        MessageCreateParams p = new AnthropicLlmClient("claude-opus-5-5", null, "NO_SUCH_VAR", "medium")
                .params("system prompt", "user text", schema);

        assertEquals("claude-opus-5-5", p.model().asString());
        var output = p.outputConfig().orElseThrow();
        assertEquals("medium", output.effort().orElseThrow().asString());
        String format = output.format().orElseThrow().toString();
        assertTrue(format.contains("claims"), "schema is passed through: " + format);
        assertEquals(com.anthropic.core.JsonValue.from("default"), p._additionalBodyProperties().get("fallbacks"));
        assertTrue(p._headers().values("anthropic-beta").contains("server-side-fallback-2026-07-01"));
    }

    @Test
    void noFallbackParameterOnModelsThatDoNotSupportIt() {
        MessageCreateParams p = new AnthropicLlmClient("claude-haiku-5-5", null, null, "low")
                .params("s", "u", schema);
        assertFalse(p._additionalBodyProperties().containsKey("fallbacks"));
        assertTrue(p._headers().values("anthropic-beta").isEmpty());
    }

    private static JsonNode read(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
