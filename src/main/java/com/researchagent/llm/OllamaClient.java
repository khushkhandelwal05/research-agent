package com.researchagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Any model served by a local Ollama server. Every call is constrained to a JSON schema
 * (Ollama "structured outputs") and run at temperature 0 with a fixed seed.
 */
public final class OllamaClient implements LlmClient {

    private static final int CONTEXT_TOKENS = 8192; // set explicitly: Ollama silently truncates past its default

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String describe() {
        return "ollama:" + model;
    }

    @Override
    public JsonNode chatJson(String system, String user, JsonNode schema) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.set("format", schema);
        // Models with a reasoning mode (qwen3, deepseek-r1) would otherwise "think" for minutes before each
        // JSON answer; models without one ignore the flag.
        body.put("think", false);
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);
        options.put("seed", 42);
        options.put("num_ctx", CONTEXT_TOKENS);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofMinutes(10)) // a 14B model on a laptop can take minutes per chunk
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new IOException("Cannot reach Ollama at " + baseUrl + ". Is it running? Start it with `ollama serve`.", e);
        }
        if (response.statusCode() == 404 && response.body().contains("not found")) {
            throw new IOException("Ollama has no model '" + model + "'. Download it with `ollama pull " + model + "`.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }
        String content = mapper.readTree(response.body()).path("message").path("content").asText();
        return mapper.readTree(content); // schema-constrained, but still fail loudly if it isn't JSON
    }
}
