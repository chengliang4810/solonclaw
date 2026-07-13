package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/** 模型能力元数据解析服务。 */
public class ModelMetadataService {
    /** OllamaTAG正则的统一常量值。 */
    private static final Pattern OLLAMA_TAG_PATTERN =
            Pattern.compile(
                    "^(\\d+\\.?\\d*b|latest|stable|q\\d|fp?\\d|instruct|chat|coder|vision|text)");

    /** 注入应用配置，用于模型元数据。 */
    private final AppConfig appConfig;

    /** 模型上下文长度在线目录服务，用于按模型自动识别上下文窗口，可为 null（降级到纯硬编码）。 */
    private final ModelContextCatalogService catalogService;

    /** 模型上下文长度持久化缓存，用于存储在线探测结果，可为 null。 */
    private final ModelContextCacheStore cacheStore;

    /**
     * 创建模型元数据服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public ModelMetadataService(AppConfig appConfig) {
        this(appConfig, null, null);
    }

    /**
     * 创建模型元数据服务实例，注入在线目录和持久化缓存。
     *
     * @param appConfig 应用运行配置。
     * @param catalogService 模型上下文在线目录服务，null 时降级到纯硬编码。
     * @param cacheStore 模型上下文持久化缓存，null 时跳过缓存读写。
     */
    public ModelMetadataService(
            AppConfig appConfig,
            ModelContextCatalogService catalogService,
            ModelContextCacheStore cacheStore) {
        this.appConfig = appConfig;
        this.catalogService = catalogService;
        this.cacheStore = cacheStore;
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param providerKey 提供方键标识或键值。
     * @param provider 模型或能力提供方。
     * @return 返回resolve结果。
     */
    public ModelMetadata resolve(String providerKey, AppConfig.ProviderConfig provider) {
        String dialect =
                LlmProviderSupport.normalizeDialect(
                        provider == null ? null : provider.getDialect());
        String model =
                StrUtil.nullToEmpty(provider == null ? null : provider.getDefaultModel()).trim();
        String normalizedModel = normalizedModelName(model);
        ModelMetadata metadata = new ModelMetadata();
        metadata.setProvider(StrUtil.nullToEmpty(providerKey).trim());
        metadata.setModel(model);
        metadata.setDialect(dialect);
        metadata.setAliases(aliases(normalizedModel));
        String baseUrl = provider == null ? null : provider.getBaseUrl();
        metadata.setContextWindow(
                resolveContextWindow(providerKey, dialect, baseUrl, normalizedModel));
        metadata.setMaxOutput(appConfig.getLlm().getMaxTokens());
        metadata.setApiUrl(resolveApiUrl(provider, dialect));
        metadata.setModelListUrl(resolveModelListUrl(providerKey, provider, dialect));
        metadata.setSupportsTools(resolveSupportsTools(dialect, provider));
        boolean supportsVision = resolveSupportsVision(dialect, normalizedModel, provider);
        boolean supportsAudio = resolveSupportsAudio(dialect, normalizedModel, provider);
        boolean supportsPdf = resolveSupportsPdf(dialect, normalizedModel, provider);
        boolean supportsAttachment =
                resolveSupportsAttachment(
                        dialect,
                        normalizedModel,
                        supportsVision,
                        supportsAudio,
                        supportsPdf,
                        provider);
        metadata.setSupportsVision(supportsVision);
        metadata.setSupportsAudio(supportsAudio);
        metadata.setSupportsAttachment(supportsAttachment);
        metadata.setSupportsPdf(supportsPdf);
        metadata.setSupportsMultimodal(
                supportsVision || supportsAudio || supportsPdf || supportsAttachment);
        metadata.setInputModalities(
                resolveInputModalities(
                        dialect, supportsVision, supportsAudio, supportsPdf, supportsAttachment));
        metadata.setOutputModalities(
                resolveOutputModalities(dialect, normalizedModel, supportsAudio));
        metadata.setSupportsReasoning(resolveSupportsReasoning(dialect, normalizedModel, provider));
        metadata.setSupportsStructuredOutput(
                resolveSupportsStructuredOutput(dialect, normalizedModel, provider));
        metadata.setSupportsOpenWeights(
                resolveSupportsOpenWeights(dialect, normalizedModel, provider));
        metadata.setSupportsInterleaved(
                resolveSupportsInterleaved(dialect, normalizedModel, provider));
        metadata.setSupportsPromptCache(resolveSupportsPromptCache(dialect, provider));
        metadata.setSupportsStreaming(resolveCapability(provider, "streaming", true));
        metadata.setSource(resolveSource(provider, normalizedModel));
        metadata.setProvenance(resolveProvenance(provider, normalizedModel));
        metadata.setDefaultModel(
                StrUtil.equals(providerKey, appConfig.getModel().getProviderKey()));
        metadata.setSupported(LlmProviderSupport.isSupportedDialect(dialect));
        return metadata;
    }

    /**
     * 执行normalized模型名称相关逻辑。
     *
     * @param model 模型名称。
     * @return 返回normalized模型名称结果。
     */
    private String normalizedModelName(String model) {
        String value = StrUtil.nullToEmpty(model).trim();
        int slash = value.indexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            return value.substring(slash + 1).trim();
        }
        int colon = value.indexOf(':');
        if (colon > 0
                && colon + 1 < value.length()
                && isProviderPrefix(value.substring(0, colon))) {
            String suffix = value.substring(colon + 1).trim();
            if (looksLikeOllamaTag(suffix)) {
                return value;
            }
            return suffix;
        }
        return value;
    }

