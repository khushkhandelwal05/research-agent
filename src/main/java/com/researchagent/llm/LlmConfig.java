package com.researchagent.llm;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Which model the agent uses. Built from defaults, then agent.properties (if present), then command-line flags.
 *
 *   provider    ollama (default) | anthropic
 *   model       ollama: any pulled model, default qwen3:14b; anthropic: default claude-opus-5-5
 *   base_url    ollama: default http://localhost:11434; anthropic: optional override
 *   api_key_env anthropic only: NAME of the environment variable holding the key (default ANTHROPIC_API_KEY)
 *   effort      anthropic only: low | medium | high | xhigh | max (default medium)
 *
 * API keys are never read from the config file: the file names the variable, the shell holds the secret.
 */
public record LlmConfig(String provider, String model, String baseUrl, String apiKeyEnv, String effort) {

    public static final String OLLAMA = "ollama";
    public static final String ANTHROPIC = "anthropic";

    private static final Map<String, String> DEFAULT_MODEL = Map.of(OLLAMA, "qwen3:14b", ANTHROPIC, "claude-opus-5-5");

    /** Reads {@code file} if it exists; {@code overrides} (from the command line) win over the file. */
    public static LlmConfig load(Path file, Map<String, String> overrides) throws IOException {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            for (String name : p.stringPropertyNames()) {
                if (name.toLowerCase(Locale.ROOT).matches(".*(api_key|token|secret)$")) {
                    throw new IllegalArgumentException(file + " contains '" + name + "'. Do not put keys in the config "
                            + "file: export the key as an environment variable and set api_key_env to its name.");
                }
            }
        }
        overrides.forEach((k, v) -> {
            if (v != null) p.setProperty(k, v);
        });

        String provider = p.getProperty("provider", OLLAMA).strip().toLowerCase(Locale.ROOT);
        if (!DEFAULT_MODEL.containsKey(provider)) {
            throw new IllegalArgumentException("Unknown provider '" + provider + "'. Use 'ollama' or 'anthropic'.");
        }
        return new LlmConfig(
                provider,
                p.getProperty("model", DEFAULT_MODEL.get(provider)).strip(),
                p.getProperty("base_url", provider.equals(OLLAMA) ? "http://localhost:11434" : null),
                p.getProperty("api_key_env", provider.equals(ANTHROPIC) ? "ANTHROPIC_API_KEY" : null),
                p.getProperty("effort", "medium").strip().toLowerCase(Locale.ROOT));
    }

    /** The provider's client, wrapped in the on-disk cache. */
    public LlmClient create(Path cacheDir) {
        LlmClient client = switch (provider) {
            case ANTHROPIC -> new AnthropicLlmClient(model, baseUrl, apiKeyEnv, effort);
            default -> new OllamaClient(baseUrl, model);
        };
        return new CachingLlmClient(client, cacheDir);
    }

    @Override
    public String toString() {
        return provider + " / " + model + (provider.equals(ANTHROPIC) ? " (effort " + effort + ", key from $" + apiKeyEnv + ")" : " at " + baseUrl);
    }
}
