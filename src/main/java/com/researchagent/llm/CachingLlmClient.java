package com.researchagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Caches replies on disk, keyed by provider + model + prompt + schema. Re-runs are instant and
 * repeatable, a demo never waits on the model, and a paid API is never called twice for the same input.
 */
public final class CachingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Path cacheDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public CachingLlmClient(LlmClient delegate, Path cacheDir) {
        this.delegate = delegate;
        this.cacheDir = cacheDir;
    }

    @Override
    public JsonNode chatJson(String system, String user, JsonNode schema) throws Exception {
        String key = sha256(String.join("\u0000", delegate.describe(), system, user, mapper.writeValueAsString(schema)));
        Path cached = cacheDir.resolve(key + ".json");
        if (Files.exists(cached)) {
            return mapper.readTree(cached.toFile());
        }
        JsonNode reply = delegate.chatJson(system, user, schema);
        Files.createDirectories(cacheDir);
        Files.writeString(cached, mapper.writeValueAsString(reply), StandardCharsets.UTF_8);
        return reply;
    }

    @Override
    public String describe() {
        return delegate.describe();
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
