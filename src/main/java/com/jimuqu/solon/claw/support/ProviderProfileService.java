package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 聚合模型提供方运行画像，供控制台和运行时诊断读取。 */
public class ProviderProfileService {
    /** 记录 Provider 解析降级的低敏诊断日志，不输出密钥或完整地址。 */
    private static final Logger log = LoggerFactory.getLogger(ProviderProfileService.class);

    /** 应用运行配置。 */
    private final AppConfig appConfig;

    /** Provider解析服务。 */
    private final LlmProviderService llmProviderService;

    /** 模型元数据服务。 */
    private final ModelMetadataService modelMetadataService;

    /** 运行时统一价格目录。 */
    private final PriceCatalog priceCatalog;

    /**
     * 创建Provider画像服务实例。
     *
     * @param appConfig 应用运行配置。
     * @param llmProviderService provider解析服务。
     */
    public ProviderProfileService(AppConfig appConfig, LlmProviderService llmProviderService) {
        this(appConfig, llmProviderService, null);
    }

    /**
     * 创建复用运行时价格目录的 Provider 画像服务。
     *
     * @param appConfig 应用运行配置。
     * @param llmProviderService Provider 解析服务。
     * @param priceCatalog 运行时统一价格目录。
     */
    public ProviderProfileService(
            AppConfig appConfig, LlmProviderService llmProviderService, PriceCatalog priceCatalog) {
        this.appConfig = appConfig == null ? new AppConfig() : appConfig;
        this.llmProviderService =
                llmProviderService == null
                        ? new LlmProviderService(this.appConfig)
                        : llmProviderService;
        this.modelMetadataService = new ModelMetadataService(this.appConfig);
        this.priceCatalog =
                priceCatalog == null ? PriceCatalog.forConfig(this.appConfig) : priceCatalog;
    }

