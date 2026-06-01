package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 模型能力元数据解析服务。 */
public class ModelMetadataService {
    private static final Pattern OLLAMA_TAG_PATTERN =
            Pattern.compile("^(\\d+\\.?\\d*b|latest|stable|q\\d|fp?\\d|instruct|chat|coder|vision|text)");

    private final AppConfig appConfig;

    public ModelMetadataService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public ModelMetadata resolve(String providerKey, AppConfig.ProviderConfig provider) {
        String dialect =
                LlmProviderSupport.normalizeDialect(provider == null ? null : provider.getDialect());
        String model = StrUtil.nullToEmpty(provider == null ? null : provider.getDefaultModel()).trim();
        String normalizedModel = normalizedModelName(model);
        ModelMetadata metadata = new ModelMetadata();
        metadata.setProvider(StrUtil.nullToEmpty(providerKey).trim());
        metadata.setModel(model);
        metadata.setDialect(dialect);
        metadata.setAliases(aliases(normalizedModel));
        metadata.setContextWindow(resolveContextWindow(dialect, normalizedModel));
        metadata.setMaxOutput(appConfig.getLlm().getMaxTokens());
        metadata.setApiUrl(resolveApiUrl(provider, dialect));
        metadata.setModelListUrl(resolveModelListUrl(provider, dialect));
        metadata.setSupportsTools(resolveSupportsTools(dialect));
        boolean supportsVision = resolveSupportsVision(dialect, normalizedModel, provider);
        boolean supportsAudio = resolveSupportsAudio(dialect, normalizedModel);
        boolean supportsAttachment = resolveSupportsAttachment(dialect, normalizedModel, supportsVision, supportsAudio);
        metadata.setSupportsVision(supportsVision);
        metadata.setSupportsAudio(supportsAudio);
        metadata.setSupportsAttachment(supportsAttachment);
        metadata.setSupportsMultimodal(supportsVision || supportsAudio || supportsAttachment);
        metadata.setSupportsReasoning(resolveSupportsReasoning(dialect, normalizedModel));
        metadata.setSupportsPromptCache(resolveSupportsPromptCache(dialect));
        metadata.setSupportsStreaming(true);
        metadata.setSource(resolveSource(provider, normalizedModel));
        metadata.setDefaultModel(StrUtil.equals(providerKey, appConfig.getModel().getProviderKey()));
        metadata.setSupported(LlmProviderSupport.isSupportedDialect(dialect));
        return metadata;
    }

