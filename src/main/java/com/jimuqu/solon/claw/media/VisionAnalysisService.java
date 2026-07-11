package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 独立图片理解服务，通过现有 Solon AI 网关发送受控图片附件。 */
public class VisionAnalysisService {
    /** 图片理解专用系统提示，限制模型只回答传入问题。 */
    private static final String SYSTEM_PROMPT = "你是图片理解助手。只根据提供的图片回答问题；无法确认时明确说明。";

    /** 应用配置，用于解析当前主模型和视觉能力。 */
    private final AppConfig appConfig;

    /** 图片输入边界服务。 */
    private final ImageInputService imageInputService;

    /** 当前有效模型 Provider 解析服务。 */
    private final LlmProviderService llmProviderService;

    /** 模型能力元数据服务。 */
    private final ModelMetadataService modelMetadataService;

    /** 延迟获取 LLM 网关，避免工具注册阶段形成 Bean 初始化环。 */
    private final Supplier<LlmGateway> llmGatewaySupplier;

    /**
     * 创建图片理解服务。
     *
     * @param appConfig 应用配置。
     * @param attachmentCacheService 附件缓存服务。
     * @param securityPolicyService URL 安全策略。
     * @param llmProviderService 模型 Provider 解析服务。
     * @param llmGatewaySupplier LLM 网关供应器。
     */
    public VisionAnalysisService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService,
            LlmProviderService llmProviderService,
            Supplier<LlmGateway> llmGatewaySupplier) {
        this.appConfig = appConfig;
        this.imageInputService =
                new ImageInputService(appConfig, attachmentCacheService, securityPolicyService);
        this.llmProviderService =
                llmProviderService == null ? new LlmProviderService(appConfig) : llmProviderService;
        this.modelMetadataService = new ModelMetadataService(appConfig);
        this.llmGatewaySupplier = llmGatewaySupplier;
    }

    /**
     * 使用当前已配置的视觉模型分析图片。
     *
     * @param imageUrl 图片 URL、data URL、media:// 引用或缓存内路径。
     * @param question 针对图片的问题。
     * @return 图片理解结果与模型用量。
     */
    public VisionAnalysisOutcome analyze(String imageUrl, String question) {
        if (StrUtil.isBlank(imageUrl)) {
            return VisionAnalysisOutcome.fail("image_url is required");
        }
        if (StrUtil.isBlank(question)) {
            return VisionAnalysisOutcome.fail("question is required");
        }
        try {
            ImageInputService.ResolvedImage image = imageInputService.resolve(imageUrl);
            LlmProviderService.ResolvedProvider provider =
                    llmProviderService.resolveEffectiveProvider(null);
            if (!supportsVision(provider)) {
                return VisionAnalysisOutcome.fail("Configured model does not support vision input");
            }
            LlmGateway gateway = llmGatewaySupplier == null ? null : llmGatewaySupplier.get();
            if (gateway == null) {
                return VisionAnalysisOutcome.fail("Vision model gateway is unavailable");
            }

            String id = IdSupport.newId();
            SessionRecord session = new SessionRecord();
            session.setSessionId("vision-" + id);
            session.setSourceKey("MEMORY:vision:" + id);
            session.setNdjson("");
            AgentRunContext runContext =
                    new AgentRunContext(
                            null, "vision-" + id, session.getSessionId(), session.getSourceKey());
            runContext.setRunKind("vision");
            runContext.setUserAttachments(Collections.singletonList(image.getAttachment()));

            AppConfig.LlmConfig resolved = llmConfig(provider);
            LlmResult result =
                    gateway.executeOnce(
                            session,
                            SYSTEM_PROMPT,
                            question.trim(),
                            Collections.emptyList(),
                            ConversationFeedbackSink.noop(),
                            ConversationEventSink.noop(),
                            false,
                            resolved,
                            runContext);
            String answer = answer(result);
            if (StrUtil.isBlank(answer)) {
                return VisionAnalysisOutcome.fail("Vision model returned no answer");
            }
            return VisionAnalysisOutcome.ok(
                    answer,
                    StrUtil.blankToDefault(result.getProvider(), provider.getProviderKey()),
                    StrUtil.blankToDefault(result.getModel(), provider.getModel()),
                    usage(result));
        } catch (Exception e) {
            return VisionAnalysisOutcome.fail(
                    StrUtil.blankToDefault(
                            SecretRedactor.redact(e.getMessage(), 400), "Vision analysis failed"));
        }
    }

    /** 判断当前有效模型是否声明视觉输入能力。 */
    private boolean supportsVision(LlmProviderService.ResolvedProvider resolved) {
        if (resolved == null) {
            return false;
        }
        AppConfig.ProviderConfig configured =
                appConfig.getProviders().get(resolved.getProviderKey());
        AppConfig.ProviderConfig effective = new AppConfig.ProviderConfig();
        if (configured != null) {
            effective.setSupportsVision(configured.getSupportsVision());
        }
        effective.setName(resolved.getLabel());
        effective.setBaseUrl(resolved.getBaseUrl());
        effective.setApiKey(resolved.getApiKey());
        effective.setDefaultModel(resolved.getModel());
        effective.setDialect(resolved.getDialect());
        return modelMetadataService
                .resolve(resolved.getProviderKey(), effective)
                .isSupportsVision();
    }

    /** 把有效 Provider 快照转换为现有 LLM 网关执行参数。 */
    private AppConfig.LlmConfig llmConfig(LlmProviderService.ResolvedProvider provider) {
        AppConfig.LlmConfig source = appConfig.getLlm();
        AppConfig.LlmConfig result = new AppConfig.LlmConfig();
        result.setProvider(provider.getProviderKey());
        result.setDialect(provider.getDialect());
        result.setApiUrl(provider.getApiUrl());
        result.setApiKey(provider.getApiKey());
        result.setModel(provider.getModel());
        result.setStream(false);
        if (source != null) {
            result.setReasoningEffort(source.getReasoningEffort());
            result.setTemperature(source.getTemperature());
            result.setMaxTokens(source.getMaxTokens());
            result.setContextWindowTokens(source.getContextWindowTokens());
            result.setContextFallbackTokens(source.getContextFallbackTokens());
            result.setModelsDevRefreshEnabled(source.isModelsDevRefreshEnabled());
            result.setPromptCache(source.getPromptCache());
        }
        return result;
    }

    /** 从 Solon AI 结果提取最终可见回答。 */
    private String answer(LlmResult result) {
        if (result == null) {
            return "";
        }
        String content =
                result.getAssistantMessage() == null
                        ? ""
                        : result.getAssistantMessage().getResultContent();
        return StrUtil.blankToDefault(content, result.getRawResponse()).trim();
    }

    /** 汇总独立图片理解调用的 token 用量。 */
    private Map<String, Object> usage(LlmResult result) {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        if (result == null) {
            return usage;
        }
        usage.put("inputTokens", Long.valueOf(result.getInputTokens()));
        usage.put("outputTokens", Long.valueOf(result.getOutputTokens()));
        usage.put("totalTokens", Long.valueOf(result.getTotalTokens()));
        return usage;
    }

    /** 图片理解结果。 */
    public static final class VisionAnalysisOutcome {
        /** 是否成功。 */
        private final boolean success;

        /** 模型回答。 */
        private final String answer;

        /** 实际 Provider。 */
        private final String provider;

        /** 实际模型。 */
        private final String model;

        /** token 用量。 */
        private final Map<String, Object> usage;

        /** 安全错误文本。 */
        private final String error;

        private VisionAnalysisOutcome(
                boolean success,
                String answer,
                String provider,
                String model,
                Map<String, Object> usage,
                String error) {
            this.success = success;
            this.answer = answer;
            this.provider = provider;
            this.model = model;
            this.usage = usage;
            this.error = error;
        }

        /** 构造成功结果。 */
        public static VisionAnalysisOutcome ok(
                String answer, String provider, String model, Map<String, Object> usage) {
            return new VisionAnalysisOutcome(true, answer, provider, model, usage, null);
        }

        /** 构造失败结果。 */
        public static VisionAnalysisOutcome fail(String error) {
            return new VisionAnalysisOutcome(
                    false, null, null, null, Collections.<String, Object>emptyMap(), error);
        }

        /**
         * @return 是否成功。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * @return 模型回答。
         */
        public String getAnswer() {
            return answer;
        }

        /**
         * @return 实际 Provider。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * @return 实际模型。
         */
        public String getModel() {
            return model;
        }

        /**
         * @return token 用量。
         */
        public Map<String, Object> getUsage() {
            return usage;
        }

        /**
         * @return 安全错误文本。
         */
        public String getError() {
            return error;
        }
    }
}