    /**
     * 判断是否提供方Prefix。
     *
     * @param prefix prefix 参数。
     * @return 如果提供方Prefix满足条件则返回 true，否则返回 false。
     */
    private boolean isProviderPrefix(String prefix) {
        String value = StrUtil.nullToEmpty(prefix).trim().toLowerCase();
        return "openai".equals(value)
                || "openai-responses".equals(value)
                || "anthropic".equals(value)
                || "gemini".equals(value)
                || "google".equals(value);
    }

    /**
     * 判断是否具有OllamaTag特征。
     *
     * @param suffix suffix 参数。
     * @return 返回looks Like Ollama Tag结果。
     */
    private boolean looksLikeOllamaTag(String suffix) {
        return OLLAMA_TAG_PATTERN.matcher(StrUtil.nullToEmpty(suffix).trim().toLowerCase()).find();
    }

    /**
     * 执行aliases相关逻辑。
     *
     * @param model 模型名称。
     * @return 返回aliases结果。
     */
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

    /**
     * 按模型解析上下文窗口大小，遵循 5 步优先级链（对齐外部对标仓库）：
     *
     * <ol>
     *   <li>用户配置覆盖（contextWindowTokens &gt; 0 时作为全局强制覆盖）
     *   <li>持久化缓存（model@baseUrl）
     *   <li>在线 API（models.dev + OpenRouter）
     *   <li>按家族硬编码目录
     *   <li>兜底（contextFallbackTokens，默认 256K）
     * </ol>
     *
     * @param providerKey 提供方配置键。
     * @param dialect 协议方言。
     * @param baseUrl 提供方 baseUrl。
     * @param model 已规范化的模型名。
     * @return 返回解析后的上下文窗口 token 数。
     */
    private int resolveContextWindow(
            String providerKey, String dialect, String baseUrl, String model) {
        return resolveContextWindow(providerKey, dialect, baseUrl, model, true);
    }

    /** 按候选类型执行完整解析链，仅 fallback 跳过主模型显式覆盖。 */
    private int resolveContextWindow(
            String providerKey,
            String dialect,
            String baseUrl,
            String model,
            boolean allowConfiguredOverride) {
        // Step 1: 用户配置覆盖
        int configured = appConfig.getLlm().getContextWindowTokens();
        if (allowConfiguredOverride && configured > 0) {
            return configured;
        }

        String normalizedModel = StrUtil.nullToEmpty(model).trim();
        String normalizedBaseUrl = StrUtil.nullToEmpty(baseUrl).trim();

        // Step 2: 持久化缓存
        if (cacheStore != null && StrUtil.isNotBlank(normalizedModel)) {
            OptionalInt cached = cacheStore.get(normalizedModel, normalizedBaseUrl);
            if (cached.isPresent()) {
                return cached.getAsInt();
            }
        }

        // Step 3: 在线 API（models.dev + OpenRouter）
        if (catalogService != null && StrUtil.isNotBlank(normalizedModel)) {
            OptionalInt online =
                    catalogService.getContextLength(
                            dialect, providerKey, normalizedBaseUrl, normalizedModel);
            if (online.isPresent()) {
                int length = online.getAsInt();
                if (cacheStore != null
                        && StrUtil.isNotBlank(normalizedBaseUrl)
                        && length >= 64000) {
                    cacheStore.save(normalizedModel, normalizedBaseUrl, length);
                }
                return length;
            }
        }

        // Step 4: 按家族硬编码目录
        int hardcoded = resolveHardcodedContextWindow(dialect, normalizedModel);
        if (hardcoded > 0) {
            return hardcoded;
        }

        // Step 5: 兜底
        int fallback = appConfig.getLlm().getContextFallbackTokens();
        return fallback > 0 ? fallback : 256000;
    }

