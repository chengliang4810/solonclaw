package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.llm.dialect.RawResponseLoggingChatDialect;
import com.jimuqu.solon.claw.media.MediaInputBoundaryService;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.noear.solon.ai.chat.content.ImageBlock;
import java.util.Collections;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

/** 校验 LLM provider 配置的前置失败逻辑。 */
public class SolonAiLlmGatewayConfigTest {
    @Test
    void shouldLoadPromptCacheConfig() {
        Properties props = new Properties();
        props.setProperty("solonclaw.runtime.home", "target/test-runtime/prompt-cache-config");
        props.setProperty("solonclaw.llm.promptCache.enabled", "true");
        props.setProperty("solonclaw.llm.promptCache.ttl", "1h");
        props.setProperty("solonclaw.llm.promptCache.layout", "system_and_3");

        AppConfig config = AppConfig.load(org.noear.solon.core.Props.from(props));

        assertThat(config.getLlm().getPromptCache().isEnabled()).isTrue();
        assertThat(config.getLlm().getPromptCache().getTtl()).isEqualTo("1h");
        assertThat(config.getLlm().getPromptCache().getLayout()).isEqualTo("system_and_3");
    }

    @Test
    void shouldFailFastForUnsupportedProvider() {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("unknown-provider");
        config.getLlm().setApiUrl("https://example.com/v1/chat/completions");
        config.getLlm().setModel("test-model");

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-1");

        assertThatThrownBy(() -> gateway.chat(session, "system", "hello", Collections.emptyList()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的 provider");
    }

    @Test
    void shouldRequireResponsesEndpointForOpenaiResponsesProvider() {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("openai-responses");
        config.getLlm().setApiUrl("https://example.com/v1/chat/completions");
        config.getLlm().setModel("gpt-5.4");

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-2");

        assertThatThrownBy(() -> gateway.chat(session, "system", "hello", Collections.emptyList()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/responses");
    }

    @Test
    void shouldFailFastForPlaceholderApiKey() {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("openai");
        config.getLlm().setDialect("openai");
        config.getLlm().setApiUrl("https://example.com/v1/chat/completions");
        config.getLlm().setApiKey("example");
        config.getLlm().setModel("gpt-5.4");

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-placeholder-key");

        assertThatThrownBy(() -> gateway.chat(session, "system", "hello", Collections.emptyList()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位符密钥");
    }

    @Test
    void shouldRejectMetadataApiUrlForRemoteProvider() {
        AppConfig config = remoteProviderConfig("http://169.254.169.254/latest/meta-data");

        assertThatThrownBy(() -> validateLlmConfig(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM apiUrl 被安全策略阻断")
                .hasMessageContaining("云元数据");
    }

    @Test
    void shouldRejectPrivateApiUrlForRemoteProviderByDefault() {
        AppConfig config = remoteProviderConfig("http://127.0.0.1:8080/v1/chat/completions");

        assertThatThrownBy(() -> validateLlmConfig(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM apiUrl 被安全策略阻断")
                .hasMessageContaining("内网/私有地址");
    }

    @Test
    void shouldAllowPrivateApiUrlForRemoteProviderWhenConfigured() throws Exception {
        AppConfig config = remoteProviderConfig("http://127.0.0.1:8080/v1/chat/completions");
        config.getSecurity().setAllowPrivateUrls(true);

        validateLlmConfig(config);
    }

    @Test
    void shouldRejectUserInfoInProviderApiUrl() {
        AppConfig config = remoteProviderConfig("https://user:pass@example.com/v1/chat/completions");

        assertThatThrownBy(() -> validateLlmConfig(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM apiUrl 被安全策略阻断")
                .hasMessageContaining("userinfo");
    }

    @Test
    void shouldAllowLocalOllamaApiUrl() throws Exception {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("ollama");
        config.getLlm().setDialect("ollama");
        config.getLlm().setApiUrl("http://localhost:11434/api/chat");
        config.getLlm().setModel("llama3");

        validateLlmConfig(config);
    }

    @Test
    void shouldRejectMetadataApiUrlForOllamaProvider() {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("ollama");
        config.getLlm().setDialect("ollama");
        config.getLlm().setApiUrl("http://169.254.169.254/latest/meta-data");
        config.getLlm().setModel("llama3");

        assertThatThrownBy(() -> validateLlmConfig(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM apiUrl 被安全策略阻断")
                .hasMessageContaining("云元数据");
    }

    @Test
    void shouldUseRawResponseLoggingDialectForOpenaiResponsesProvider() throws Exception {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("openai-responses");
        config.getLlm().setApiUrl("https://example.com/v1/responses");
        config.getLlm().setModel("gpt-5.4");

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        Method buildChatModel =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "buildChatModel", AppConfig.LlmConfig.class);
        buildChatModel.setAccessible(true);

        ChatModel chatModel = (ChatModel) buildChatModel.invoke(gateway, config.getLlm());

        assertThat(chatModel.getDialect()).isInstanceOf(RawResponseLoggingChatDialect.class);
        assertThat(((RawResponseLoggingChatDialect) chatModel.getDialect()).getDialectName())
                .isEqualTo("openai-responses");
    }

    @Test
    void shouldUseRawResponseLoggingDialectForOpenaiProvider() throws Exception {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("openai");
        config.getLlm().setDialect("openai");
        config.getLlm().setApiUrl("https://example.com/v1/chat/completions");
        config.getLlm().setModel("gpt-5.4");

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        Method buildChatModel =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "buildChatModel", AppConfig.LlmConfig.class);
        buildChatModel.setAccessible(true);

        ChatModel chatModel = (ChatModel) buildChatModel.invoke(gateway, config.getLlm());

        assertThat(chatModel.getDialect()).isInstanceOf(RawResponseLoggingChatDialect.class);
        assertThat(((RawResponseLoggingChatDialect) chatModel.getDialect()).getDialectName())
                .isEqualTo("openai");
    }

    @Test
    void shouldUseRawResponseLoggingDialectForAllSupportedProviders() throws Exception {
        assertLoggingDialect("ollama", "http://localhost:11434/api/chat", "llama3");
        assertLoggingDialect("gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-pro");
        assertLoggingDialect("anthropic", "https://api.anthropic.com/v1/messages", "claude-sonnet");
    }

    @Test
    void shouldBuildMultimodalPromptForImageAttachments() throws Exception {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(new AppConfig());
        AgentRunContext runContext = new AgentRunContext(null, "run-1", "session-1", "MEMORY:cli:session-1");
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("image");
        attachment.setOriginalName("shot.png");
        attachment.setMimeType("image/png");
        attachment.setData("iVBORw0KGgo=");
        runContext.setUserAttachments(Arrays.asList(attachment));
        Method userPrompt =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "userPrompt", String.class, AgentRunContext.class);
        userPrompt.setAccessible(true);

        Prompt prompt = (Prompt) userPrompt.invoke(gateway, "Describe it", runContext);

        assertThat(prompt.getMessages()).hasSize(1);
        assertThat(prompt.getMessages().get(0)).isInstanceOf(UserMessage.class);
        UserMessage message = (UserMessage) prompt.getMessages().get(0);
        assertThat(message.getBlocks()).hasSize(2);
        assertThat(message.getBlocks().get(1)).isInstanceOf(ImageBlock.class);
        ImageBlock image = (ImageBlock) message.getBlocks().get(1);
        assertThat(image.getMimeType()).isEqualTo("image/png");
        assertThat(image.getData()).isEqualTo("iVBORw0KGgo=");
    }

    @Test
    void shouldApplySafeImageAttachmentBoundaries() throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome("target/test-runtime/image-boundaries");
        config.getRuntime().setCacheDir("target/test-runtime/image-boundaries/cache");
        AttachmentCacheService cacheService = new AttachmentCacheService(config);
        File mediaDir = cacheService.platformDir(com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY);
        Files.createDirectories(mediaDir.toPath());
        File localImage = new File(mediaDir, "safe.png");
        Files.write(localImage.toPath(), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        File outsideImage = new File("target/outside-image.png");
        Files.write(outsideImage.toPath(), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        AgentRunContext runContext = new AgentRunContext(null, "run-1", "session-1", "MEMORY:cli:session-1");
        runContext.setUserAttachments(
                Arrays.asList(
                        imageWithData("image/png", "iVBORw0KGgo="),
                        imageWithUrl("image/jpeg", "https://example.com/a.jpg"),
                        imageWithLocalPath("image/png", localImage.getAbsolutePath()),
                        imageWithData("text/plain", "aGVsbG8="),
                        attachment("file", "image/png", "iVBORw0KGgo=", null, null),
                        imageWithData("image/svg+xml", "PHN2Zz48L3N2Zz4="),
                        imageWithData("image/png", java.util.Base64.getEncoder().encodeToString(new byte[6 * 1024 * 1024])),
                        imageWithLocalPath("image/png", outsideImage.getAbsolutePath()),
                        imageWithData("image/png", "iVBORw0KGgo=")));
        Method userPrompt =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "userPrompt", String.class, AgentRunContext.class);
        userPrompt.setAccessible(true);

        Prompt prompt = (Prompt) userPrompt.invoke(gateway, "Describe it", runContext);

        UserMessage message = (UserMessage) prompt.getMessages().get(0);
        assertThat(message.getBlocks()).hasSize(4);
        assertThat(message.getBlocks())
                .filteredOn(block -> block instanceof ImageBlock)
                .hasSize(3);
    }

    @Test
    void shouldRedactImageAttachmentReadFailureLogs() throws Exception {
        String leakedToken = "sk-image-path12345";
        AppConfig config = new AppConfig();
        config.getRuntime().setCacheDir("target/test-runtime/redact-image/cache");
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("image");
        attachment.setOriginalName("secret.png");
        attachment.setMimeType("image/png");
        File missing = new File(config.getRuntime().getCacheDir(), leakedToken + "/missing.png");
        attachment.setLocalPath(missing.getAbsolutePath());
        MediaInputBoundaryService service = new MediaInputBoundaryService(config);

        assertThat(service.toImageBlock(attachment)).isNull();
        assertThat(SecretRedactor.redact(attachment.getLocalPath(), 400))
                .contains("***")
                .doesNotContain(leakedToken);
    }

    private void assertLoggingDialect(String provider, String apiUrl, String model)
            throws Exception {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider(provider);
        config.getLlm().setDialect(provider);
        config.getLlm().setApiUrl(apiUrl);
        config.getLlm().setModel(model);

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        Method buildChatModel =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "buildChatModel", AppConfig.LlmConfig.class);
        buildChatModel.setAccessible(true);

        ChatModel chatModel = (ChatModel) buildChatModel.invoke(gateway, config.getLlm());

        assertThat(chatModel.getDialect()).isInstanceOf(RawResponseLoggingChatDialect.class);
        assertThat(((RawResponseLoggingChatDialect) chatModel.getDialect()).getDialectName())
                .isEqualTo(provider);
    }

    private AppConfig remoteProviderConfig(String apiUrl) {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider("openai");
        config.getLlm().setDialect("openai");
        config.getLlm().setApiUrl(apiUrl);
        config.getLlm().setApiKey("sk-test-valid-key");
        config.getLlm().setModel("gpt-5.4");
        return config;
    }

    private void validateLlmConfig(AppConfig config) throws Exception {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        Method validate =
                SolonAiLlmGateway.class.getDeclaredMethod("validate", AppConfig.LlmConfig.class);
        validate.setAccessible(true);
        try {
            validate.invoke(gateway, config.getLlm());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    private MessageAttachment imageWithData(String mimeType, String data) {
        return attachment("image", mimeType, data, null, null);
    }

    private MessageAttachment imageWithUrl(String mimeType, String url) {
        return attachment("image", mimeType, null, url, null);
    }

    private MessageAttachment imageWithLocalPath(String mimeType, String localPath) {
        return attachment("image", mimeType, null, null, localPath);
    }

    private MessageAttachment attachment(
            String kind, String mimeType, String data, String url, String localPath) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind(kind);
        attachment.setOriginalName("image.png");
        attachment.setMimeType(mimeType);
        attachment.setData(data);
        attachment.setUrl(url);
        attachment.setLocalPath(localPath);
        return attachment;
    }
}
