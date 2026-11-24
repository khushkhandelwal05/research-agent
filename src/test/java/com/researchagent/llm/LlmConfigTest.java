package com.researchagent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigTest {

    @TempDir
    Path dir;

    @Test
    void defaultsToLocalQwen3WhenThereIsNoConfigFile() throws Exception {
        LlmConfig c = LlmConfig.load(dir.resolve("missing.properties"), Map.of());
        assertEquals("ollama", c.provider());
        assertEquals("qwen3:14b", c.model());
        assertEquals("http://localhost:11434", c.baseUrl());
        assertNull(c.apiKeyEnv());
    }

    @Test
    void anthropicDefaultsToOpus55AndTheStandardKeyVariable() throws Exception {
        LlmConfig c = LlmConfig.load(null, Map.of("provider", "anthropic"));
        assertEquals("claude-opus-5-5", c.model());
        assertEquals("ANTHROPIC_API_KEY", c.apiKeyEnv());
        assertEquals("medium", c.effort());
        assertNull(c.baseUrl());
    }

    @Test
    void commandLineFlagsWinOverTheFile() throws Exception {
        Path file = dir.resolve("agent.properties");
        Files.writeString(file, "provider=ollama\nmodel=qwen2.5:7b\n");
        assertEquals("qwen2.5:7b", LlmConfig.load(file, Map.of()).model());
        assertEquals("llama3.1:8b", LlmConfig.load(file, Map.of("model", "llama3.1:8b")).model());
    }

    @Test
    void refusesAKeyWrittenIntoTheConfigFile() throws Exception {
        Path file = dir.resolve("agent.properties");
        Files.writeString(file, "provider=anthropic\napi_key=sk-ant-not-a-real-key\n");
        var e = assertThrows(IllegalArgumentException.class, () -> LlmConfig.load(file, Map.of()));
        assertTrue(e.getMessage().contains("environment variable"));
    }

    @Test
    void rejectsAnUnknownProvider() {
        assertThrows(IllegalArgumentException.class, () -> LlmConfig.load(null, Map.of("provider", "foo")));
    }
}