    /**
     * 按模型族硬编码目录解析上下文窗口，最长键优先子串匹配。
     *
     * @param dialect 协议方言。
     * @param model 已规范化的模型名。
     * @return 返回匹配到的上下文窗口，未匹配返回 0。
     */
    private int resolveHardcodedContextWindow(String dialect, String model) {
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        if (lower.length() == 0) {
            return 0;
        }
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
        return 0;
    }

    /**
     * 为运行时（压缩/预算计算）解析当前会话模型的上下文窗口。
     *
     * @param providerKey 当前提供方配置键。
     * @param dialect 当前协议方言。
     * @param baseUrl 当前提供方 baseUrl。
     * @param model 当前模型名。
     * @return 返回该模型的上下文窗口 token 数。
     */
    public int resolveContextWindowForRuntime(
            String providerKey, String dialect, String baseUrl, String model) {
        return resolveContextWindow(providerKey, dialect, baseUrl, normalizedModelName(model));
    }

    /**
     * 为指定候选解析上下文窗口，并控制是否允许主模型显式覆盖。
     *
     * @param providerKey 当前提供方配置键。
     * @param dialect 当前协议方言。
     * @param baseUrl 当前提供方 baseUrl。
     * @param model 当前模型名。
     * @param allowConfiguredOverride 是否允许使用主模型显式窗口覆盖。
     * @return 返回该候选模型的上下文窗口 token 数。
     */
    public int resolveContextWindowForRuntime(
            String providerKey,
            String dialect,
            String baseUrl,
            String model,
            boolean allowConfiguredOverride) {
        return resolveContextWindow(
                providerKey,
                dialect,
                baseUrl,
                normalizedModelName(model),
                allowConfiguredOverride);
    }

    /**
     * 判断是否匹配Any。
     *
     * @param value 待规范化或校验的原始值。
     * @param needles needles 参数。
     * @return 返回matches Any结果。
     */
    private boolean matchesAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析Supports工具。
     *
     * @param dialect dialect 参数。
     * @return 返回解析后的Supports工具。
     */
    private boolean resolveSupportsTools(String dialect, AppConfig.ProviderConfig provider) {
        return resolveCapability(
                provider, "tool_calling", LlmProviderSupport.isSupportedDialect(dialect));
    }

    /**
     * 解析Supports Vision。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @return 返回解析后的Supports Vision。
     */
    private boolean resolveSupportsVision(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "vision");
        if (configured == null && provider != null) {
            configured = provider.getSupportsVision();
        }
        if (configured != null) {
            return configured.booleanValue();
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

    /**
     * 解析Supports Audio。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @return 返回解析后的Supports Audio。
     */
    private boolean resolveSupportsAudio(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "audio");
        if (configured != null) {
            return configured.booleanValue();
        }
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

    /**
     * 解析Supports Pdf。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @return 返回解析后的Supports Pdf。
     */
    private boolean resolveSupportsPdf(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "pdf");
        if (configured != null) {
            return configured.booleanValue();
        }
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_GEMINI.equals(dialect)
                || LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                || LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                || lower.contains("gpt-4o")
                || lower.contains("gpt-4.1")
                || lower.contains("gpt-5")
                || lower.contains("qwen-vl")
                || lower.contains("qwen2-vl")
                || lower.contains("qwen2.5-vl")
                || lower.contains("qwen3-vl");
    }

