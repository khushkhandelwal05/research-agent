package com.researchagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** One schema-constrained chat call. Implemented by each provider; wrapped by {@link CachingLlmClient}. */
public interface LlmClient {

    /** Sends a system prompt and one user message; returns the reply parsed as JSON matching {@code schema}. */
    JsonNode chatJson(String system, String user, JsonNode schema) throws Exception;

    /** Provider and model, e.g. "ollama:qwen3:14b". Part of the cache key, so switching models never reuses answers. */
    String describe();
}
