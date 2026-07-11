package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.VisionAnalysisService;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.MediaSpeechTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.VisionAnalyzeTools;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;

class MediaImageToolsTest {
    private static final String PNG_DATA_URL =
            "data:image/png;base64,"
                    + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=";

    @TempDir Path tempDir;

    @Test
    void imageGenerateSchemaMatchesTheFourConfirmedParameters() {
        RecordingImageProvider provider = new RecordingImageProvider(true, 3);
        ImageGenerationService service = imageService(provider, "image-schema");
        MediaSpeechTools tools = new MediaSpeechTools(service, null);

        FunctionTool tool = functionTool(tools.getTools(), "image_generate");
        Map<String, Object> schema = ONode.deserialize(tool.inputSchema(), Map.class);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> aspectRatio = (Map<String, Object>) properties.get("aspect_ratio");

        assertThat(properties.keySet())
                .containsExactlyInAnyOrder(
                        "prompt", "aspect_ratio", "image_url", "reference_image_urls");
        assertThat(aspectRatio.get("default")).isEqualTo("landscape");
        assertThat((List<Object>) aspectRatio.get("enum"))
                .containsExactly("landscape", "square", "portrait");
        assertThat(tool.inputSchema())
                .doesNotContain("aspectRatio", "optionsJson", "model", "quality", "format", "size");
    }

    @Test
    void imageGenerateDefaultsLandscapeAndPassesPreparedSourcesToProvider() {
        RecordingImageProvider provider = new RecordingImageProvider(true, 3);
        ImageGenerationService service = imageService(provider, "image-forward");

        ImageGenerationService.ImageGenerationOutcome outcome =
                service.generate(
                        "保留主体并改成水彩", null, PNG_DATA_URL, Collections.singletonList(PNG_DATA_URL));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(provider.aspectRatio).isEqualTo("landscape");
        assertThat(provider.imageUrl).isNotBlank();
        assertThat(new File(provider.imageUrl)).isFile();
        assertThat(provider.referenceImageUrls).hasSize(1);
        assertThat(new File(provider.referenceImageUrls.get(0))).isFile();
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void imageGenerateToolRejectsInvalidAspectInsteadOfDefaultingIt() throws Throwable {
        RecordingImageProvider provider = new RecordingImageProvider(true, 3);
        MediaSpeechTools tools =
                new MediaSpeechTools(imageService(provider, "image-invalid"), null);
        FunctionTool tool = functionTool(tools.getTools(), "image_generate");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("prompt", "生成图片");
        args.put("aspect_ratio", "wide");

        Map<String, Object> result =
                ONode.deserialize(String.valueOf(tool.handle(args)), Map.class);

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("error")).asString().contains("aspect_ratio");
        assertThat(provider.calls).hasValue(0);
    }

    @Test
    void imageGenerateRejectsUnsupportedOrOutOfRangeInputsBeforeProviderCall() {
        RecordingImageProvider textOnly = new RecordingImageProvider(false, 0);
        ImageGenerationService textOnlyService = imageService(textOnly, "image-text-only");

        ImageGenerationService.ImageGenerationOutcome unsupported =
                textOnlyService.generate("编辑", "square", PNG_DATA_URL, Collections.emptyList());
        ImageGenerationService.ImageGenerationOutcome invalidAspect =
                textOnlyService.generate("生成", "wide", null, Collections.emptyList());

        assertThat(unsupported.isSuccess()).isFalse();
        assertThat(unsupported.getError()).contains("does not support image input");
        assertThat(invalidAspect.isSuccess()).isFalse();
        assertThat(invalidAspect.getError()).contains("aspect_ratio");
        assertThat(textOnly.calls).hasValue(0);

        RecordingImageProvider singleSource = new RecordingImageProvider(true, 1);
        ImageGenerationService singleSourceService = imageService(singleSource, "image-source-cap");
        ImageGenerationService.ImageGenerationOutcome tooMany =
                singleSourceService.generate(
                        "编辑", "portrait", PNG_DATA_URL, Collections.singletonList(PNG_DATA_URL));

        assertThat(tooMany.isSuccess()).isFalse();
        assertThat(tooMany.getError()).contains("at most 1 source image");
        assertThat(singleSource.calls).hasValue(0);
    }