    /**
     * 列出Provider画像。
     *
     * @return 返回画像结果。
     */
    public Map<String, Object> profiles() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> profiles = listProfiles();
        result.put("profiles", profiles);
        result.put("defaultProviderKey", appConfig.getModel().getProviderKey());
        result.put("defaultModel", appConfig.getModel().getDefault());
        result.put("fallbackProviders", fallbackProviders());
        result.put("profileCount", Integer.valueOf(profiles.size()));
        return result;
    }

    /**
     * 列出Provider画像集合。
     *
     * @return 返回画像集合。
     */
    public List<Map<String, Object>> listProfiles() {
        if (appConfig.getProviders() == null || appConfig.getProviders().isEmpty()) {
            return Collections.emptyList();
        }
        PriceCatalog currentPriceCatalog = priceCatalog.configuredFor(appConfig);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                appConfig.getProviders().entrySet()) {
            result.add(profile(entry.getKey(), entry.getValue(), currentPriceCatalog));
        }
        return result;
    }

    /**
     * 生成单个Provider画像。
     *
     * @param providerKey provider键。
     * @param provider provider配置。
     * @param priceCatalog 价格目录。
     * @return 返回画像。
     */
    private Map<String, Object> profile(
            String providerKey, AppConfig.ProviderConfig provider, PriceCatalog priceCatalog) {
        LlmProviderService.ResolvedProvider resolved = resolveProvider(providerKey, provider);
        AppConfig.ProviderConfig effective = effectiveProvider(provider, resolved);
        ModelMetadata metadata = modelMetadataService.resolve(providerKey, effective);
        ModelPrice price = priceCatalog.find(providerKey, effective.getDefaultModel());

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("provider", providerKey);
        item.put("label", StrUtil.blankToDefault(resolved.getLabel(), providerKey));
        item.put("dialect", resolved.getDialect());
        item.put("base_url", SecretRedactor.maskUrl(resolved.getBaseUrl()));
        item.put("api_url", SecretRedactor.maskUrl(resolved.getApiUrl()));
        item.put("authentication", authentication(resolved));
        item.put("catalog", catalog(metadata));
        item.put("capabilities", capabilities(metadata));
        item.put("parameters", parameters(metadata));
        item.put("routing", routing(providerKey, resolved));
        item.put("display", display(providerKey, provider));
        item.put("pricing", price == null ? unknownPricing() : pricing(price));
        return item;
    }

    /**
     * 解析Provider；失败时回落到静态配置。
     *
     * @param providerKey provider键。
     * @param provider provider配置。
     * @return 返回解析结果。
     */
    private LlmProviderService.ResolvedProvider resolveProvider(
            String providerKey, AppConfig.ProviderConfig provider) {
        try {
            return llmProviderService.resolveProvider(providerKey, null);
        } catch (Exception e) {
            log.debug(
                    "Provider画像解析失败，回落到静态配置 provider={} error={}",
                    SecretRedactor.redact(providerKey, 120),
                    e.getClass().getSimpleName());
            LlmProviderService.ResolvedProvider resolved =
                    new LlmProviderService.ResolvedProvider();
            resolved.setProviderKey(providerKey);
            resolved.setLabel(provider == null ? providerKey : provider.getName());
            resolved.setDialect(provider == null ? "" : provider.getDialect());
            resolved.setBaseUrl(provider == null ? "" : provider.getBaseUrl());
            resolved.setApiUrl("");
            resolved.setApiKey(provider == null ? "" : provider.getApiKey());
            resolved.setModel(provider == null ? "" : provider.getDefaultModel());
            return resolved;
        }
    }

    /**
     * 构造元数据解析需要的Provider快照。
     *
     * @param provider provider配置。
     * @param resolved 已解析provider。
     * @return 返回Provider配置快照。
     */
    private AppConfig.ProviderConfig effectiveProvider(
            AppConfig.ProviderConfig provider, LlmProviderService.ResolvedProvider resolved) {
        AppConfig.ProviderConfig effective = new AppConfig.ProviderConfig();
        if (provider != null) {
            effective.setName(provider.getName());
            effective.setSupportsVision(provider.getSupportsVision());
            effective.setCapabilities(
                    provider.getCapabilities() == null
                            ? new LinkedHashMap<String, Boolean>()
                            : new LinkedHashMap<String, Boolean>(provider.getCapabilities()));
            effective.setGroupId(provider.getGroupId());
            effective.setGroupLabel(provider.getGroupLabel());
            effective.setGroupDescription(provider.getGroupDescription());
            effective.setDisplayDescription(provider.getDisplayDescription());
        }
        effective.setBaseUrl(resolved.getBaseUrl());
        effective.setApiKey(resolved.getApiKey());
        effective.setDefaultModel(resolved.getModel());
        effective.setDialect(resolved.getDialect());
        return effective;
    }

    /**
     * 构造鉴权画像。
     *
     * @param resolved provider解析结果。
     * @return 返回鉴权画像。
     */
    private Map<String, Object> authentication(LlmProviderService.ResolvedProvider resolved) {
        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        boolean keyRequired =
                !LlmConstants.PROVIDER_OLLAMA.equals(
                        StrUtil.nullToEmpty(resolved.getDialect()).trim().toLowerCase(Locale.ROOT));
        boolean configured = !keyRequired || SecretValueGuard.hasUsableSecret(resolved.getApiKey());
        auth.put("type", keyRequired ? "api_key" : "local");
        auth.put("required", Boolean.valueOf(keyRequired));
        auth.put("configured", Boolean.valueOf(configured));
        auth.put("status", configured ? "configured" : "missing_key");
        return auth;
    }

    /**
     * 构造模型目录画像。
     *
     * @param metadata 模型元数据。
     * @return 返回目录画像。
     */
    private Map<String, Object> catalog(ModelMetadata metadata) {
        Map<String, Object> catalog = new LinkedHashMap<String, Object>();
        catalog.put("default_model", metadata.getModel());
        catalog.put("aliases", metadata.getAliases());
        catalog.put("context_window", Integer.valueOf(metadata.getContextWindow()));
        catalog.put("max_output", Integer.valueOf(metadata.getMaxOutput()));
        catalog.put("model_list_url", SecretRedactor.maskUrl(metadata.getModelListUrl()));
        catalog.put("source", metadata.getSource());
        catalog.put("provenance", metadata.getProvenance());
        catalog.put("supported", Boolean.valueOf(metadata.isSupported()));
        return catalog;
    }

    /**
     * 构造能力画像。
     *
     * @param metadata 模型元数据。
     * @return 返回能力画像。
     */
    private Map<String, Object> capabilities(ModelMetadata metadata) {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("tool_calling", Boolean.valueOf(metadata.isSupportsTools()));
        capabilities.put("streaming", Boolean.valueOf(metadata.isSupportsStreaming()));
        capabilities.put("reasoning", Boolean.valueOf(metadata.isSupportsReasoning()));
        capabilities.put(
                "structured_output", Boolean.valueOf(metadata.isSupportsStructuredOutput()));
        capabilities.put("prompt_cache", Boolean.valueOf(metadata.isSupportsPromptCache()));
        capabilities.put("vision", Boolean.valueOf(metadata.isSupportsVision()));
        capabilities.put("audio", Boolean.valueOf(metadata.isSupportsAudio()));
        capabilities.put("attachment", Boolean.valueOf(metadata.isSupportsAttachment()));
        capabilities.put("pdf", Boolean.valueOf(metadata.isSupportsPdf()));
        capabilities.put("input_modalities", metadata.getInputModalities());
        capabilities.put("output_modalities", metadata.getOutputModalities());
        return capabilities;
    }

    /**
     * 构造推理参数画像。
     *
     * @param metadata 模型元数据。
     * @return 返回参数画像。
     */
    private Map<String, Object> parameters(ModelMetadata metadata) {
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("temperature", Double.valueOf(appConfig.getLlm().getTemperature()));
        parameters.put("max_tokens", Integer.valueOf(appConfig.getLlm().getMaxTokens()));
        parameters.put(
                "reasoning_effort",
                StrUtil.nullToEmpty(appConfig.getLlm().getReasoningEffort()).trim());
        parameters.put("reasoning_supported", Boolean.valueOf(metadata.isSupportsReasoning()));
        Map<String, Object> promptCache = new LinkedHashMap<String, Object>();
        promptCache.put(
                "enabled", Boolean.valueOf(appConfig.getLlm().getPromptCache().isEnabled()));
        promptCache.put("ttl", appConfig.getLlm().getPromptCache().getTtl());
        promptCache.put("layout", appConfig.getLlm().getPromptCache().getLayout());
        parameters.put("prompt_cache", promptCache);
        return parameters;
    }

    /**
     * 构造路由画像。
     *
     * @param providerKey provider键。
     * @param resolved provider解析结果。
     * @return 返回路由画像。
     */
    private Map<String, Object> routing(
            String providerKey, LlmProviderService.ResolvedProvider resolved) {
        Map<String, Object> routing = new LinkedHashMap<String, Object>();
        boolean primary = StrUtil.equals(providerKey, appConfig.getModel().getProviderKey());
        int fallbackOrder = fallbackOrder(providerKey);
        routing.put("role", primary ? "primary" : fallbackOrder >= 0 ? "fallback" : "auxiliary");
        routing.put("primary", Boolean.valueOf(primary));
        routing.put("fallback", Boolean.valueOf(fallbackOrder >= 0));
        if (fallbackOrder >= 0) {
            routing.put("fallback_order", Integer.valueOf(fallbackOrder));
            routing.put("fallback_model", fallbackModel(providerKey));
        }
        routing.put("effective_model", resolved.getModel());
        return routing;
    }

    /**
     * 构造展示画像。
     *
     * @param providerKey provider键。
     * @param provider provider配置。
     * @return 返回展示画像。
     */
    private Map<String, Object> display(String providerKey, AppConfig.ProviderConfig provider) {
        ProviderDisplayGrouping.ProviderDisplay display =
                ProviderDisplayGrouping.providerDisplay(providerKey, provider);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("label", display.getLabel());
        map.put("group_id", display.getGroupId());
        map.put("group_label", display.getGroupLabel());
        map.put("group_description", display.getGroupDescription());
        map.put("description", display.getDisplayDescription());
        return map;
    }

    /**
     * 构造价格画像。
     *
     * @param price 模型价格。
     * @return 返回价格画像。
     */
    private Map<String, Object> pricing(ModelPrice price) {
        Map<String, Object> pricing = new LinkedHashMap<String, Object>();
        pricing.put("available", Boolean.TRUE);
        String currency =
                StrUtil.blankToDefault(price.getCurrency(), "USD").trim().toUpperCase(Locale.ROOT);
        pricing.put("currency", currency);
        boolean free = price.isFree();
        pricing.put("free", Boolean.valueOf(free));
        pricing.put("status", free ? "free" : "priced");
        if (free) {
            pricing.put("input", "free");
            pricing.put("output", "free");
            pricing.put("cache_read", "free");
            pricing.put("cache_write", "free");
            pricing.put("reasoning", "free");
        } else {
            putPrice(pricing, "input", price.inputMicrosPerTokenExact(), currency);
            putPrice(pricing, "output", price.outputMicrosPerTokenExact(), currency);
            putPrice(pricing, "cache_read", price.cacheReadMicrosPerTokenExact(), currency);
            putPrice(pricing, "cache_write", price.cacheWriteMicrosPerTokenExact(), currency);
            putPrice(pricing, "reasoning", price.reasoningMicrosPerTokenExact(), currency);
        }
        if (StrUtil.isNotBlank(price.getSource())) {
            pricing.put("source", price.getSource());
        }
        if (StrUtil.isNotBlank(price.getSourceUrl())) {
            pricing.put("source_url", price.getSourceUrl());
        }
        if (StrUtil.isNotBlank(price.getPricingVersion())) {
            pricing.put("pricing_version", price.getPricingVersion());
        }
        if (price.getFetchedAt() > 0L) {
            pricing.put("fetched_at", Long.valueOf(price.getFetchedAt()));
        }
        return pricing;
    }

    /**
     * 返回未配置价格时的显式状态，避免缺字段被误读为免费。
     *
     * @return 返回 unknown 价格画像。
     */
    private Map<String, Object> unknownPricing() {
        Map<String, Object> pricing = new LinkedHashMap<String, Object>();
        pricing.put("available", Boolean.FALSE);
        pricing.put("status", "unknown");
        pricing.put("free", Boolean.FALSE);
        return pricing;
    }

    /**
     * 写入价格字段。
     *
     * @param pricing 价格画像。
     * @param key 字段名。
     * @param microsPerToken 每百万token价格。
     * @param currency 币种。
     */
    private void putPrice(
            Map<String, Object> pricing, String key, BigDecimal microsPerToken, String currency) {
        if (microsPerToken == null || microsPerToken.signum() <= 0) {
            return;
        }
        String amount = microsPerToken.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        pricing.put(key, "USD".equals(currency) ? "$" + amount : currency + " " + amount);
    }

    /**
     * 读取fallback顺序。
     *
     * @param providerKey provider键。
     * @return 返回0基顺序，未命中返回-1。
     */
    private int fallbackOrder(String providerKey) {
        List<AppConfig.FallbackProviderConfig> fallbacks = appConfig.getFallbackProviders();
        if (fallbacks == null) {
            return -1;
        }
        for (int i = 0; i < fallbacks.size(); i++) {
            AppConfig.FallbackProviderConfig fallback = fallbacks.get(i);
            if (fallback != null && StrUtil.equals(providerKey, fallback.getProvider())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 读取fallback模型。
     *
     * @param providerKey provider键。
     * @return 返回模型。
     */
    private String fallbackModel(String providerKey) {
        List<AppConfig.FallbackProviderConfig> fallbacks = appConfig.getFallbackProviders();
        if (fallbacks == null) {
            return "";
        }
        for (AppConfig.FallbackProviderConfig fallback : fallbacks) {
            if (fallback != null && StrUtil.equals(providerKey, fallback.getProvider())) {
                return StrUtil.nullToEmpty(fallback.getModel());
            }
        }
        return "";
    }

    /**
     * 克隆fallback配置。
     *
     * @return 返回fallback配置。
     */
    private List<Map<String, Object>> fallbackProviders() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (appConfig.getFallbackProviders() == null) {
            return result;
        }
        for (AppConfig.FallbackProviderConfig fallback : appConfig.getFallbackProviders()) {
            if (fallback == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", fallback.getProvider());
            item.put("model", fallback.getModel());
            result.add(item);
        }
        return result;
    }
}
