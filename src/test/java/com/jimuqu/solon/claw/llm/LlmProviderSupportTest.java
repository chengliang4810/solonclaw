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
}
