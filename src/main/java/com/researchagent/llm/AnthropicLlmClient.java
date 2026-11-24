package com.researchagent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Claude via the official Anthropic Java SDK, with the reply constrained to the JSON schema
 * (structured outputs: output_config.format). Optional: used only when provider=anthropic.
 */
public final class AnthropicLlmClient implements LlmClient {

    /** Models that accept server-side refusal fallbacks; on others the parameter is left out. */
    private static final Set<String> FALLBACK_MODELS =
            Set.of("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final OutputConfig.Effort effort;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param apiKeyEnv name of the environment variable holding the key. The key itself is never read from
     *                  a file. If that variable is unset, the SDK's normal lookup (ANTHROPIC_API_KEY, etc.) applies.
     */
    public AnthropicLlmClient(String model, String baseUrl, String apiKeyEnv, String effort) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv().timeout(Duration.ofMinutes(10));
        String key = apiKeyEnv == null ? null : System.getenv(apiKeyEnv);
        if (key != null && !key.isBlank()) builder.apiKey(key);
        if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl);
        this.client = builder.build();
        this.model = model;
        this.effort = OutputConfig.Effort.of(effort == null ? "medium" : effort);
    }

    @Override
    public String describe() {
        return "anthropic:" + model + ":" + effort;
    }

    @Override
    public JsonNode chatJson(String system, String user, JsonNode schema) throws IOException {
        Message message;
        try {
            message = client.messages().create(params(system, user, schema));
        } catch (RuntimeException e) {
            throw new IOException("Claude API call failed (" + model + "). Check that ANTHROPIC_API_KEY, or the "
                    + "variable named by api_key_env, is set and valid. " + e.getMessage(), e);
        }
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new IOException("Claude declined this request: " + message.stopDetails().map(Object::toString).orElse("no details"));
        }
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new IOException("Claude's reply was cut off at max_tokens; raise it or send less text per call.");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
        return mapper.readTree(text);
    }

    /** Package-private so a test can check the request builds without calling the API. */
    MessageCreateParams params(String system, String user, JsonNode schema) {
        JsonOutputFormat.Schema.Builder schemaBuilder = JsonOutputFormat.Schema.builder();
        schema.properties().forEach(f -> schemaBuilder.putAdditionalProperty(f.getKey(), JsonValue.fromJsonNode(f.getValue())));

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .addUserMessage(user)
                .outputConfig(OutputConfig.builder()
                        .effort(effort)
                        .format(JsonOutputFormat.builder().schema(schemaBuilder.build()).build())
                        .build());
        if (FALLBACK_MODELS.contains(model)) {
            // If a safety classifier declines, the server retries on a fallback model it picks by category.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }
}