    @Test
    void visionAnalyzeUsesResolvedVisionProviderAndAttachmentBoundary() {
        AppConfig config = config("vision-success");
        RecordingVisionGateway gateway = new RecordingVisionGateway("图中是一枚红色印章");
        VisionAnalysisService service = visionService(config, gateway);

        VisionAnalysisService.VisionAnalysisOutcome outcome =
                service.analyze(PNG_DATA_URL, "图里有什么？");

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getAnswer()).isEqualTo("图中是一枚红色印章");
        assertThat(outcome.getProvider()).isEqualTo("vision-test");
        assertThat(outcome.getModel()).isEqualTo("vision-model");
        assertThat(gateway.question).isEqualTo("图里有什么？");
        assertThat(gateway.runContext.getUserAttachments()).hasSize(1);
        assertThat(new File(gateway.runContext.getUserAttachments().get(0).getLocalPath()))
                .isFile();
    }

    @Test
    void visionAnalyzeToolHasOnlyRequiredImageUrlAndQuestion() {
        AppConfig config = config("vision-schema");
        VisionAnalyzeTools tools =
                new VisionAnalyzeTools(visionService(config, new RecordingVisionGateway("answer")));

        FunctionTool tool = functionTool(new MethodToolProvider(tools), "vision_analyze");
        Map<String, Object> schema = ONode.deserialize(tool.inputSchema(), Map.class);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties.keySet()).containsExactlyInAnyOrder("image_url", "question");
        assertThat((List<Object>) schema.get("required"))
                .containsExactlyInAnyOrder("image_url", "question");
        assertThat(AgentRuntimePolicy.knownToolNames()).contains("vision_analyze");
        assertThat(AgentRuntimePolicy.expandToolSelectors(Collections.singletonList("vision")))
                .contains("vision_analyze", "image_generate");
    }

    @Test
    void visionAnalyzeBlocksPrivateRemoteImageBeforeModelCall() {
        AppConfig config = config("vision-private-url");
        RecordingVisionGateway gateway = new RecordingVisionGateway("must-not-run");
        VisionAnalysisService service = visionService(config, gateway);

        VisionAnalysisService.VisionAnalysisOutcome outcome =
                service.analyze("http://127.0.0.1/private.png", "识别内容");

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getError()).contains("blocked");
        assertThat(gateway.calls).hasValue(0);
    }

    @Test
    void toolRegistryExposesVisionAnalyzeToolObject() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        assertThat(env.toolRegistry.listToolNames()).contains("vision_analyze");
        List<Object> tools = env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1");
        assertThat(tools).anyMatch(tool -> tool instanceof VisionAnalyzeTools);
        assertThat(exposedToolNames(tools)).contains("vision_analyze");
    }

    /** 收集注册表对象最终暴露给模型的函数名。 */
    private List<String> exposedToolNames(List<Object> tools) {
        List<String> names = new java.util.ArrayList<String>();
        for (Object tool : tools) {
            if (tool instanceof ToolProvider) {
                for (FunctionTool function : ((ToolProvider) tool).getTools()) {
                    names.add(function.name());
                }
            } else {
                for (FunctionTool function : new MethodToolProvider(tool).getTools()) {
                    names.add(function.name());
                }
            }
        }
        return names;
    }

    private ImageGenerationService imageService(RecordingImageProvider provider, String name) {
        AppConfig config = config(name);
        return new ImageGenerationService(
                config,
                new AttachmentCacheService(config),
                Collections.<ImageGenProvider>singletonList(provider),
                new SecurityPolicyService(config));
    }

    private VisionAnalysisService visionService(AppConfig config, RecordingVisionGateway gateway) {
        return new VisionAnalysisService(
                config,
                new AttachmentCacheService(config),
                new SecurityPolicyService(config),
                new LlmProviderService(config),
                () -> gateway);
    }

    private AppConfig config(String name) {
        AppConfig config = new AppConfig();
        String home = tempDir.resolve(name).toString();
        config.getRuntime().setHome(home);
        config.getRuntime().setCacheDir(tempDir.resolve(name).resolve("cache").toString());
        config.getModel().setProviderKey("vision-test");
        config.getModel().setDefault("vision-model");
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Vision test");
        provider.setDialect("openai");
        provider.setBaseUrl("https://example.com/v1");
        provider.setApiKey("test-key");
        provider.setDefaultModel("vision-model");
        provider.setSupportsVision(Boolean.TRUE);
        config.getProviders().put("vision-test", provider);
        return config;
    }

    private FunctionTool functionTool(MethodToolProvider provider, String name) {
        return functionTool(provider.getTools(), name);
    }

    private FunctionTool functionTool(java.util.Collection<FunctionTool> tools, String name) {
        return tools.stream()
                .filter(tool -> name.equals(tool.name()))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static class RecordingImageProvider implements ImageGenProvider {
        private final boolean supportsImageInput;
        private final int maxSourceImages;
        private final AtomicInteger calls = new AtomicInteger();
        private String aspectRatio;
        private String imageUrl;
        private List<String> referenceImageUrls;

        private RecordingImageProvider(boolean supportsImageInput, int maxSourceImages) {
            this.supportsImageInput = supportsImageInput;
            this.maxSourceImages = maxSourceImages;
        }

        @Override
        public String name() {
            return "recording-image";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supportsImageInput() {
            return supportsImageInput;
        }

        @Override
        public int maxSourceImages() {
            return maxSourceImages;
        }

        @Override
        public ImageGenResult generate(
                String prompt,
                String aspectRatio,
                String imageUrl,
                List<String> referenceImageUrls) {
            calls.incrementAndGet();
            this.aspectRatio = aspectRatio;
            this.imageUrl = imageUrl;
            this.referenceImageUrls = referenceImageUrls;
            return ImageGenResult.ok(PNG_DATA_URL);
        }
    }

    private static class RecordingVisionGateway implements LlmGateway {
        private final String answer;
        private final AtomicInteger calls = new AtomicInteger();
        private AgentRunContext runContext;
        private String question;

        private RecordingVisionGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new AssertionError("vision_analyze must call executeOnce with attachments");
        }

        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext) {
            calls.incrementAndGet();
            this.runContext = runContext;
            this.question = userMessage;
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(answer));
            result.setRawResponse(answer);
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setInputTokens(10L);
            result.setOutputTokens(5L);
            result.setTotalTokens(15L);
            return result;
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new AssertionError("vision_analyze must not resume a session");
        }
    }
}