    /**
     * 解析Supports附件。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @param supportsVision supportsVision 参数。
     * @param supportsAudio supports音频参数。
     * @param supportsPdf supportsPdf 参数。
     * @return 返回解析后的Supports附件。
     */
    private boolean resolveSupportsAttachment(
            String dialect,
            String model,
            boolean supportsVision,
            boolean supportsAudio,
            boolean supportsPdf,
            AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "attachment");
        if (configured != null) {
            return configured.booleanValue();
        }
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return supportsVision
                || supportsAudio
                || supportsPdf
                || LlmConstants.PROVIDER_GEMINI.equals(dialect)
                || lower.contains("vision")
                || lower.contains("multimodal")
                || lower.contains("omni")
                || lower.contains("-vl")
                || lower.contains("_vl")
                || lower.contains("/vl");
    }

    /**
     * 解析输入Modalities。
     *
     * @param dialect dialect 参数。
     * @param supportsVision supportsVision 参数。
     * @param supportsAudio supports音频参数。
     * @param supportsPdf supportsPdf 参数。
     * @param supportsAttachment supports附件参数。
     * @return 返回解析后的输入Modalities。
     */
    private List<String> resolveInputModalities(
            String dialect,
            boolean supportsVision,
            boolean supportsAudio,
            boolean supportsPdf,
            boolean supportsAttachment) {
        Set<String> modalities = new LinkedHashSet<String>();
        modalities.add("text");
        if (supportsVision) {
            modalities.add("image");
        }
        if (supportsPdf) {
            modalities.add("pdf");
        }
        if (supportsAudio) {
            modalities.add("audio");
        }
        if (supportsAttachment && LlmConstants.PROVIDER_GEMINI.equals(dialect)) {
            modalities.add("file");
        }
        return new ArrayList<String>(modalities);
    }

    /**
     * 解析输出Modalities。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @param supportsAudio supports音频参数。
     * @return 返回解析后的输出Modalities。
     */
    private List<String> resolveOutputModalities(
            String dialect, String model, boolean supportsAudio) {
        Set<String> modalities = new LinkedHashSet<String>();
        modalities.add("text");
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        if (lower.contains("tts") || lower.contains("speech")) {
            modalities.add("audio");
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(dialect) && lower.contains("image")) {
            modalities.add("image");
        }
        return new ArrayList<String>(modalities);
    }

    /**
     * 解析Api URL。
     *
     * @param provider 模型或能力提供方。
     * @param dialect dialect 参数。
     * @return 返回解析后的Api URL。
     */
    private String resolveApiUrl(AppConfig.ProviderConfig provider, String dialect) {
        if (provider == null || StrUtil.isBlank(provider.getBaseUrl())) {
            return "";
        }
        return LlmProviderSupport.buildApiUrl(provider.getBaseUrl(), dialect);
    }

    /**
     * 解析模型List URL。
     *
     * @param providerKey 提供方键标识或键值。
     * @param provider 模型或能力提供方。
     * @param dialect dialect 参数。
     * @return 返回解析后的模型List URL。
     */
    private String resolveModelListUrl(
            String providerKey, AppConfig.ProviderConfig provider, String dialect) {
        if (provider == null || StrUtil.isBlank(provider.getBaseUrl())) {
            return "";
        }
        return LlmProviderSupport.buildModelListUrl(providerKey, provider.getBaseUrl(), dialect);
    }

    /**
     * 解析来源。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     * @return 返回解析后的来源。
     */
    private String resolveSource(AppConfig.ProviderConfig provider, String model) {
        if (provider != null
                && (StrUtil.isNotBlank(provider.getBaseUrl())
                        || hasConfiguredCapabilities(provider))) {
            return "provider_config";
        }
        if (StrUtil.isNotBlank(model)) {
            return "static_inference";
        }
        return "default";
    }

    /**
     * 解析来源追踪。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     * @return 返回解析后的来源追踪。
     */
    private String resolveProvenance(AppConfig.ProviderConfig provider, String model) {
        if (hasConfiguredCapabilities(provider) && StrUtil.isBlank(provider.getBaseUrl())) {
            return "provider_config:capabilities_with_static_fallback";
        }
        if (provider != null && StrUtil.isNotBlank(provider.getBaseUrl())) {
            return hasConfiguredCapabilities(provider)
                    ? "provider_config:base_url_and_capabilities_with_static_fallback"
                    : "provider_config:base_url";
        }
        if (StrUtil.isNotBlank(model)) {
            return "static_inference:model_family";
        }
        return "default:fallback";
    }

    /**
     * 解析Supports Reasoning。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @return 返回解析后的Supports Reasoning。
     */
    private boolean resolveSupportsReasoning(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "reasoning");
        if (configured != null) {
            return configured.booleanValue();
        }
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

    /**
     * 解析Supports Structured输出。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @return 返回解析后的Supports Structured输出。
     */
    private boolean resolveSupportsStructuredOutput(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "structured_output");
        if (configured != null) {
            return configured.booleanValue();
        }
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                || LlmConstants.PROVIDER_GEMINI.equals(dialect)
                || lower.contains("gpt-4o")
                || lower.contains("gpt-4.1")
                || lower.contains("gpt-5")
                || lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")
                || lower.contains("/o1")
                || lower.contains("/o3")
                || lower.contains("/o4")
                || lower.contains("qwen")
                || lower.contains("deepseek")
                || lower.contains("grok")
                || lower.contains("glm");
    }

    /**
     * 解析Supports Open Weights。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @return 返回解析后的Supports Open Weights。
     */
    private boolean resolveSupportsOpenWeights(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "open_weights");
        if (configured != null) {
            return configured.booleanValue();
        }
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_OLLAMA.equals(dialect)
                || lower.contains("llama")
                || lower.contains("qwen")
                || lower.contains("deepseek")
                || lower.contains("gemma")
                || lower.contains("mistral")
                || lower.contains("mixtral")
                || lower.contains("yi-")
                || lower.startsWith("yi")
                || lower.contains("glm")
                || lower.contains("phi")
                || lower.contains("minicpm");
    }

    /**
     * 解析Supports Interleaved。
     *
     * @param dialect dialect 参数。
     * @param model 模型名称。
     * @return 返回解析后的Supports Interleaved。
     */
    private boolean resolveSupportsInterleaved(
            String dialect, String model, AppConfig.ProviderConfig provider) {
        Boolean configured = configuredCapability(provider, "interleaved");
        if (configured != null) {
            return configured.booleanValue();
        }
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return false;
        }
        String lower = StrUtil.nullToEmpty(model).toLowerCase();
        return LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                && (lower.contains("claude-sonnet-4") || lower.contains("claude-opus-4"));
    }

    /**
     * 解析Supports提示词缓存。
     *
     * @param dialect dialect 参数。
     * @return 返回解析后的Supports提示词缓存。
     */
    private boolean resolveSupportsPromptCache(String dialect, AppConfig.ProviderConfig provider) {
        return resolveCapability(
                provider,
                "prompt_cache",
                LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)
                        || LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)
                        || LlmConstants.PROVIDER_GEMINI.equals(dialect));
    }

    /**
     * 读取显式 provider 能力；没有配置时使用可信协议或静态目录回退值。
     *
     * @param provider provider 配置。
     * @param key 能力键。
     * @param fallback 未配置时的回退值。
     * @return 返回最终能力值。
     */
    private boolean resolveCapability(
            AppConfig.ProviderConfig provider, String key, boolean fallback) {
        Boolean configured = configuredCapability(provider, key);
        return configured == null ? fallback : configured.booleanValue();
    }

    /**
     * 读取 provider 中显式声明的能力值。
     *
     * @param provider provider 配置。
     * @param key 能力键。
     * @return 返回显式能力值；未配置时返回 null。
     */
    private Boolean configuredCapability(AppConfig.ProviderConfig provider, String key) {
        if (provider == null || provider.getCapabilities() == null) {
            return null;
        }
        return provider.getCapabilities().get(key);
    }

    /**
     * 判断 provider 是否携带显式能力元数据。
     *
     * @param provider provider 配置。
     * @return 存在显式能力元数据时返回 true。
     */
    private boolean hasConfiguredCapabilities(AppConfig.ProviderConfig provider) {
        return provider != null
                && (provider.getSupportsVision() != null
                        || (provider.getCapabilities() != null
                                && !provider.getCapabilities().isEmpty()));
    }
}