    private String normalizedModelName(String model) {
        String value = StrUtil.nullToEmpty(model).trim();
        int slash = value.indexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            return value.substring(slash + 1).trim();
        }
        int colon = value.indexOf(':');
        if (colon > 0 && colon + 1 < value.length() && isProviderPrefix(value.substring(0, colon))) {
            String suffix = value.substring(colon + 1).trim();
            if (looksLikeOllamaTag(suffix)) {
                return value;
            }
            return suffix;
        }
        return value;
    }

    private boolean isProviderPrefix(String prefix) {
        String value = StrUtil.nullToEmpty(prefix).trim().toLowerCase();
        return "openai".equals(value)
                || "openai-responses".equals(value)
                || "anthropic".equals(value)
                || "gemini".equals(value)
                || "google".equals(value);
    }

    private boolean looksLikeOllamaTag(String suffix) {
        return OLLAMA_TAG_PATTERN.matcher(StrUtil.nullToEmpty(suffix).trim().toLowerCase()).find();
    }

    private List<String> aliases(String model) {
        List<String> aliases = new ArrayList<String>();
        String value = StrUtil.nullToEmpty(model).trim();
        String lower = value.toLowerCase();
        if (StrUtil.isBlank(value)) {
            return aliases;
        }
        if (lower.startsWith("gpt-")) {
            aliases.add("gpt");
        }
        if (lower.contains("codex")) {
            aliases.add("codex");
        }
        if (lower.startsWith("claude")) {
            aliases.add("claude");
            if (lower.contains("sonnet")) {
                aliases.add("sonnet");
            }
            if (lower.contains("opus")) {
                aliases.add("opus");
            }
            if (lower.contains("haiku")) {
                aliases.add("haiku");
            }
        }
        if (lower.startsWith("gemini")) {
            aliases.add("gemini");
        }
        if (lower.contains("qwen")) {
            aliases.add("qwen");
        }
        if (lower.contains("deepseek")) {
            aliases.add("deepseek");
        }
        return aliases;
    }

    private int resolveContextWindow(String dialect, String model) {
        int configured = appConfig.getLlm().getContextWindowTokens();
        if (configured > 0) {
            return configured;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            return 32768;
        }
        if (matchesAny(
                lower,
                "claude-opus-4-8",
                "claude-opus-4.8",
                "claude-opus-4-7",
                "claude-opus-4.7",
                "claude-opus-4-6",
                "claude-opus-4.6",
                "claude-sonnet-4-8",
                "claude-sonnet-4.8",
                "claude-sonnet-4-7",
                "claude-sonnet-4.7",
                "claude-sonnet-4-6",
                "claude-sonnet-4.6",
                "deepseek-chat",
                "deepseek-reasoner",
                "deepseek-v4")) {
            return 1000000;
        }
        if (matchesAny(lower, "gpt-5.5", "gpt-5.4")
                && !matchesAny(lower, "gpt-5.4-mini", "gpt-5.4-nano")) {
            return 1050000;
        }
        if (matchesAny(lower, "gemini", "qwen3.6-plus", "qwen3-coder-plus")) {
            return 1000000;
        }
        if (lower.contains("gpt-4.1")) {
            return 1047576;
        }
        if (lower.contains("grok-4-fast") || lower.contains("grok-4.20")) {
            return 2000000;
        }
        if (matchesAny(lower, "gpt-5", "gpt-5.4-mini", "gpt-5.4-nano")) {
            return 400000;
        }
        if (matchesAny(lower, "qwen3-coder", "kimi", "grok-4", "gemma-4", "gemma4")) {
            return 262144;
        }
        if (matchesAny(lower, "claude", "minimax", "glm")) {
            return 200000;
        }
        if (matchesAny(lower, "gpt-4", "qwen", "deepseek", "llama", "grok")) {
            return 131072;
        }
        return 64000;
    }

    private boolean matchesAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean resolveSupportsTools(String dialect) {
        return LlmProviderSupport.isSupportedDialect(dialect);
    }

    private boolean resolveSupportsVision(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        if (provider != null && provider.getSupportsVision() != null) {
            return provider.getSupportsVision().booleanValue();
        }
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_GEMINI.equals(dialect)
                || lower.contains("vision")
                || lower.contains("-vl")
                || lower.contains("_vl")
                || lower.contains("/vl")
                || lower.contains("omni")
                || lower.contains("gpt-4o")
                || lower.contains("gpt-5")
                || lower.contains("qwen-vl")
                || lower.contains("qwen2-vl")
                || lower.contains("qwen2.5-vl")
                || lower.contains("qwen3-vl")
                || lower.contains("claude-3")
                || lower.contains("claude-sonnet-4")
                || lower.contains("claude-opus-4")
                || lower.contains("grok-2-vision");
    }

    private boolean resolveSupportsAudio(String dialect, String model) {
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_GEMINI.equals(dialect)
                || lower.contains("audio")
                || lower.contains("tts")
                || lower.contains("transcribe")
                || lower.contains("realtime")
                || lower.contains("omni")
                || lower.contains("gpt-4o-audio")
                || lower.contains("gpt-4o-transcribe")
                || lower.contains("gpt-4o-mini-transcribe");
    }

    private boolean resolveSupportsAttachment(
            String dialect, String model, boolean supportsVision, boolean supportsAudio) {
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return supportsVision
                || supportsAudio
                || LlmConstants.PROVIDER_GEMINI.equals(dialect)
                || lower.contains("vision")
                || lower.contains("multimodal")
                || lower.contains("omni")
                || lower.contains("-vl")
                || lower.contains("_vl")
                || lower.contains("/vl");
    }

    private String resolveApiUrl(AppConfig.ProviderConfig provider, String dialect) {
        if (provider == null || StrUtil.isBlank(provider.getBaseUrl())) {
            return "";
        }
        return LlmProviderSupport.buildApiUrl(provider.getBaseUrl(), dialect);
    }

    private String resolveModelListUrl(AppConfig.ProviderConfig provider, String dialect) {
        if (provider == null || StrUtil.isBlank(provider.getBaseUrl())) {
            return "";
        }
        return LlmProviderSupport.buildModelListUrl(provider.getBaseUrl(), dialect);
    }

    private String resolveSource(AppConfig.ProviderConfig provider, String model) {
        if (provider != null && StrUtil.isNotBlank(provider.getBaseUrl())) {
            return "provider_config";
        }
        if (StrUtil.isNotBlank(model)) {
            return "static_inference";
        }
        return "default";
    }

    private boolean resolveSupportsReasoning(String dialect, String model) {
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                || LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                || lower.contains("reason")
                || lower.contains("thinking")
                || lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")
                || lower.contains("/o1")
                || lower.contains("/o3")
                || lower.contains("/o4")
                || lower.contains("grok-3-mini")
                || lower.contains("grok-4.20-multi-agent")
                || lower.contains("grok-4.3");
    }

    private boolean resolveSupportsPromptCache(String dialect) {
        return LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                || LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                || LlmConstants.PROVIDER_GEMINI.equals(dialect);
    }
}
