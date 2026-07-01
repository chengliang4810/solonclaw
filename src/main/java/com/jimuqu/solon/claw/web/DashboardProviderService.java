package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.ProviderDisplayGrouping;
import com.jimuqu.solon.claw.support.ProviderProfileService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.UrlOriginSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Dashboard provider 配置管理服务。 */
public class DashboardProviderService {
    /** 模型列表缓存TTLMILLIS的统一常量值。 */
    private static final long MODEL_LIST_CACHE_TTL_MILLIS = 60L * 60L * 1000L;

    /** 模型列表缓存最大ENTRIES的统一常量值。 */
    private static final int MODEL_LIST_CACHE_MAX_ENTRIES = 64;

    /** 注入应用配置，用于控制台提供方。 */
    private final AppConfig appConfig;

    /** 注入消息网关工作区配置刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 注入模型元数据服务，用于调用对应业务能力。 */
    private final ModelMetadataService modelMetadataService;

    /** 注入Provider画像服务，用于输出运行时画像。 */
    private final ProviderProfileService providerProfileService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 保存模型列表缓存映射，便于按键快速查询。 */
    private final Map<String, ModelListCacheEntry> modelListCache =
            new LinkedHashMap<String, ModelListCacheEntry>(16, 0.75f, true) {
                /**
                 * 移除Eldest Entry。
                 *
                 * @param eldest eldest 参数。
                 * @return 返回Eldest Entry结果。
                 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ModelListCacheEntry> eldest) {
                    return size() > MODEL_LIST_CACHE_MAX_ENTRIES;
                }
            };

    /**
     * 创建控制台提供方服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public DashboardProviderService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService) {
        this(appConfig, gatewayRuntimeRefreshService, llmProviderService, null);
    }

    /**
     * 创建控制台提供方服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DashboardProviderService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService,
            SecurityPolicyService securityPolicyService) {
        this.appConfig = appConfig;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.llmProviderService = llmProviderService;
        this.modelMetadataService = new ModelMetadataService(appConfig);
        this.providerProfileService = new ProviderProfileService(appConfig, llmProviderService);
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
    }

    /**
     * 列出Providers。
     *
     * @return 返回Providers列表。
     */
    public Map<String, Object> listProviders() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                appConfig.getProviders().entrySet()) {
            items.add(toProviderMap(entry.getKey(), entry.getValue()));
        }
        result.put("providers", items);
        result.put("defaultProviderKey", appConfig.getModel().getProviderKey());
        result.put("defaultModel", appConfig.getModel().getDefault());
        result.put("fallbackProviders", cloneFallbackProviders(appConfig.getFallbackProviders()));
        result.put("dialectCatalog", dialectCatalog());
        result.put("providerProfiles", providerProfileService.listProfiles());
        return result;
    }

    /**
     * 执行项目Models相关逻辑。
     *
     * @return 返回项目Models结果。
     */
    public Map<String, Object> JimuquModels() {
        Map<String, Object> result = listProviders();
        List<Map<String, Object>> models = new ArrayList<Map<String, Object>>();
        List<ProviderDisplayGrouping.Item> groupItems =
                new ArrayList<ProviderDisplayGrouping.Item>();
        PriceCatalog priceCatalog = PriceCatalog.forConfig(appConfig);
        for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                appConfig.getProviders().entrySet()) {
            AppConfig.ProviderConfig provider = entry.getValue();
            ProviderDisplayGrouping.ProviderDisplay display =
                    ProviderDisplayGrouping.providerDisplay(entry.getKey(), provider);
            ModelMetadata metadata = modelMetadataService.resolve(entry.getKey(), provider);
            Map<String, Object> model = new LinkedHashMap<String, Object>();
            model.put("provider", entry.getKey());
            model.put("model", provider.getDefaultModel());
            model.put("dialect", provider.getDialect());
            model.put(
                    "role",
                    entry.getKey().equals(appConfig.getModel().getProviderKey())
                            ? "primary"
                            : "auxiliary");
            model.put("status", providerStatus(provider));
            model.put("metadata", metadataMap(metadata));
            model.put("api_url", SecretRedactor.maskUrl(metadata.getApiUrl()));
            model.put("model_list_url", SecretRedactor.maskUrl(metadata.getModelListUrl()));
            model.put("aliases", metadata.getAliases());
            model.put("context_window", metadata.getContextWindow());
            model.put("max_output", metadata.getMaxOutput());
            model.put("reasoning_effort", appConfig.getLlm().getReasoningEffort());
            appendPricingMetadata(
                    model, priceCatalog.find(entry.getKey(), provider.getDefaultModel()));
            appendProviderDisplay(model, display);
            models.add(model);
            groupItems.add(
                    new ProviderDisplayGrouping.Item(
                            entry.getKey(),
                            display.getLabel(),
                            display.getGroupId(),
                            display.getGroupLabel(),
                            display.getGroupDescription(),
                            display.getDisplayDescription(),
                            model));
        }
        result.put("models", models);
        List<Map<String, Object>> modelGroups = modelGroups(groupItems);
        result.put("model_groups", modelGroups);
        result.put("modelGroups", modelGroups);
        result.put("fallback_chain", cloneFallbackProviders(appConfig.getFallbackProviders()));
        return result;
    }

    /**
     * 执行健康检查相关逻辑。
     *
     * @return 返回健康检查结果。
     */
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> providers = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                appConfig.getProviders().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", entry.getKey());
            item.put("status", providerStatus(entry.getValue()));
            item.put("checked_at", System.currentTimeMillis());
            providers.add(item);
        }
        result.put("providers", providers);
        return result;
    }

    /**
     * 执行提供方状态相关逻辑。
     *
     * @param provider 模型或能力提供方。
     * @return 返回提供方状态。
     */
    private String providerStatus(AppConfig.ProviderConfig provider) {
        if (provider == null || StrUtil.isBlank(provider.getBaseUrl())) {
            return "unreachable";
        }
        if (!SecretValueGuard.hasUsableSecret(provider.getApiKey())
                && !"ollama".equalsIgnoreCase(provider.getDialect())) {
            return "missing_key";
        }
        return "configured";
    }

    /**
     * 执行元数据映射相关逻辑。
     *
     * @param metadata 元数据参数。
     * @return 返回元数据Map结果。
     */
    private Map<String, Object> metadataMap(ModelMetadata metadata) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("provider", metadata.getProvider());
        map.put("model", metadata.getModel());
        map.put("dialect", metadata.getDialect());
        map.put("context_window", metadata.getContextWindow());
        map.put("max_output", metadata.getMaxOutput());
        map.put("api_url", SecretRedactor.maskUrl(metadata.getApiUrl()));
        map.put("model_list_url", SecretRedactor.maskUrl(metadata.getModelListUrl()));
        map.put("tool_calling", Boolean.valueOf(metadata.isSupportsTools()));
        map.put("vision", Boolean.valueOf(metadata.isSupportsVision()));
        map.put("audio", Boolean.valueOf(metadata.isSupportsAudio()));
        map.put("attachment", Boolean.valueOf(metadata.isSupportsAttachment()));
        map.put("pdf", Boolean.valueOf(metadata.isSupportsPdf()));
        map.put("multimodal", Boolean.valueOf(metadata.isSupportsMultimodal()));
        map.put("streaming", Boolean.valueOf(metadata.isSupportsStreaming()));
        map.put("reasoning", Boolean.valueOf(metadata.isSupportsReasoning()));
        map.put("structured_output", Boolean.valueOf(metadata.isSupportsStructuredOutput()));
        map.put("open_weights", Boolean.valueOf(metadata.isSupportsOpenWeights()));
        map.put("interleaved", Boolean.valueOf(metadata.isSupportsInterleaved()));
        map.put("prompt_cache", Boolean.valueOf(metadata.isSupportsPromptCache()));
        map.put("source", metadata.getSource());
        map.put("default_model", Boolean.valueOf(metadata.isDefaultModel()));
        map.put("supported", Boolean.valueOf(metadata.isSupported()));
        return map;
    }

    /**
     * 追加价格元数据。
     *
     * @param model 模型名称。
     * @param price 价格参数。
     */
    private void appendPricingMetadata(Map<String, Object> model, ModelPrice price) {
        if (model == null || price == null) {
            return;
        }
        Map<String, Object> pricing = new LinkedHashMap<String, Object>();
        String currency = normalizeCurrency(price.getCurrency());
        pricing.put("currency", currency);
        boolean free = price.isFree();
        if (free) {
            pricing.put("input", "free");
            pricing.put("output", "free");
            pricing.put("cache", "free");
            pricing.put("cache_read", "free");
            pricing.put("cache_write", "free");
            pricing.put("reasoning", "free");
        } else {
            putPriceField(pricing, "input", price.inputMicrosPerTokenExact(), currency);
            putPriceField(pricing, "output", price.outputMicrosPerTokenExact(), currency);
            putPriceField(pricing, "cache", price.cacheReadMicrosPerTokenExact(), currency);
            putPriceField(pricing, "cache_read", price.cacheReadMicrosPerTokenExact(), currency);
            putPriceField(pricing, "cache_write", price.cacheWriteMicrosPerTokenExact(), currency);
            putPriceField(pricing, "reasoning", price.reasoningMicrosPerTokenExact(), currency);
        }
        pricing.put("free", Boolean.valueOf(free));
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
        model.put("pricing", pricing);
    }

    /**
     * 写入价格Field。
     *
     * @param pricing 价格参数。
     * @param key 配置键或映射键。
     * @param microsPerToken microsPertoken参数。
     * @param currency currency 参数。
     */
    private void putPriceField(
            Map<String, Object> pricing, String key, BigDecimal microsPerToken, String currency) {
        if (microsPerToken != null && microsPerToken.signum() > 0) {
            pricing.put(key, formatPerMillionPrice(microsPerToken, currency));
        }
    }

    /**
     * 格式化Per Million价格。
     *
     * @param microsPerToken microsPertoken参数。
     * @param currency currency 参数。
     * @return 返回Per Million价格结果。
     */
    private String formatPerMillionPrice(BigDecimal microsPerToken, String currency) {
        String amount = microsPerToken.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        if ("USD".equals(currency)) {
            return "$" + amount;
        }
        return currency + " " + amount;
    }

    /**
     * 规范化Currency。
     *
     * @param currency currency 参数。
     * @return 返回Currency结果。
     */
    private String normalizeCurrency(String currency) {
        return StrUtil.blankToDefault(currency, "USD").trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 创建提供方。
     *
     * @param data 数据参数。
     * @return 返回创建好的提供方。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createProvider(Map<String, Object> data) {
        String providerKey = readString(data, "providerKey");
        ensureProviderKey(providerKey);
        Map<String, Object> root = loadRootForMutation();
        Map<String, Object> providers = getOrCreateMap(root, "providers");
        if (providers.containsKey(providerKey)) {
            throw new IllegalArgumentException("Provider 已存在：" + providerKey);
        }
        providers.put(providerKey, toProviderNode(data, null));

        Map<String, Object> model = getOrCreateMap(root, "model");
        if (StrUtil.isBlank(readString(model, "providerKey"))) {
            model.put("providerKey", providerKey);
        }

        write(root);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 更新提供方。
     *
     * @param providerKey 提供方键标识或键值。
     * @param data 数据参数。
     * @return 返回提供方结果。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateProvider(String providerKey, Map<String, Object> data) {
        ensureProviderKey(providerKey);
        Map<String, Object> root = loadRootForMutation();
        Map<String, Object> providers = getOrCreateMap(root, "providers");
        Object existing = providers.get(providerKey);
        if (!(existing instanceof Map)) {
            throw new IllegalArgumentException("Provider 不存在：" + providerKey);
        }
        providers.put(providerKey, toProviderNode(data, (Map<String, Object>) existing));
        write(root);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 删除提供方。
     *
     * @param providerKey 提供方键标识或键值。
     * @return 返回提供方结果。
     */
    public Map<String, Object> deleteProvider(String providerKey) {
        ensureProviderKey(providerKey);
        if (StrUtil.equals(providerKey, appConfig.getModel().getProviderKey())) {
            throw new IllegalArgumentException("当前默认 provider 不能删除。");
        }
        for (AppConfig.FallbackProviderConfig fallback : appConfig.getFallbackProviders()) {
            if (fallback != null && StrUtil.equals(providerKey, fallback.getProvider())) {
                throw new IllegalArgumentException("该 provider 正在 fallbackProviders 中使用，不能删除。");
            }
        }

        Map<String, Object> root = loadRootForMutation();
        Map<String, Object> providers = getOrCreateMap(root, "providers");
        providers.remove(providerKey);
        write(root);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 更新默认模型。
     *
     * @param providerKey 提供方键标识或键值。
     * @param model 模型名称。
     * @return 返回默认模型结果。
     */
    public Map<String, Object> updateDefaultModel(String providerKey, String model) {
        String nextProviderKey =
                StrUtil.isNotBlank(providerKey)
                        ? providerKey.trim()
                        : appConfig.getModel().getProviderKey();
        if (!llmProviderService.hasProvider(nextProviderKey)) {
            throw new IllegalArgumentException("未找到 provider：" + nextProviderKey);
        }

        Map<String, Object> root = loadRootForMutation();
        Map<String, Object> modelNode = getOrCreateMap(root, "model");
        modelNode.put("providerKey", nextProviderKey);
        modelNode.put("default", StrUtil.nullToEmpty(model).trim());
        write(root);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 更新兜底Providers。
     *
     * @param items items 参数。
     * @return 返回兜底Providers结果。
     */
    public Map<String, Object> updateFallbackProviders(List<Map<String, Object>> items) {
        List<Object> next = new ArrayList<Object>();
        if (items != null) {
            for (Map<String, Object> item : items) {
                if (item == null) {
                    continue;
                }
                String provider = readString(item, "provider");
                if (!llmProviderService.hasProvider(provider)) {
                    throw new IllegalArgumentException(
                            "fallbackProviders 引用了不存在的 provider：" + provider);
                }
                Map<String, Object> node = new LinkedHashMap<String, Object>();
                node.put("provider", provider);
                String model = readString(item, "model");
                if (StrUtil.isNotBlank(model)) {
                    node.put("model", model);
                }
                next.add(node);
            }
        }

        Map<String, Object> root = loadRootForMutation();
        root.put("fallbackProviders", next);
        write(root);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 列出Remote Models。
     *
     * @param data 数据参数。
     * @return 返回Remote Models列表。
     */
    public Map<String, Object> listRemoteModels(Map<String, Object> data) {
        ProviderProbe probe = resolveProviderProbe(data);
        String providerKey = probe.providerKey;
        String apiKey = probe.apiKey;
        String dialect = probe.dialect;
        String url = LlmProviderSupport.buildModelListUrl(providerKey, probe.baseUrl, dialect);
        assertSafeProviderUrl(url, dialect);
        String cacheKey = modelListCacheKey(providerKey, url, dialect, apiKey);
        ModelListCacheEntry cached = cachedModelList(cacheKey);
        if (isFresh(cached)) {
            return modelListResult(cached, "hit");
        }
        try {
            HttpResponse response = executeModelListRequest(url, apiKey, dialect, 0);
            String body;
            try {
                int status = response.getStatus();
                body = response.body();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(
                            "获取模型列表失败：HTTP " + status + " " + trimForError(body));
                }
            } finally {
                response.close();
            }

            ModelListCacheEntry refreshed =
                    new ModelListCacheEntry(
                            SecretRedactor.maskUrl(url),
                            parseModels(body, dialect),
                            currentTimeMillis());
            cacheModelList(cacheKey, refreshed);
            return modelListResult(refreshed, cached == null ? "miss" : "refresh");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            if (cached != null) {
                return modelListResult(cached, "stale");
            }
            throw e;
        }
    }

    /**
     * 校验提供方。
     *
     * @param data 数据参数。
     * @return 返回提供方结果。
     */
    public Map<String, Object> validateProvider(Map<String, Object> data) {
        ProviderProbe probe = resolveProviderProbe(data);
        String url =
                LlmProviderSupport.buildModelListUrl(
                        probe.providerKey, probe.baseUrl, probe.dialect);
        assertSafeProviderUrl(url, probe.dialect);
        try {
            HttpResponse response = executeModelListRequest(url, probe.apiKey, probe.dialect, 0);
            try {
                int status = response.getStatus();
                String body = response.body();
                if (status >= 200 && status < 300) {
                    Map<String, Object> result =
                            providerValidationResult(
                                    true, true, "valid", "Provider reachable.", url);
                    result.put("models", parseModels(body, probe.dialect));
                    return result;
                }
                if (shouldFallbackToAnthropicMessageProbe(status, probe)) {
                    return validateAnthropicMessagesProbe(probe);
                }
                if (status == 429) {
                    return providerValidationResult(
                            true, true, "rate_limited", "HTTP 429 " + trimForError(body), url);
                }
                if (status == 401 || status == 403) {
                    return providerValidationResult(
                            false,
                            true,
                            "rejected",
                            "HTTP " + status + " " + trimForError(body),
                            url);
                }
                return providerValidationResult(
                        false, true, "error", "HTTP " + status + " " + trimForError(body), url);
            } finally {
                response.close();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            return providerValidationResult(
                    false, false, "unreachable", validationRuntimeMessage(e), url);
        }
    }

    /**
     * 解析提供方Probe。
     *
     * @param data 数据参数。
     * @return 返回解析后的提供方Probe。
     */
    private ProviderProbe resolveProviderProbe(Map<String, Object> data) {
        String providerKey = readString(data, "providerKey");
        String baseUrl = readString(data, "baseUrl");
        String apiKey = readString(data, "apiKey");
        String dialect = LlmProviderSupport.normalizeDialect(readString(data, "dialect"));
        String model = readFirstString(data, "model", "defaultModel");
        AppConfig.ProviderConfig provider =
                StrUtil.isBlank(providerKey) ? null : appConfig.getProviders().get(providerKey);
        if (provider != null) {
            baseUrl = StrUtil.blankToDefault(baseUrl, provider.getBaseUrl());
            apiKey = StrUtil.blankToDefault(apiKey, provider.getApiKey());
            dialect =
                    LlmProviderSupport.normalizeDialect(
                            StrUtil.blankToDefault(dialect, provider.getDialect()));
            model = StrUtil.blankToDefault(model, provider.getDefaultModel());
        }
        model = StrUtil.blankToDefault(model, appConfig.getModel().getDefault());
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不能为空。");
        }
        assertSafeProviderBaseUrl(baseUrl);
        if (StrUtil.isBlank(dialect) || !LlmProviderSupport.isSupportedDialect(dialect)) {
            throw new IllegalArgumentException("不支持的 dialect：" + dialect);
        }
        return new ProviderProbe(providerKey, baseUrl, apiKey, dialect, model);
    }

    /**
     * 判断是否需要用 Anthropic messages 端点兜底校验。部分兼容服务不提供 /models，但 /messages 可正常工作。
     *
     * @param status 模型列表请求状态码。
     * @param probe provider 探针参数。
     * @return 需要兜底校验时返回 true。
     */
    private boolean shouldFallbackToAnthropicMessageProbe(int status, ProviderProbe probe) {
        return probe != null
                && LlmConstants.PROVIDER_ANTHROPIC.equals(probe.dialect)
                && (status == 404 || status == 405)
                && StrUtil.isNotBlank(probe.model);
    }

    /**
     * 通过 Anthropic messages 端点做最小连通性校验，避免把缺少模型列表接口误判为 provider 不可用。
     *
     * @param probe provider 探针参数。
     * @return provider 校验结果。
     */
    private Map<String, Object> validateAnthropicMessagesProbe(ProviderProbe probe) {
        String url = LlmProviderSupport.buildApiUrl(probe.baseUrl, probe.dialect);
        assertSafeProviderUrl(url, probe.dialect);
        try {
            HttpResponse response =
                    executeAnthropicMessageProbeRequest(url, probe.apiKey, probe.model);
            try {
                int status = response.getStatus();
                String body = response.body();
                if (status >= 200 && status < 300) {
                    Map<String, Object> result =
                            providerValidationResult(
                                    true,
                                    true,
                                    "valid",
                                    "Provider reachable; model list endpoint unavailable.",
                                    url);
                    result.put("models", Collections.singletonList(probe.model));
                    return result;
                }
                if (status == 429) {
                    return providerValidationResult(
                            true, true, "rate_limited", "HTTP 429 " + trimForError(body), url);
                }
                if (status == 401 || status == 403) {
                    return providerValidationResult(
                            false,
                            true,
                            "rejected",
                            "HTTP " + status + " " + trimForError(body),
                            url);
                }
                return providerValidationResult(
                        false, true, "error", "HTTP " + status + " " + trimForError(body), url);
            } finally {
                response.close();
            }
        } catch (RuntimeException e) {
            return providerValidationResult(
                    false, false, "unreachable", validationRuntimeMessage(e), url);
        }
    }

    /**
     * 执行提供方Validation结果相关逻辑。
     *
     * @param ok ok 参数。
     * @param reachable reachable 参数。
     * @param status 状态参数。
     * @param message 平台消息或错误消息。
     * @param url 待校验或访问的 URL。
     * @return 返回提供方Validation结果。
     */
    private Map<String, Object> providerValidationResult(
            boolean ok, boolean reachable, String status, String message, String url) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.valueOf(ok));
        result.put("reachable", Boolean.valueOf(reachable));
        result.put("status", status);
        result.put("message", SecretRedactor.redact(StrUtil.nullToEmpty(message), 1000));
        result.put("url", SecretRedactor.maskUrl(StrUtil.nullToEmpty(url)));
        return result;
    }

    /**
     * 执行validation运行时消息相关逻辑。
     *
     * @param e 捕获到的异常。
     * @return 返回validation运行时消息结果。
     */
    private String validationRuntimeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (StrUtil.isBlank(message) && e.getCause() != null) {
            message = e.getCause().getMessage();
        }
        return SecretRedactor.redact(
                StrUtil.blankToDefault(message, e.getClass().getSimpleName()), 1000);
    }

    /**
     * 执行当前时间Millis相关逻辑。
     *
     * @return 返回当前时间Millis结果。
     */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 执行模型列表缓存TtlMillis相关逻辑。
     *
     * @return 返回模型List缓存Ttl Millis结果。
     */
    protected long modelListCacheTtlMillis() {
        return MODEL_LIST_CACHE_TTL_MILLIS;
    }

    /**
     * 执行cached模型列表相关逻辑。
     *
     * @param cacheKey 缓存键标识或键值。
     * @return 返回cached模型List结果。
     */
    private ModelListCacheEntry cachedModelList(String cacheKey) {
        synchronized (modelListCache) {
            return modelListCache.get(cacheKey);
        }
    }

    /**
     * 执行缓存模型列表相关逻辑。
     *
     * @param cacheKey 缓存键标识或键值。
     * @param entry entry 参数。
     */
    private void cacheModelList(String cacheKey, ModelListCacheEntry entry) {
        synchronized (modelListCache) {
            modelListCache.put(cacheKey, entry);
        }
    }

    /**
     * 判断是否Fresh。
     *
     * @param entry entry 参数。
     * @return 如果Fresh满足条件则返回 true，否则返回 false。
     */
    private boolean isFresh(ModelListCacheEntry entry) {
        if (entry == null) {
            return false;
        }
        long ttl = Math.max(0L, modelListCacheTtlMillis());
        return ttl > 0L && currentTimeMillis() - entry.cachedAt <= ttl;
    }

    /**
     * 执行模型列表缓存键相关逻辑。
     *
     * @param providerKey 提供方键标识或键值。
     * @param url 待校验或访问的 URL。
     * @param dialect dialect 参数。
     * @param apiKey api键标识或键值。
     * @return 返回模型List缓存键结果。
     */
    private String modelListCacheKey(
            String providerKey, String url, String dialect, String apiKey) {
        return StrUtil.nullToEmpty(providerKey)
                + "|"
                + StrUtil.nullToEmpty(dialect)
                + "|"
                + StrUtil.nullToEmpty(url)
                + "|key:"
                + Integer.toHexString(StrUtil.nullToEmpty(apiKey).hashCode());
    }

    /**
     * 执行模型列表结果相关逻辑。
     *
     * @param entry entry 参数。
     * @param cacheStatus 缓存状态参数。
     * @return 返回模型List结果。
     */
    private Map<String, Object> modelListResult(ModelListCacheEntry entry, String cacheStatus) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("url", entry.url);
        result.put("models", new ArrayList<String>(entry.models));
        result.put("cache", cacheStatus);
        return result;
    }

    /**
     * 执行模型List请求。
     *
     * @param url 待校验或访问的 URL。
     * @param apiKey api键标识或键值。
     * @param dialect dialect 参数。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回模型List请求结果。
     */
    protected HttpResponse executeModelListRequest(
            String url, String apiKey, String dialect, int redirectCount) {
        return executeModelListRequest(url, url, apiKey, dialect, redirectCount);
    }

    /**
     * 执行 Anthropic messages 最小校验请求，仅用于 provider 可达性诊断，不进入正式聊天链路。
     *
     * @param url 待校验或访问的 URL。
     * @param apiKey api 键。
     * @param model 模型名称。
     * @return 返回 HTTP 响应。
     */
    protected HttpResponse executeAnthropicMessageProbeRequest(
            String url, String apiKey, String model) {
        assertSafeProviderUrl(url, LlmConstants.PROVIDER_ANTHROPIC);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", StrUtil.nullToEmpty(model).trim());
        payload.put("max_tokens", Integer.valueOf(1));
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("role", "user");
        message.put("content", "ping");
        messages.add(message);
        payload.put("messages", messages);

        HttpRequest request =
                HttpRequest.post(url)
                        .timeout(15000)
                        .setFollowRedirects(false)
                        .header("content-type", "application/json")
                        .body(ONode.serialize(payload));
        if (StrUtil.isNotBlank(apiKey)) {
            request.header("Authorization", "Bearer " + apiKey);
            request.header("x-api-key", apiKey);
            request.header("anthropic-version", "2023-06-01");
        }
        return request.execute();
    }

    /**
     * 执行模型List请求。
     *
     * @param initialUrl 待校验或访问的地址参数。
     * @param url 待校验或访问的 URL。
     * @param apiKey api键标识或键值。
     * @param dialect dialect 参数。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回模型List请求结果。
     */
    private HttpResponse executeModelListRequest(
            String initialUrl, String url, String apiKey, String dialect, int redirectCount) {
        assertSafeProviderUrl(url, dialect);
        HttpRequest request = HttpRequest.get(url).timeout(15000).setFollowRedirects(false);
        if (StrUtil.isNotBlank(apiKey) && UrlOriginSupport.sameOrigin(initialUrl, url)) {
            if (LlmConstants.PROVIDER_GEMINI.equals(dialect)) {
                request.form("key", apiKey);
            } else {
                request.header("Authorization", "Bearer " + apiKey);
            }
            if (LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)) {
                request.header("x-api-key", apiKey);
                request.header("anthropic-version", "2023-06-01");
            }
        }
        HttpResponse response = request.execute();
        int status = response.getStatus();
        if (isRedirect(status)) {
            try {
                if (redirectCount >= 5) {
                    throw new IllegalStateException("获取模型列表重定向次数过多。");
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("获取模型列表重定向缺少 Location。");
                }
                String nextUrl = resolveRedirectUrl(url, location);
                response.close();
                return executeModelListRequest(
                        initialUrl, nextUrl, apiKey, dialect, redirectCount + 1);
            } catch (RuntimeException e) {
                response.close();
                throw e;
            }
        }
        return response;
    }

    /**
     * 执行assert安全提供方URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param dialect 提供方协议，用于复用正式 LLM 调用的私网例外规则。
     */
    private void assertSafeProviderUrl(String url, String dialect) {
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrlSafety(url, providerPrivateUrlOverride(dialect));
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Provider model list URL blocked: "
                            + com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(verdict.getUrl())
                            + " ("
                            + verdict.getMessage()
                            + ")");
        }
    }

    /**
     * 返回模型提供方 URL 硬安全检查的私网覆盖策略；与正式 LLM API 调用保持一致。
     *
     * @param dialect 提供方协议。
     * @return Ollama 允许本地私网模型服务，其他协议沿用全局安全配置。
     */
    private Boolean providerPrivateUrlOverride(String dialect) {
        return LlmConstants.PROVIDER_OLLAMA.equals(
                        LlmProviderSupport.normalizeDialect(dialect))
                ? Boolean.TRUE
                : null;
    }

    /**
     * 判断是否Redirect。
     *
     * @param status 状态参数。
     * @return 如果Redirect满足条件则返回 true，否则返回 false。
     */
    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 解析Redirect URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param location location 参数。
     * @return 返回解析后的Redirect URL。
     */
    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("获取模型列表重定向 URL 无效。", e);
        }
    }

    /**
     * 转换为提供方Map。
     *
     * @param providerKey 提供方键标识或键值。
     * @param provider 模型或能力提供方。
     * @return 返回转换后的提供方Map。
     */
    private Map<String, Object> toProviderMap(
            String providerKey, AppConfig.ProviderConfig provider) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("providerKey", providerKey);
        item.put("name", StrUtil.blankToDefault(provider.getName(), providerKey));
        item.put("baseUrl", SecretRedactor.maskUrl(StrUtil.nullToEmpty(provider.getBaseUrl())));
        item.put("defaultModel", StrUtil.nullToEmpty(provider.getDefaultModel()));
        item.put("dialect", StrUtil.nullToEmpty(provider.getDialect()));
        item.put("hasApiKey", SecretValueGuard.hasUsableSecret(provider.getApiKey()));
        item.put("isDefault", StrUtil.equals(providerKey, appConfig.getModel().getProviderKey()));
        item.put("metadata", metadataMap(modelMetadataService.resolve(providerKey, provider)));
        appendProviderDisplay(item, ProviderDisplayGrouping.providerDisplay(providerKey, provider));
        return item;
    }

    /**
     * 返回 Dashboard 可选择的大模型协议目录，前端表单以此为主、静态清单仅作为离线兜底。
     *
     * @return 支持协议的展示目录。
     */
    private List<Map<String, Object>> dialectCatalog() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String dialect : LlmConstants.SUPPORTED_PROVIDERS) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("value", dialect);
            item.put("labelKey", dialectLabelKey(dialect));
            item.put("baseUrlPlaceholder", dialectBaseUrlPlaceholder(dialect));
            items.add(item);
        }
        return items;
    }

    /**
     * 返回协议对应的前端翻译键，避免协议名和展示文案散落在页面组件里。
     *
     * @param dialect 大模型协议标识。
     * @return 前端 i18n 翻译键。
     */
    private String dialectLabelKey(String dialect) {
        if (LlmConstants.PROVIDER_OPENAI.equals(dialect)) {
            return "models.dialectOpenai";
        }
        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(dialect)) {
            return "models.dialectOpenaiResponses";
        }
        if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            return "models.dialectOllama";
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(dialect)) {
            return "models.dialectGemini";
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)) {
            return "models.dialectAnthropic";
        }
        return dialect;
    }

    /**
     * 返回协议默认基础地址占位符，仅作为表单提示，不参与运行时路由或安全判断。
     *
     * @param dialect 大模型协议标识。
     * @return 协议基础地址占位符。
     */
    private String dialectBaseUrlPlaceholder(String dialect) {
        if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            return "http://127.0.0.1:11434";
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(dialect)) {
            return "https://generativelanguage.googleapis.com";
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(dialect)) {
            return "https://api.anthropic.com";
        }
        return "https://api.example.com";
    }

    /**
     * 追加提供方展示。
     *
     * @param item item 参数。
     * @param display 展示参数。
     */
    private void appendProviderDisplay(
            Map<String, Object> item, ProviderDisplayGrouping.ProviderDisplay display) {
        if (item == null || display == null) {
            return;
        }
        if (StrUtil.isNotBlank(display.getDisplayDescription())) {
            item.put("display_description", display.getDisplayDescription());
            item.put("displayDescription", display.getDisplayDescription());
        }
        if (StrUtil.isNotBlank(display.getGroupId())) {
            item.put("group_id", display.getGroupId());
            item.put("groupId", display.getGroupId());
            item.put("group_label", display.getGroupLabel());
            item.put("groupLabel", display.getGroupLabel());
            item.put("group_description", display.getGroupDescription());
            item.put("groupDescription", display.getGroupDescription());
        }
    }

    /**
     * 执行模型Groups相关逻辑。
     *
     * @param items items 参数。
     * @return 返回模型Groups结果。
     */
    private List<Map<String, Object>> modelGroups(List<ProviderDisplayGrouping.Item> items) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (ProviderDisplayGrouping.Row row : ProviderDisplayGrouping.group(items)) {
            if (!"group".equals(row.getKind())) {
                continue;
            }
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("kind", "group");
            group.put("group_id", row.getGroupId());
            group.put("groupId", row.getGroupId());
            group.put("label", row.getLabel());
            group.put("description", row.getDescription());
            List<Object> members = new ArrayList<Object>();
            for (ProviderDisplayGrouping.Item member : row.getMembers()) {
                members.add(member.getPayload());
            }
            group.put("members", members);
            rows.add(group);
        }
        return rows;
    }

    /**
     * 转换为提供方Node。
     *
     * @param provider 模型或能力提供方。
     * @return 返回转换后的提供方Node。
     */
    private Map<String, Object> toProviderNode(AppConfig.ProviderConfig provider) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", StrUtil.nullToEmpty(provider.getName()).trim());
        result.put("baseUrl", StrUtil.nullToEmpty(provider.getBaseUrl()).trim());
        result.put("apiKey", StrUtil.nullToEmpty(provider.getApiKey()).trim());
        result.put("defaultModel", StrUtil.nullToEmpty(provider.getDefaultModel()).trim());
        result.put("dialect", StrUtil.nullToEmpty(provider.getDialect()).trim());
        if (provider.getSupportsVision() != null) {
            result.put("supportsVision", provider.getSupportsVision());
        }
        putIfNotBlank(result, "groupId", provider.getGroupId());
        putIfNotBlank(result, "groupLabel", provider.getGroupLabel());
        putIfNotBlank(result, "groupDescription", provider.getGroupDescription());
        putIfNotBlank(result, "displayDescription", provider.getDisplayDescription());
        return result;
    }

    /**
     * 克隆兜底Providers。
     *
     * @param source 来源参数。
     * @return 返回clone兜底Providers结果。
     */
    private List<Map<String, Object>> cloneFallbackProviders(
            List<AppConfig.FallbackProviderConfig> source) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (source == null) {
            return result;
        }
        for (AppConfig.FallbackProviderConfig item : source) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("provider", StrUtil.nullToEmpty(item.getProvider()));
            row.put("model", StrUtil.nullToEmpty(item.getModel()));
            result.add(row);
        }
        return result;
    }

    /**
     * 确保提供方键。
     *
     * @param providerKey 提供方键标识或键值。
     */
    private void ensureProviderKey(String providerKey) {
        if (StrUtil.isBlank(providerKey)) {
            throw new IllegalArgumentException("providerKey 不能为空。");
        }
    }

    /**
     * 转换为提供方Node。
     *
     * @param source 来源参数。
     * @param base 基础参数。
     * @return 返回转换后的提供方Node。
     */
    private Map<String, Object> toProviderNode(
            Map<String, Object> source, Map<String, Object> base) {
        String name = readString(source, "name");
        String baseUrl = readString(source, "baseUrl");
        String apiKey =
                source.containsKey("apiKey")
                        ? readString(source, "apiKey")
                        : readString(base, "apiKey");
        String defaultModel = readString(source, "defaultModel");
        String dialect = LlmProviderSupport.normalizeDialect(readString(source, "dialect"));
        Boolean supportsVision = readBooleanValue(source, "supportsVision", base, "supportsVision");
        String groupId = readString(source, "groupId");
        String groupLabel = readString(source, "groupLabel");
        String groupDescription = readString(source, "groupDescription");
        String displayDescription = readString(source, "displayDescription");

        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不能为空。");
        }
        assertSafeProviderBaseUrl(baseUrl);
        if (StrUtil.isBlank(dialect) || !LlmProviderSupport.isSupportedDialect(dialect)) {
            throw new IllegalArgumentException("不支持的 dialect：" + dialect);
        }
        if (SecretValueGuard.isPlaceholderSecret(apiKey)
                && !LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            throw new IllegalArgumentException("apiKey 不能使用示例或占位符密钥。");
        }
        if (StrUtil.isBlank(defaultModel) && StrUtil.isBlank(appConfig.getModel().getDefault())) {
            throw new IllegalArgumentException("defaultModel 不能为空。");
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", StrUtil.nullToEmpty(name).trim());
        result.put("baseUrl", StrUtil.nullToEmpty(baseUrl).trim());
        result.put("apiKey", StrUtil.nullToEmpty(apiKey).trim());
        result.put("defaultModel", StrUtil.nullToEmpty(defaultModel).trim());
        result.put("dialect", dialect);
        if (supportsVision != null) {
            result.put("supportsVision", supportsVision);
        }
        putIfNotBlank(result, "groupId", groupId);
        putIfNotBlank(result, "groupLabel", groupLabel);
        putIfNotBlank(result, "groupDescription", groupDescription);
        putIfNotBlank(result, "displayDescription", displayDescription);
        return result;
    }

    /**
     * 写入If Not Blank。
     *
     * @param result 结果响应或执行结果。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void putIfNotBlank(Map<String, Object> result, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            result.put(key, value.trim());
        }
    }

    /**
     * 读取Boolean Value。
     *
     * @param source 来源参数。
     * @param sourceKey 渠道来源键。
     * @param base 基础参数。
     * @param baseKey 基础键标识或键值。
     * @return 返回读取到的Boolean Value。
     */
    private Boolean readBooleanValue(
            Map<String, Object> source,
            String sourceKey,
            Map<String, Object> base,
            String baseKey) {
        if (source != null && source.containsKey(sourceKey)) {
            return toOptionalBoolean(source.get(sourceKey));
        }
        if (base != null && base.containsKey(baseKey)) {
            return toOptionalBoolean(base.get(baseKey));
        }
        return null;
    }

    /**
     * 转换为Optional Boolean。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回转换后的Optional Boolean。
     */
    private Boolean toOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.length() == 0) {
            return null;
        }
        return Boolean.valueOf(
                "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text));
    }

    /**
     * 执行assert安全提供方基础URL相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     */
    private void assertSafeProviderBaseUrl(String baseUrl) {
        try {
            LlmProviderSupport.validateBaseUrl(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "provider.baseUrl 配置无效："
                            + SecretRedactor.maskUrl(StrUtil.nullToEmpty(baseUrl))
                            + "，"
                            + e.getMessage(),
                    e);
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkAlwaysBlockedUrl(baseUrl);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "provider.baseUrl 被 URL 安全底线阻止："
                            + verdict.getMessage()
                            + " URL: "
                            + SecretRedactor.maskUrl(verdict.getUrl()));
        }
    }

    /**
     * 加载根用户For变更。
     *
     * @return 返回根用户For变更结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRootForMutation() {
        File configFile = new File(appConfig.getRuntime().getConfigFile());
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        if (configFile.exists()) {
            Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
            if (parsed instanceof Map) {
                root.putAll(sanitizeMap((Map<?, ?>) parsed));
            }
        }

        if (!(root.get("providers") instanceof Map)) {
            Map<String, Object> providers = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                    appConfig.getProviders().entrySet()) {
                providers.put(entry.getKey(), toProviderNode(entry.getValue()));
            }
            root.put("providers", providers);
        }
        if (!(root.get("model") instanceof Map)) {
            Map<String, Object> model = new LinkedHashMap<String, Object>();
            model.put("providerKey", appConfig.getModel().getProviderKey());
            model.put("default", appConfig.getModel().getDefault());
            root.put("model", model);
        }
        if (!(root.get("fallbackProviders") instanceof List)) {
            root.put(
                    "fallbackProviders",
                    new ArrayList<Object>(
                            cloneFallbackProviders(appConfig.getFallbackProviders())));
        }
        return root;
    }

    /**
     * 执行写入相关逻辑。
     *
     * @param root root 参数。
     */
    private void write(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(1);

        File configFile = new File(appConfig.getRuntime().getConfigFile());
        FileUtil.mkParentDirs(configFile);
        FileUtil.writeUtf8String(new Yaml(options).dump(root), configFile);
        gatewayRuntimeRefreshService.refreshConfigOnly();
    }

    /**
     * 读取Or Create Map。
     *
     * @param root root 参数。
     * @param key 配置键或映射键。
     * @return 返回读取到的Or Create Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateMap(Map<String, Object> root, String key) {
        Object current = root.get(key);
        if (current instanceof Map) {
            return (Map<String, Object>) current;
        }
        Map<String, Object> created = new LinkedHashMap<String, Object>();
        root.put(key, created);
        return created;
    }

    /**
     * 读取String。
     *
     * @param source 来源参数。
     * @param key 配置键或映射键。
     * @return 返回读取到的String。
     */
    private String readString(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return "";
        }
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 按顺序读取多个候选字符串键，用于兼容同一请求体中的前端字段命名差异。
     *
     * @param source 来源参数。
     * @param keys 候选键。
     * @return 第一项非空字符串。
     */
    private String readFirstString(Map<String, Object> source, String... keys) {
        if (keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = readString(source, key);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * 解析Models。
     *
     * @param body 请求体或消息正文内容。
     * @param dialect dialect 参数。
     * @return 返回解析后的Models。
     */
    @SuppressWarnings("unchecked")
    private List<String> parseModels(String body, String dialect) {
        List<String> models = new ArrayList<String>();
        Object parsed = ONode.deserialize(StrUtil.nullToEmpty(body), Object.class);
        if (!(parsed instanceof Map)) {
            return models;
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            Object items = root.get("models");
            if (items instanceof List) {
                for (Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        Map<String, Object> row = (Map<String, Object>) item;
                        addModel(models, row.get("name"));
                        addModel(models, row.get("model"));
                    }
                }
            }
            return models;
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(dialect)) {
            Object items = root.get("models");
            if (items instanceof List) {
                for (Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        Map<String, Object> row = (Map<String, Object>) item;
                        String name = StrUtil.nullToEmpty(String.valueOf(row.get("name"))).trim();
                        if (StrUtil.startWith(name, "models/")) {
                            name = name.substring("models/".length());
                        }
                        addModel(models, name);
                    }
                }
            }
            return models;
        }
        Object items = root.get("data");
        if (items instanceof List) {
            for (Object item : (List<?>) items) {
                if (item instanceof Map) {
                    addModel(models, ((Map<String, Object>) item).get("id"));
                }
            }
        }
        return models;
    }

    /**
     * 追加模型。
     *
     * @param models models 参数。
     * @param model 模型名称。
     */
    private void addModel(List<String> models, Object model) {
        String normalized = model == null ? "" : String.valueOf(model).trim();
        if (StrUtil.isNotBlank(normalized) && !models.contains(normalized)) {
            models.add(normalized);
        }
    }

    /**
     * 执行trimFor错误相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回trim For Error结果。
     */
    private String trimForError(String body) {
        String text =
                SecretRedactor.redact(StrUtil.nullToEmpty(body), 1000)
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .trim();
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

    /**
     * 清理Map。
     *
     * @param raw 原始输入值。
     * @return 返回Map结果。
     */
    private Map<String, Object> sanitizeMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map) {
                result.put(key, sanitizeMap((Map<?, ?>) value));
            } else if (value instanceof List) {
                result.put(key, sanitizeList((List<?>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 清理List。
     *
     * @param raw 原始输入值。
     * @return 返回List结果。
     */
    private List<Object> sanitizeList(List<?> raw) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : raw) {
            if (item instanceof Map) {
                result.add(sanitizeMap((Map<?, ?>) item));
            } else if (item instanceof List) {
                result.add(sanitizeList((List<?>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /** 承载模型列表缓存Entry相关状态和辅助逻辑。 */
    private static class ModelListCacheEntry {
        /** 记录模型列表缓存Entry中的URL。 */
        private final String url;

        /** 保存models集合，维持调用顺序或去重语义。 */
        private final List<String> models;

        /** 记录模型列表缓存Entry中的cached时间。 */
        private final long cachedAt;

        /**
         * 创建模型List缓存Entry实例，并注入运行所需依赖。
         *
         * @param url 待校验或访问的 URL。
         * @param models models 参数。
         * @param cachedAt cachedAt 参数。
         */
        private ModelListCacheEntry(String url, List<String> models, long cachedAt) {
            this.url = url;
            this.models =
                    models == null
                            ? Collections.<String>emptyList()
                            : new ArrayList<String>(models);
            this.cachedAt = cachedAt;
        }
    }

    /** 承载提供方Probe相关状态和辅助逻辑。 */
    private static class ProviderProbe {
        /** 记录提供方Probe中的提供方键。 */
        private final String providerKey;

        /** 记录提供方Probe中的基础URL。 */
        private final String baseUrl;

        /** 记录提供方Probe中的api键。 */
        private final String apiKey;

        /** 记录提供方Probe中的协议方言。 */
        private final String dialect;

        /** 记录提供方Probe中的模型名称，用于不提供模型列表接口的协议校验。 */
        private final String model;

        /**
         * 创建提供方Probe实例，并注入运行所需依赖。
         *
         * @param providerKey 提供方键标识或键值。
         * @param baseUrl 待校验或访问的地址参数。
         * @param apiKey api键标识或键值。
         * @param dialect dialect 参数。
         * @param model 模型名称。
         */
        private ProviderProbe(
                String providerKey, String baseUrl, String apiKey, String dialect, String model) {
            this.providerKey = providerKey;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.dialect = dialect;
            this.model = model;
        }
    }
}
