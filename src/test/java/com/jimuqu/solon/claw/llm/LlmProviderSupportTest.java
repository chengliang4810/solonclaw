package com.jimuqu.solon.claw.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class LlmProviderSupportTest {
    @Test
    void shouldAcceptLocalOllamaBaseUrl() {
        LlmProviderSupport.validateBaseUrl("http://127.0.0.1:11434");
    }

    @Test
    void shouldRejectMalformedPort() {
        assertThatThrownBy(() -> LlmProviderSupport.validateBaseUrl("http://127.0.0.1:6153export"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.baseUrl");
    }

    @Test
    void shouldRejectUserInfoCredentials() {
        assertThatThrownBy(
                        () ->
                                LlmProviderSupport.validateBaseUrl(
                                        "https://user:pass@api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
    }

    @Test
    void shouldRejectSecretLikeUrlToken() {
        assertThatThrownBy(
                        () ->
                                LlmProviderSupport.validateBaseUrl(
                                        "https://api.example.com/v1?api_key=sk-1234567890"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void shouldDetectOnlyNativeOpenAiHostAsDirect() {
        assertThat(LlmProviderSupport.isDirectOpenAiBaseUrl("https://api.openai.com/v1"))
                .isTrue();
        assertThat(LlmProviderSupport.isDirectOpenAiBaseUrl("https://api.openai.com.example/v1"))
                .isFalse();
        assertThat(LlmProviderSupport.isDirectOpenAiBaseUrl("https://proxy.example/api.openai.com/v1"))
                .isFalse();
    }

    @Test
    void shouldExtractBaseUrlHostnameLikeJimuqu() {
        assertThat(LlmProviderSupport.baseUrlHostname(null)).isEqualTo("");
        assertThat(LlmProviderSupport.baseUrlHostname("")).isEqualTo("");
        assertThat(LlmProviderSupport.baseUrlHostname("api.openai.com")).isEqualTo("api.openai.com");
        assertThat(LlmProviderSupport.baseUrlHostname("api.openai.com/v1")).isEqualTo("api.openai.com");
        assertThat(LlmProviderSupport.baseUrlHostname("https://API.OpenAI.com./v1"))
                .isEqualTo("api.openai.com");
        assertThat(LlmProviderSupport.baseUrlHostname("https://api.openai.com:443/v1"))
                .isEqualTo("api.openai.com");
        assertThat(LlmProviderSupport.baseUrlHostname("https://proxy.example.test/api.openai.com/v1"))
                .isEqualTo("proxy.example.test");
        assertThat(LlmProviderSupport.baseUrlHostname("https://api.openai.com.example/v1"))
                .isEqualTo("api.openai.com.example");
    }

    @Test
    void shouldMatchBaseUrlHostsWithoutSubstringFalsePositivesLikeJimuqu() {
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://openrouter.ai/api/v1", "openrouter.ai"))
                .isTrue();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://api.moonshot.ai/v1", "moonshot.ai"))
                .isTrue();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://OpenRouter.AI/v1", "OPENROUTER.AI"))
                .isTrue();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://openrouter.ai/v1", "openrouter.ai."))
                .isTrue();

        assertThat(LlmProviderSupport.baseUrlHostMatches("https://evil.test/moonshot.ai/v1", "moonshot.ai"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://proxy.example.test/openrouter.ai/v1", "openrouter.ai"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://moonshot.ai.evil/v1", "moonshot.ai"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://fake-openrouter.ai/v1", "openrouter.ai"))
                .isFalse();
    }

    @Test
    void shouldRejectOllamaHostLookalikesLikeJimuquSecurityAdvisory() {
        assertThat(LlmProviderSupport.baseUrlHostMatches("http://127.0.0.1:9000/ollama.com/v1", "ollama.com"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("http://ollama.com.attacker.test:9000/v1", "ollama.com"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("http://ollama.com.localtest.me:9000/v1", "ollama.com"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://ollama.ai/v1", "ollama.com"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("http://localhost:11434/v1", "ollama.com"))
                .isFalse();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://ollama.com/api/generate", "ollama.com"))
                .isTrue();
        assertThat(LlmProviderSupport.baseUrlHostMatches("https://api.ollama.com/v1", "ollama.com"))
                .isTrue();
    }

    @Test
    void shouldResolveProviderAwareOpenAiModelListUrls() {
        assertThat(LlmProviderSupport.buildModelListUrl("openai", "https://api.openai.com", "openai"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "openai", "https://api.openai.com/v1/chat/completions", "openai"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "openai", "https://api.openai.com/v1/responses", "openai-responses"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "openrouter", "https://openrouter.ai/api/v1", "openai"))
                .isEqualTo("https://openrouter.ai/api/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "openrouter", "https://proxy.example.test/openrouter", "openai"))
                .isEqualTo("https://proxy.example.test/openrouter/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "custom", "https://custom.example.test/v1/models", "openai"))
                .isEqualTo("https://custom.example.test/v1/models");
    }

    @Test
    void shouldResolveProviderAwareAnthropicModelListUrls() {
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "anthropic", "https://api.anthropic.com", "anthropic"))
                .isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "anthropic", "https://api.anthropic.com/v1", "anthropic"))
                .isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "anthropic", "https://api.anthropic.com/v1/messages", "anthropic"))
                .isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "anthropic", "https://api.anthropic.com/v1/models", "anthropic"))
                .isEqualTo("https://api.anthropic.com/v1/models");
    }

    @Test
    void shouldResolveProviderAwareOllamaModelListUrls() {
        assertThat(LlmProviderSupport.buildModelListUrl("ollama", "http://127.0.0.1:11434", "ollama"))
                .isEqualTo("http://127.0.0.1:11434/api/tags");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "ollama", "http://127.0.0.1:11434/api", "ollama"))
                .isEqualTo("http://127.0.0.1:11434/api/tags");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "ollama", "http://127.0.0.1:11434/api/chat", "ollama"))
                .isEqualTo("http://127.0.0.1:11434/api/tags");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "ollama", "http://127.0.0.1:11434/api/tags", "ollama"))
                .isEqualTo("http://127.0.0.1:11434/api/tags");
    }

    @Test
    void shouldResolveProviderAwareGeminiAndCustomModelListUrls() {
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "gemini", "https://generativelanguage.googleapis.com", "gemini"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "gemini", "https://generativelanguage.googleapis.com/v1", "gemini"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "gemini",
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent",
                                "gemini"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(
                        LlmProviderSupport.buildModelListUrl(
                                "custom", "https://custom.example.test/openai", "openai"))
                .isEqualTo("https://custom.example.test/openai/v1/models");
    }
}
