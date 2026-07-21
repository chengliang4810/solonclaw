package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.profile.ProfileMutationLock;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import com.jimuqu.solon.claw.storage.repository.ReadOnlyCronJobReferenceRepository;
import com.jimuqu.solon.claw.support.HttpRedirectSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.ProviderDisplayGrouping;
import com.jimuqu.solon.claw.support.ProviderProfileService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.UrlOriginSupport;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileConfigFile;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Dashboard provider 配置管理服务。 */
public class DashboardProviderService {
    /** 模型列表缓存TTLMILLIS的统一常量值。 */
    private static final long MODEL_LIST_CACHE_TTL_MILLIS = 60L * 60L * 1000L;

    /** 模型列表缓存最大ENTRIES的统一常量值。 */
    private static final int MODEL_LIST_CACHE_MAX_ENTRIES = 64;

    /** Dashboard 支持配置默认模型的后台任务类别，顺序同时用于稳定展示。 */
    private static final List<String> TASK_MODEL_ROUTE_CATEGORIES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "monitor",
                            "background_review",
                            "curator",
                            "approval",
                            "compression",
                            "cron"));

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

    /** 运行时统一价格目录。 */
    private final PriceCatalog priceCatalog;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 解析 Dashboard 显式选择的 Profile；为空时保留当前行为。 */
    private final DashboardProfileContext profileContext;

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
        this(appConfig, gatewayRuntimeRefreshService, llmProviderService, null, null, null);
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
        this(
                appConfig,
                gatewayRuntimeRefreshService,
                llmProviderService,
                securityPolicyService,
                null,
                null);
    }

    /**
     * 创建控制台提供方服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param securityPolicyService 安全策略服务依赖。
     * @param modelMetadataService 模型元数据服务依赖，null 时内部创建。
     */
    public DashboardProviderService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService,
            SecurityPolicyService securityPolicyService,
            ModelMetadataService modelMetadataService) {
        this(
                appConfig,
                gatewayRuntimeRefreshService,
                llmProviderService,
                securityPolicyService,
                modelMetadataService,
                null);
    }

    /**
     * 创建支持 Profile 作用域的 Provider 管理服务。
     *
     * @param appConfig 当前 JVM 配置。
     * @param gatewayRuntimeRefreshService 当前 JVM 网关刷新服务。
     * @param llmProviderService 当前 JVM Provider 服务。
     * @param securityPolicyService URL 安全策略服务。
     * @param modelMetadataService 模型元数据服务。
     * @param profileContext Dashboard Profile 请求上下文。
     */
    public DashboardProviderService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService,
            SecurityPolicyService securityPolicyService,
            ModelMetadataService modelMetadataService,
            DashboardProfileContext profileContext) {
        this(
                appConfig,
                gatewayRuntimeRefreshService,
                llmProviderService,
                securityPolicyService,
                modelMetadataService,
                profileContext,
                null);
    }

    /**
     * 创建支持在线价格目录的 Provider 管理服务。
     *
     * @param appConfig 当前 JVM 配置。
     * @param gatewayRuntimeRefreshService 当前 JVM 网关刷新服务。
     * @param llmProviderService 当前 JVM Provider 服务。
     * @param securityPolicyService URL 安全策略服务。
     * @param modelMetadataService 模型元数据服务。
     * @param profileContext Dashboard Profile 请求上下文。
     * @param priceCatalog 运行时统一价格目录。
     */
    public DashboardProviderService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService,
            SecurityPolicyService securityPolicyService,
            ModelMetadataService modelMetadataService,
            DashboardProfileContext profileContext,
            PriceCatalog priceCatalog) {
        this.appConfig = appConfig;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.llmProviderService = llmProviderService;
        this.modelMetadataService =
                modelMetadataService != null
                        ? modelMetadataService
                        : new ModelMetadataService(appConfig);
        this.priceCatalog = priceCatalog == null ? PriceCatalog.forConfig(appConfig) : priceCatalog;
        this.providerProfileService =
                new ProviderProfileService(appConfig, llmProviderService, this.priceCatalog);
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
        this.profileContext = profileContext;
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
        result.put("taskModelRoutes", taskModelRoutes());
        result.put("dialectCatalog", dialectCatalog());
        result.put("providerProfiles", providerProfileService.listProfiles());
        return result;
    }

    /**
     * @param profile Profile 名。 @return 目标 Profile 的 Provider 列表。
     */
    public Map<String, Object> listProviders(String profile) {
        return forProfile(profile).listProviders();
    }

    /**
     * 列出主动监控、后台复盘、技能整理、审批、压缩和 Cron 的默认模型路由。
     *
     * @return 六类默认模型路由。
     */
    public Map<String, Object> listTaskModelRoutes() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("routes", taskModelRoutes());
        return result;
    }

    /**
     * 列出指定 Profile 的后台任务默认模型路由。
     *
     * @param profile Profile 名。
     * @return 六类默认模型路由。
     */
    public Map<String, Object> listTaskModelRoutes(String profile) {
        return forProfile(profile).listTaskModelRoutes();
    }

    /**
     * 更新六类后台任务默认模型路由；空 provider/model 表示沿用 Profile 主模型。
     *
     * @param data Dashboard 请求体。
     * @return 更新结果。
     */
    public Map<String, Object> updateTaskModelRoutes(Map<String, Object> data) {
        Object rawRoutes = data == null ? null : data.get("routes");
        if (!(rawRoutes instanceof Map)) {
            throw new IllegalArgumentException("routes 必须是对象。");
        }
        Map<String, Object> routes = sanitizeMap((Map<?, ?>) rawRoutes);
        return withMutationLock(
                () -> {
                    synchronized (configFileLock()) {
                        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
                        for (String category : TASK_MODEL_ROUTE_CATEGORIES) {
                            Map<String, Object> route = sanitizeMap(asMap(routes.get(category)));
                            String provider = readString(route, "provider");
                            String model = readString(route, "model");
                            validateTaskModelRoute(category, provider, model);
                            normalized.put(category, taskModelRoute(provider, model));
                        }
                        Map<String, Object> root = loadRootForMutation();
                        writeTaskModelRoutes(root, normalized);
                        write(root);
                        Map<String, Object> result = new LinkedHashMap<String, Object>();
                        result.put("routes", normalized);
                        return result;
                    }
                });
    }

    /**
     * 更新指定 Profile 的后台任务默认模型路由。
     *
     * @param data Dashboard 请求体。
     * @param profile Profile 名。
     * @return 更新结果。
     */
    public Map<String, Object> updateTaskModelRoutes(Map<String, Object> data, String profile) {
        return forProfile(profile).updateTaskModelRoutes(data);
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
        PriceCatalog currentPriceCatalog = priceCatalog.configuredFor(appConfig);
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
                    model, currentPriceCatalog.find(entry.getKey(), provider.getDefaultModel()));
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
     * @param profile Profile 名。 @return 目标 Profile 的模型列表。
     */
    public Map<String, Object> JimuquModels(String profile) {
        return forProfile(profile).JimuquModels();
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
     * @param profile Profile 名。 @return 目标 Profile 的模型配置健康状态。
     */
    public Map<String, Object> health(String profile) {
        return forProfile(profile).health();
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
        map.put("provenance", metadata.getProvenance());
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
        if (model == null) {
            return;
        }
        Map<String, Object> pricing = new LinkedHashMap<String, Object>();
        if (price == null) {
            pricing.put("available", Boolean.FALSE);
            pricing.put("status", "unknown");
            pricing.put("free", Boolean.FALSE);
            model.put("pricing", pricing);
            return;
        }
        pricing.put("available", Boolean.TRUE);
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
        pricing.put("status", free ? "free" : "priced");
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
        if (isNamedProfileConfig()) {
            return globalProviderService().createProvider(data);
        }
        return withMutationLock(
                () -> {
                    synchronized (configFileLock()) {
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
                });
    }

    /** 在指定 Profile 创建 Provider。 */
    public Map<String, Object> createProvider(Map<String, Object> data, String profile) {
        validateRequestedProfile(profile);
        return createProvider(data);
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
        if (isNamedProfileConfig()) {
            return globalProviderService().updateProvider(providerKey, data);
        }
        return withMutationLock(
                () -> {
                    synchronized (configFileLock()) {
                        ensureProviderKey(providerKey);
                        Map<String, Object> root = loadRootForMutation();
                        Map<String, Object> providers = getOrCreateMap(root, "providers");
                        Object existing = providers.get(providerKey);
                        if (!(existing instanceof Map)) {
                            throw new IllegalArgumentException("Provider 不存在：" + providerKey);
                        }
                        Map<String, Object> candidate =
                                toProviderNode(data, (Map<String, Object>) existing);
                        ensureProviderModelsPreserveReferences(providerKey, candidate, root);
                        providers.put(providerKey, candidate);
                        write(root);
                        return Collections.<String, Object>singletonMap("ok", true);
                    }
                });
    }

    /** 在指定 Profile 更新 Provider。 */
    public Map<String, Object> updateProvider(
            String providerKey, Map<String, Object> data, String profile) {
        validateRequestedProfile(profile);
        return updateProvider(providerKey, data);
    }

    /**
     * 删除提供方。
     *
     * @param providerKey 提供方键标识或键值。
     * @return 返回提供方结果。
     */
    public Map<String, Object> deleteProvider(String providerKey) {
        if (isNamedProfileConfig()) {
            return globalProviderService().deleteProvider(providerKey);
        }
        return withMutationLock(
                () -> {
                    synchronized (configFileLock()) {
                        ensureProviderKey(providerKey);
                        Map<String, Object> root = loadRootForMutation();
                        ensureProviderNotReferenced(root, providerKey, "根配置");
                        ensureProviderNotReferenced(appConfig, providerKey, "根运行配置");
                        ensureProviderNotReferencedByCron(appConfig, providerKey, "根 Profile");
                        ensureProviderNotReferencedByProfiles(providerKey);

                        Map<String, Object> providers = getOrCreateMap(root, "providers");
                        providers.remove(providerKey);
                        write(root);
                        return Collections.<String, Object>singletonMap("ok", true);
                    }
                });
    }

    /**
     * 删除全局 Provider 前检查全部命名 Profile 的主模型和备用链引用。
     *
     * @param providerKey 待删除的全局 Provider 键。
     */
    private void ensureProviderNotReferencedByProfiles(String providerKey) {
        Path profilesHome = globalRootHome().resolve("profiles");
        if (!Files.isDirectory(profilesHome)) {
            return;
        }
        try (java.util.stream.Stream<Path> profiles = Files.list(profilesHome)) {
            java.util.Iterator<Path> iterator = profiles.filter(Files::isDirectory).iterator();
            while (iterator.hasNext()) {
                Path profileHome = iterator.next();
                Map<String, Object> root =
                        new DashboardProfileConfigFile(profileHome.resolve("config.yml"))
                                .readRoot();
                String scope = "Profile " + profileHome.getFileName();
                ensureProviderNotReferenced(root, providerKey, scope);
                Props props = new Props();
                props.put("solonclaw.workspace", profileHome.toString());
                AppConfig profileConfig = AppConfig.loadDetached(props);
                ensureProviderNotReferenced(profileConfig, providerKey, scope + " 运行配置");
                ensureProviderNotReferencedByCron(profileConfig, providerKey, scope);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 Profile Provider 引用失败。", e);
        }
    }

    /** 校验原始 YAML 中的主模型、备用链和六类任务路由均未引用待删除 Provider。 */
    private void ensureProviderNotReferenced(
            Map<String, Object> root, String providerKey, String scope) {
        Map<String, Object> model = sanitizeMap(asMap(root.get("model")));
        if (StrUtil.equals(providerKey, readString(model, "providerKey"))
                || fallbackReferencesProvider(root.get("fallbackProviders"), providerKey)
                || taskModelRoutesReferenceProvider(root, providerKey)) {
            throw new IllegalArgumentException("Provider 正被" + scope + "使用：" + providerKey);
        }
    }

    /** 校验运行配置快照中的主模型、备用链和六类任务路由均未引用待删除 Provider。 */
    private void ensureProviderNotReferenced(AppConfig config, String providerKey, String scope) {
        if (config == null) {
            return;
        }
        if (StrUtil.equals(providerKey, config.getModel().getProviderKey())) {
            throw new IllegalArgumentException("Provider 正被" + scope + "主模型使用：" + providerKey);
        }
        for (AppConfig.FallbackProviderConfig fallback : config.getFallbackProviders()) {
            if (fallback != null && StrUtil.equals(providerKey, fallback.getProvider())) {
                throw new IllegalArgumentException(
                        "Provider 正被" + scope + " fallback 使用：" + providerKey);
            }
        }
        if (runtimeTaskModelRoutesReferenceProvider(config, providerKey)) {
            throw new IllegalArgumentException("Provider 正被" + scope + "后台任务模型使用：" + providerKey);
        }
    }

    /** 判断运行配置快照的六类后台任务是否引用指定 Provider。 */
    private boolean runtimeTaskModelRoutesReferenceProvider(AppConfig config, String providerKey) {
        return StrUtil.equals(providerKey, config.getProactive().getModelProvider())
                || StrUtil.equals(providerKey, config.getLearning().getModelProvider())
                || StrUtil.equals(providerKey, config.getCurator().getAiProvider())
                || StrUtil.equals(providerKey, config.getApprovals().getModelProvider())
                || StrUtil.equals(providerKey, config.getCompression().getSummaryProvider())
                || StrUtil.equals(providerKey, config.getScheduler().getDefaultProvider());
    }

    /**
     * 更新 Provider 模型清单前校验根配置和全部命名 Profile 的模型引用仍然有效。
     *
     * @param providerKey 待更新的 Provider 键。
     * @param candidate 更新后的 Provider 配置。
     * @param root 根工作区配置。
     */
    private void ensureProviderModelsPreserveReferences(
            String providerKey, Map<String, Object> candidate, Map<String, Object> root) {
        List<String> availableModels =
                normalizeModels(readString(candidate, "defaultModel"), candidate.get("models"));
        validateProviderModelReferences(root, providerKey, availableModels, "根配置");
        validateProviderModelReferences(appConfig, providerKey, availableModels, "根运行配置");
        validateCronModelReferences(appConfig, providerKey, availableModels, "根 Profile");

        Path profilesHome = globalRootHome().resolve("profiles");
        if (!Files.isDirectory(profilesHome)) {
            return;
        }
        try (java.util.stream.Stream<Path> profiles = Files.list(profilesHome)) {
            java.util.Iterator<Path> iterator = profiles.filter(Files::isDirectory).iterator();
            while (iterator.hasNext()) {
                Path profileHome = iterator.next();
                Map<String, Object> profileRoot =
                        new DashboardProfileConfigFile(profileHome.resolve("config.yml"))
                                .readRoot();
                validateProviderModelReferences(
                        profileRoot,
                        providerKey,
                        availableModels,
                        "Profile " + profileHome.getFileName());
                Props props = new Props();
                props.put("solonclaw.workspace", profileHome.toString());
                AppConfig profileConfig = AppConfig.loadDetached(props);
                validateProviderModelReferences(
                        profileConfig,
                        providerKey,
                        availableModels,
                        "Profile " + profileHome.getFileName() + " 运行配置");
                validateCronModelReferences(
                        profileConfig,
                        providerKey,
                        availableModels,
                        "Profile " + profileHome.getFileName());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 Profile 模型引用失败。", e);
        }
    }

    /**
     * 校验指定 Profile 的现有 Cron 固定模型仍在候选 Provider 清单中。
     *
     * @param config Profile 配置快照。
     * @param providerKey 待更新 Provider 键。
     * @param availableModels 更新后仍可用的模型。
     * @param scope Profile 来源说明。
     */
    private void validateCronModelReferences(
            AppConfig config, String providerKey, List<String> availableModels, String scope) {
        try {
            for (CronJobRecord job : readCronReferences(config, providerKey)) {
                String model = StrUtil.nullToEmpty(job.getModel()).trim();
                if (StrUtil.isBlank(model)) {
                    throw new IllegalArgumentException(
                            scope + " 定时任务 " + job.getJobId() + " 必须同时指定 provider 和 model。");
                }
                validateExplicitModelReference(
                        providerKey, model, availableModels, scope + " 定时任务 " + job.getJobId());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(scope + " 定时任务模型引用读取失败。", e);
        }
    }

    /**
     * 删除 Provider 前校验指定 Profile 的 Cron 数据库不存在该 Provider 的固定绑定。
     *
     * @param config Profile 配置快照。
     * @param providerKey 待删除 Provider 键。
     * @param scope Profile 来源说明。
     */
    private void ensureProviderNotReferencedByCron(
            AppConfig config, String providerKey, String scope) {
        try {
            for (CronJobRecord job : readCronReferences(config, providerKey)) {
                throw new IllegalArgumentException(
                        "Provider 正被 " + scope + " 定时任务使用：" + job.getJobId());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(scope + " 定时任务 Provider 引用读取失败。", e);
        }
    }

    /**
     * 以只读方式查询指定 Profile 的 Cron Provider 引用，不触发 SQLite schema 初始化或迁移。
     *
     * @param config Profile 配置快照。
     * @param providerKey Provider 键。
     * @return 固定绑定到该 Provider 的 Cron 引用。
     * @throws Exception 只读数据库查询失败。
     */
    private List<CronJobRecord> readCronReferences(AppConfig config, String providerKey)
            throws Exception {
        if (config == null || StrUtil.isBlank(config.getRuntime().getStateDb())) {
            return Collections.emptyList();
        }
        File stateDb = new File(config.getRuntime().getStateDb());
        if (!stateDb.isFile()) {
            return Collections.emptyList();
        }
        return new ReadOnlyCronJobReferenceRepository(stateDb.toPath()).listByProvider(providerKey);
    }

    /**
     * 校验一份根配置或 Profile 配置中的显式模型均存在于候选 Provider 清单。
     *
     * @param root 待检查配置根节点。
     * @param providerKey Provider 键。
     * @param availableModels 更新后仍可用的模型。
     * @param scope 引用来源说明。
     */
    private void validateProviderModelReferences(
            Map<String, Object> root,
            String providerKey,
            List<String> availableModels,
            String scope) {
        Map<String, Object> model = sanitizeMap(asMap(root.get("model")));
        if (StrUtil.equals(providerKey, readString(model, "providerKey"))) {
            validateExplicitModelReference(
                    providerKey, readString(model, "default"), availableModels, scope + "主模型");
        }

        Object rawFallbacks = root.get("fallbackProviders");
        if (rawFallbacks instanceof List) {
            for (Object item : (List<?>) rawFallbacks) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> fallback = sanitizeMap((Map<?, ?>) item);
                if (StrUtil.equals(providerKey, readString(fallback, "provider"))) {
                    validateExplicitModelReference(
                            providerKey,
                            readString(fallback, "model"),
                            availableModels,
                            scope + " fallback");
                }
            }
        }

        Map<String, Object> solonclaw = sanitizeMap(asMap(root.get("solonclaw")));
        Map<String, Object> skills = sanitizeMap(asMap(solonclaw.get("skills")));
        validateTaskModelReference(
                sanitizeMap(asMap(solonclaw.get("proactive"))),
                "modelProvider",
                "model",
                providerKey,
                availableModels,
                scope + " monitor");
        validateTaskModelReference(
                sanitizeMap(asMap(solonclaw.get("learning"))),
                "modelProvider",
                "model",
                providerKey,
                availableModels,
                scope + " background_review");
        validateTaskModelReference(
                sanitizeMap(asMap(skills.get("curator"))),
                "aiProvider",
                "aiModel",
                providerKey,
                availableModels,
                scope + " curator");
        validateTaskModelReference(
                sanitizeMap(asMap(root.get("approvals"))),
                "modelProvider",
                "model",
                providerKey,
                availableModels,
                scope + " approval");
        validateTaskModelReference(
                sanitizeMap(asMap(solonclaw.get("compression"))),
                "summaryProvider",
                "summaryModel",
                providerKey,
                availableModels,
                scope + " compression");
        validateTaskModelReference(
                sanitizeMap(asMap(solonclaw.get("scheduler"))),
                "defaultProvider",
                "defaultModel",
                providerKey,
                availableModels,
                scope + " cron");
    }

    /**
     * 校验运行配置快照中的主模型、备用链和六类任务模型均存在于候选 Provider 清单。
     *
     * @param config 待检查的运行配置快照。
     * @param providerKey Provider 键。
     * @param availableModels 更新后仍可用的模型。
     * @param scope 引用来源说明。
     */
    private void validateProviderModelReferences(
            AppConfig config, String providerKey, List<String> availableModels, String scope) {
        if (config == null) {
            return;
        }
        if (StrUtil.equals(providerKey, config.getModel().getProviderKey())) {
            validateExplicitModelReference(
                    providerKey, config.getModel().getDefault(), availableModels, scope + "主模型");
        }
        for (AppConfig.FallbackProviderConfig fallback : config.getFallbackProviders()) {
            if (fallback != null && StrUtil.equals(providerKey, fallback.getProvider())) {
                validateExplicitModelReference(
                        providerKey, fallback.getModel(), availableModels, scope + " fallback");
            }
        }
        validateTaskModelReference(
                config.getProactive().getModelProvider(),
                config.getProactive().getModel(),
                providerKey,
                availableModels,
                scope + " monitor");
        validateTaskModelReference(
                config.getLearning().getModelProvider(),
                config.getLearning().getModel(),
                providerKey,
                availableModels,
                scope + " background_review");
        validateTaskModelReference(
                config.getCurator().getAiProvider(),
                config.getCurator().getAiModel(),
                providerKey,
                availableModels,
                scope + " curator");
        validateTaskModelReference(
                config.getApprovals().getModelProvider(),
                config.getApprovals().getModel(),
                providerKey,
                availableModels,
                scope + " approval");
        validateTaskModelReference(
                config.getCompression().getSummaryProvider(),
                config.getCompression().getSummaryModel(),
                providerKey,
                availableModels,
                scope + " compression");
        validateTaskModelReference(
                config.getScheduler().getDefaultProvider(),
                config.getScheduler().getDefaultModel(),
                providerKey,
                availableModels,
                scope + " cron");
    }

    /**
     * 校验后台任务的成对模型引用仍在候选 Provider 清单中。
     *
     * @param node 任务配置节点。
     * @param providerField Provider 字段名。
     * @param modelField 模型字段名。
     * @param providerKey 待更新 Provider 键。
     * @param availableModels 更新后仍可用的模型。
     * @param scope 引用来源说明。
     */
    private void validateTaskModelReference(
            Map<String, Object> node,
            String providerField,
            String modelField,
            String providerKey,
            List<String> availableModels,
            String scope) {
        if (!StrUtil.equals(providerKey, readString(node, providerField))) {
            return;
        }
        String model = readString(node, modelField);
        if (StrUtil.isBlank(model)) {
            throw new IllegalArgumentException(scope + " 必须同时指定 provider 和 model。");
        }
        validateExplicitModelReference(providerKey, model, availableModels, scope);
    }

    /**
     * 校验运行配置中的成对任务模型引用仍在候选 Provider 清单中。
     *
     * @param routeProvider 任务显式 Provider。
     * @param routeModel 任务显式模型。
     * @param providerKey 待更新 Provider 键。
     * @param availableModels 更新后仍可用的模型。
     * @param scope 引用来源说明。
     */
    private void validateTaskModelReference(
            String routeProvider,
            String routeModel,
            String providerKey,
            List<String> availableModels,
            String scope) {
        if (!StrUtil.equals(providerKey, StrUtil.nullToEmpty(routeProvider).trim())) {
            return;
        }
        if (StrUtil.isBlank(routeModel)) {
            throw new IllegalArgumentException(scope + " 必须同时指定 provider 和 model。");
        }
        validateExplicitModelReference(providerKey, routeModel, availableModels, scope);
    }

    /**
     * 校验非空显式模型仍在候选 Provider 清单中；空主模型或 fallback 模型继续继承 Provider 默认模型。
     *
     * @param providerKey Provider 键。
     * @param model 显式模型名。
     * @param availableModels 更新后仍可用的模型。
     * @param scope 引用来源说明。
     */
    private void validateExplicitModelReference(
            String providerKey, String model, List<String> availableModels, String scope) {
        if (StrUtil.isBlank(model)) {
            return;
        }
        String normalizedModel = model.trim();
        if (!availableModels.contains(normalizedModel)) {
            throw new IllegalArgumentException(
                    scope + " 正在使用模型 " + normalizedModel + "，不能从 Provider " + providerKey + " 删除。");
        }
    }

    /** 返回全局 Provider 配置所在的根工作区。 */
    private Path globalRootHome() {
        if (profileContext != null) {
            return profileContext.profileManager().root().toAbsolutePath().normalize();
        }
        Path home =
                new File(appConfig.getRuntime().getHome()).toPath().toAbsolutePath().normalize();
        Path parent = home.getParent();
        if (parent != null && "profiles".equals(String.valueOf(parent.getFileName()))) {
            Path root = parent.getParent();
            return root == null ? home : root;
        }
        return home;
    }

    /** 判断备用链是否引用指定全局 Provider。 */
    private boolean fallbackReferencesProvider(Object rawFallbacks, String providerKey) {
        if (!(rawFallbacks instanceof List)) {
            return false;
        }
        for (Object item : (List<?>) rawFallbacks) {
            if (item instanceof Map
                    && StrUtil.equals(
                            providerKey, readString(sanitizeMap((Map<?, ?>) item), "provider"))) {
                return true;
            }
        }
        return false;
    }

    /** 把映射对象安全转换为通用映射，非映射值返回空映射。 */
    private Map<?, ?> asMap(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    /** 从指定 Profile 删除 Provider。 */
    public Map<String, Object> deleteProvider(String providerKey, String profile) {
        validateRequestedProfile(profile);
        return deleteProvider(providerKey);
    }

    /**
     * 校验 Dashboard 请求携带的 Profile 仍然存在；Provider 虽为全局资源，也不能忽略失效作用域。
     *
     * @param profile Dashboard 请求选择的 Profile。
     */
    private void validateRequestedProfile(String profile) {
        if (profileContext != null) {
            profileContext.resolve(profile);
        }
    }

    /**
     * 更新默认模型。
     *
     * @param providerKey 提供方键标识或键值。
     * @param model 模型名称。
     * @return 返回默认模型结果。
     */
    public Map<String, Object> updateDefaultModel(String providerKey, String model) {
        return withMutationLock(
                () -> {
                    synchronized (configFileLock()) {
                        String nextProviderKey =
                                StrUtil.isNotBlank(providerKey)
                                        ? providerKey.trim()
                                        : appConfig.getModel().getProviderKey();
                        if (!llmProviderService.hasProvider(nextProviderKey)) {
                            throw new IllegalArgumentException("未找到 provider：" + nextProviderKey);
                        }
                        validateProviderModel(nextProviderKey, model, true);

                        Map<String, Object> root = loadRootForMutation();
                        Map<String, Object> modelNode = getOrCreateMap(root, "model");
                        modelNode.put("providerKey", nextProviderKey);
                        modelNode.put("default", StrUtil.nullToEmpty(model).trim());
                        write(root);
                        return Collections.<String, Object>singletonMap("ok", true);
                    }
                });
    }

    /** 更新指定 Profile 的默认模型。 */
    public Map<String, Object> updateDefaultModel(
            String providerKey, String model, String profile) {
        return forProfile(profile).updateDefaultModel(providerKey, model);
    }

    /**
     * 更新兜底Providers。
     *
     * @param items items 参数。
     * @return 返回兜底Providers结果。
     */
    public Map<String, Object> updateFallbackProviders(List<Map<String, Object>> items) {
        return withMutationLock(
                () -> {
                    synchronized (configFileLock()) {
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
                                    validateProviderModel(provider, model, false);
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
                });
    }

    /** 更新指定 Profile 的故障切换链。 */
    public Map<String, Object> updateFallbackProviders(
            List<Map<String, Object>> items, String profile) {
        return forProfile(profile).updateFallbackProviders(items);
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

    /** 使用指定 Profile 的 Provider 配置列出远端模型。 */
    public Map<String, Object> listRemoteModels(Map<String, Object> data, String profile) {
        return forProfile(profile).listRemoteModels(data);
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

    /** 使用指定 Profile 的配置校验 Provider。 */
    public Map<String, Object> validateProvider(Map<String, Object> data, String profile) {
        return forProfile(profile).validateProvider(data);
    }

    /** 返回指定 Profile 的服务；非当前 Profile 不触发本 JVM 配置刷新。 */
    private DashboardProviderService forProfile(String profile) {
        if (profileContext == null) {
            return this;
        }
        DashboardProfileContext.Scope scope = profileContext.resolve(profile);
        if (scope.isCurrent()) {
            return this;
        }
        AppConfig scopedConfig = scope.getConfig();
        return new DashboardProviderService(
                scopedConfig,
                gatewayRuntimeRefreshService,
                DashboardProfileContext.snapshotProviderService(scopedConfig),
                new SecurityPolicyService(scopedConfig),
                new ModelMetadataService(scopedConfig),
                null);
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
        if (HttpRedirectSupport.isRedirectStatus(status)) {
            try {
                if (redirectCount >= 5) {
                    throw new IllegalStateException("获取模型列表重定向次数过多。");
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("获取模型列表重定向缺少 Location。");
                }
                String nextUrl =
                        HttpRedirectSupport.resolveLocation(url, location, "获取模型列表重定向 URL 无效");
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
        return LlmConstants.PROVIDER_OLLAMA.equals(LlmProviderSupport.normalizeDialect(dialect))
                ? Boolean.TRUE
                : null;
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
        item.put("models", configuredModels(provider));
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
        result.put("models", configuredModels(provider));
        result.put("dialect", StrUtil.nullToEmpty(provider.getDialect()).trim());
        if (provider.getSupportsVision() != null) {
            result.put("supportsVision", provider.getSupportsVision());
        }
        if (provider.getCapabilities() != null && !provider.getCapabilities().isEmpty()) {
            result.put(
                    "capabilities", new LinkedHashMap<String, Boolean>(provider.getCapabilities()));
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
        List<String> models =
                normalizeModels(
                        defaultModel,
                        source.containsKey("models")
                                ? source.get("models")
                                : (base == null ? null : base.get("models")));
        String dialect = LlmProviderSupport.normalizeDialect(readString(source, "dialect"));
        Boolean supportsVision = readBooleanValue(source, "supportsVision", base, "supportsVision");
        Map<String, Boolean> capabilities = readCapabilities(source, base);
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
        result.put("models", models);
        result.put("dialect", dialect);
        if (supportsVision != null) {
            result.put("supportsVision", supportsVision);
        }
        if (!capabilities.isEmpty()) {
            result.put("capabilities", capabilities);
        }
        putIfNotBlank(result, "groupId", groupId);
        putIfNotBlank(result, "groupLabel", groupLabel);
        putIfNotBlank(result, "groupDescription", groupDescription);
        putIfNotBlank(result, "displayDescription", displayDescription);
        return result;
    }

    /**
     * 返回 Provider 可选模型，默认模型始终排在首位且列表不含空值和重复项。
     *
     * @param provider Provider 配置。
     * @return 规范化模型列表。
     */
    private List<String> configuredModels(AppConfig.ProviderConfig provider) {
        return normalizeModels(
                provider == null ? "" : provider.getDefaultModel(),
                provider == null ? null : provider.getModels());
    }

    /**
     * 规范化请求或配置中的模型清单。
     *
     * @param defaultModel Provider 默认模型。
     * @param rawModels 原始模型列表或逗号分隔文本。
     * @return 默认模型优先的去重列表。
     */
    private List<String> normalizeModels(String defaultModel, Object rawModels) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (StrUtil.isNotBlank(defaultModel)) {
            result.add(defaultModel.trim());
        }
        if (rawModels instanceof List) {
            for (Object item : (List<?>) rawModels) {
                addNormalizedModel(result, item);
            }
        } else if (rawModels != null) {
            for (String item : String.valueOf(rawModels).split(",")) {
                addNormalizedModel(result, item);
            }
        }
        return new ArrayList<String>(result);
    }

    /**
     * 向模型集合加入一项非空模型。
     *
     * @param target 目标集合。
     * @param rawModel 原始模型值。
     */
    private void addNormalizedModel(LinkedHashSet<String> target, Object rawModel) {
        String model = rawModel == null ? "" : String.valueOf(rawModel).trim();
        if (StrUtil.isNotBlank(model)) {
            target.add(model);
        }
    }

    /**
     * 校验模型是否属于指定 Provider 的可选模型列表。
     *
     * @param providerKey Provider 键。
     * @param model 模型名。
     * @param allowBlank 是否允许模型留空。
     */
    private void validateProviderModel(String providerKey, String model, boolean allowBlank) {
        String normalizedModel = StrUtil.nullToEmpty(model).trim();
        if (StrUtil.isBlank(normalizedModel)) {
            if (allowBlank) {
                return;
            }
            throw new IllegalArgumentException("模型不能为空。");
        }
        AppConfig.ProviderConfig provider = appConfig.getProviders().get(providerKey);
        if (provider == null) {
            throw new IllegalArgumentException("未找到 provider：" + providerKey);
        }
        if (!configuredModels(provider).contains(normalizedModel)) {
            throw new IllegalArgumentException(
                    "模型未加入 provider " + providerKey + " 的模型列表：" + normalizedModel);
        }
    }

    /** 返回六类后台任务当前生效的显式模型路由。 */
    private Map<String, Object> taskModelRoutes() {
        Map<String, Object> routes = new LinkedHashMap<String, Object>();
        routes.put(
                "monitor",
                taskModelRoute(
                        appConfig.getProactive().getModelProvider(),
                        appConfig.getProactive().getModel()));
        routes.put(
                "background_review",
                taskModelRoute(
                        appConfig.getLearning().getModelProvider(),
                        appConfig.getLearning().getModel()));
        routes.put(
                "curator",
                taskModelRoute(
                        appConfig.getCurator().getAiProvider(),
                        appConfig.getCurator().getAiModel()));
        routes.put(
                "approval",
                taskModelRoute(
                        appConfig.getApprovals().getModelProvider(),
                        appConfig.getApprovals().getModel()));
        routes.put(
                "compression",
                taskModelRoute(
                        appConfig.getCompression().getSummaryProvider(),
                        appConfig.getCompression().getSummaryModel()));
        routes.put(
                "cron",
                taskModelRoute(
                        appConfig.getScheduler().getDefaultProvider(),
                        appConfig.getScheduler().getDefaultModel()));
        return routes;
    }

    /**
     * 创建一项模型路由响应。
     *
     * @param provider Provider 键。
     * @param model 模型名。
     * @return 路由响应。
     */
    private Map<String, Object> taskModelRoute(String provider, String model) {
        Map<String, Object> route = new LinkedHashMap<String, Object>();
        route.put("provider", StrUtil.nullToEmpty(provider).trim());
        route.put("model", StrUtil.nullToEmpty(model).trim());
        return route;
    }

    /**
     * 校验后台任务路由同时指定 Provider 和模型，并要求模型已登记。
     *
     * @param category 任务类别。
     * @param provider Provider 键。
     * @param model 模型名。
     */
    private void validateTaskModelRoute(String category, String provider, String model) {
        if (StrUtil.isBlank(provider) && StrUtil.isBlank(model)) {
            return;
        }
        if (StrUtil.isBlank(provider) || StrUtil.isBlank(model)) {
            throw new IllegalArgumentException(category + " 必须同时指定 provider 和 model。");
        }
        validateProviderModel(provider, model, false);
    }

    /**
     * 把统一 Dashboard 路由写回各业务已有配置段。
     *
     * @param root config.yml 根节点。
     * @param routes 已校验路由。
     */
    private void writeTaskModelRoutes(Map<String, Object> root, Map<String, Object> routes) {
        Map<String, Object> solonclaw = getOrCreateMap(root, "solonclaw");
        writeModelRoute(
                getOrCreateMap(solonclaw, "proactive"),
                sanitizeMap(asMap(routes.get("monitor"))),
                "modelProvider",
                "model");
        writeModelRoute(
                getOrCreateMap(solonclaw, "learning"),
                sanitizeMap(asMap(routes.get("background_review"))),
                "modelProvider",
                "model");
        Map<String, Object> skills = getOrCreateMap(solonclaw, "skills");
        writeModelRoute(
                getOrCreateMap(skills, "curator"),
                sanitizeMap(asMap(routes.get("curator"))),
                "aiProvider",
                "aiModel");
        writeModelRoute(
                getOrCreateMap(root, "approvals"),
                sanitizeMap(asMap(routes.get("approval"))),
                "modelProvider",
                "model");
        writeModelRoute(
                getOrCreateMap(solonclaw, "compression"),
                sanitizeMap(asMap(routes.get("compression"))),
                "summaryProvider",
                "summaryModel");
        writeModelRoute(
                getOrCreateMap(solonclaw, "scheduler"),
                sanitizeMap(asMap(routes.get("cron"))),
                "defaultProvider",
                "defaultModel");
    }

    /**
     * 写入或清除一项业务配置中的 Provider/模型字段。
     *
     * @param target 目标配置段。
     * @param route 路由值。
     * @param providerField Provider 字段名。
     * @param modelField 模型字段名。
     */
    private void writeModelRoute(
            Map<String, Object> target,
            Map<String, Object> route,
            String providerField,
            String modelField) {
        putOrRemove(target, providerField, readString(route, "provider"));
        putOrRemove(target, modelField, readString(route, "model"));
    }

    /**
     * 非空字符串写入配置，空字符串删除配置键以恢复继承语义。
     *
     * @param target 目标配置段。
     * @param key 配置键。
     * @param value 配置值。
     */
    private void putOrRemove(Map<String, Object> target, String key, String value) {
        if (StrUtil.isBlank(value)) {
            target.remove(key);
        } else {
            target.put(key, value.trim());
        }
    }

    /** 判断当前运行配置的六类后台任务是否引用指定 Provider。 */
    private boolean taskModelRoutesReferenceProvider(String providerKey) {
        for (Object value : taskModelRoutes().values()) {
            if (value instanceof Map
                    && StrUtil.equals(
                            providerKey, readString(sanitizeMap((Map<?, ?>) value), "provider"))) {
                return true;
            }
        }
        return false;
    }

    /** 判断原始 Profile 配置中的六类后台任务是否引用指定 Provider。 */
    private boolean taskModelRoutesReferenceProvider(Map<String, Object> root, String providerKey) {
        Map<String, Object> solonclaw = sanitizeMap(asMap(root.get("solonclaw")));
        Map<String, Object> skills = sanitizeMap(asMap(solonclaw.get("skills")));
        return routeReferencesProvider(
                        sanitizeMap(asMap(solonclaw.get("proactive"))),
                        "modelProvider",
                        providerKey)
                || routeReferencesProvider(
                        sanitizeMap(asMap(solonclaw.get("learning"))), "modelProvider", providerKey)
                || routeReferencesProvider(
                        sanitizeMap(asMap(skills.get("curator"))), "aiProvider", providerKey)
                || routeReferencesProvider(
                        sanitizeMap(asMap(root.get("approvals"))), "modelProvider", providerKey)
                || routeReferencesProvider(
                        sanitizeMap(asMap(solonclaw.get("compression"))),
                        "summaryProvider",
                        providerKey)
                || routeReferencesProvider(
                        sanitizeMap(asMap(solonclaw.get("scheduler"))),
                        "defaultProvider",
                        providerKey);
    }

    /** 判断一个业务配置段的 Provider 字段是否匹配目标 Provider。 */
    private boolean routeReferencesProvider(
            Map<String, Object> node, String field, String providerKey) {
        return StrUtil.equals(providerKey, readString(node, field));
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
     * 合并 Dashboard provider 编辑请求中的显式能力元数据。
     *
     * @param source 本次提交的 provider 数据。
     * @param base 已保存的 provider 数据。
     * @return 返回规范化后的能力映射。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Boolean> readCapabilities(
            Map<String, Object> source, Map<String, Object> base) {
        Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
        Object baseValue = base == null ? null : base.get("capabilities");
        Object sourceValue = source == null ? null : source.get("capabilities");
        appendCapabilities(result, baseValue);
        if (source != null && source.containsKey("capabilities")) {
            result.clear();
            appendCapabilities(result, sourceValue);
        }
        return result;
    }

    /**
     * 将原始能力对象中的布尔字段追加到目标映射。
     *
     * @param target 目标能力映射。
     * @param raw 原始能力对象。
     */
    private void appendCapabilities(Map<String, Boolean> target, Object raw) {
        if (!(raw instanceof Map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            Boolean value = toOptionalBoolean(entry.getValue());
            if (StrUtil.isNotBlank(key) && value != null) {
                target.put(key, value);
            }
        }
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

        if (isNamedProfileConfig()) {
            root.remove("providers");
            return root;
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
     * 判断当前写入目标是否为命名 Profile；命名 Profile 只能保存 Provider/模型引用，不能复制全局注册表。
     *
     * @return 当前配置文件位于根工作区 profiles 目录下时返回 true。
     */
    private boolean isNamedProfileConfig() {
        Path home =
                new File(appConfig.getRuntime().getHome()).toPath().toAbsolutePath().normalize();
        return !home.equals(globalRootHome());
    }

    /**
     * 创建绑定根工作区 Provider 注册表的服务，供命名 Profile 上的 Provider 增删改复用。
     *
     * @return 根工作区 Provider 管理服务；当前已是根工作区时返回自身。
     */
    private DashboardProviderService globalProviderService() {
        if (!isNamedProfileConfig()) {
            return this;
        }
        Props props = new Props();
        props.put("solonclaw.workspace", globalRootHome().toString());
        AppConfig globalConfig = AppConfig.loadDetached(props);
        return new DashboardProviderService(
                globalConfig,
                gatewayRuntimeRefreshService,
                new LlmProviderService(globalConfig),
                new SecurityPolicyService(globalConfig),
                new ModelMetadataService(globalConfig),
                profileContext,
                priceCatalog);
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
        synchronized (DashboardProfileConfigFile.lockFor(configFile.toPath())) {
            FileUtil.mkParentDirs(configFile);
            File temp = new File(configFile.getParentFile(), configFile.getName() + ".tmp");
            FileUtil.writeUtf8String(new Yaml(options).dump(root), temp);
            try {
                try {
                    Files.move(
                            temp.toPath(),
                            configFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicMoveFailed) {
                    Files.move(
                            temp.toPath(),
                            configFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write provider config file.", e);
            }
        }
        if (gatewayRuntimeRefreshService != null) {
            if (isNamedProfileConfig()) {
                gatewayRuntimeRefreshService.refreshProfileConfigOnly(
                        ProfileRuntimeIdentity.resolve(appConfig));
            } else {
                gatewayRuntimeRefreshService.refreshConfigOnly();
                gatewayRuntimeRefreshService.refreshProfileConfigsOnly();
            }
        }
    }

    /** 返回当前 Provider 配置文件的共享写锁。 */
    private Object configFileLock() {
        return DashboardProfileConfigFile.lockFor(
                new File(appConfig.getRuntime().getConfigFile()).toPath());
    }

    /**
     * 在 Profile 根目录跨进程锁内执行动作。
     *
     * @param action 受保护动作。
     * @param <T> 返回值类型。
     * @return 动作结果。
     * @throws Exception 加锁或动作执行失败时抛出异常。
     */
    private <T> T withMutationLock(ProfileMutationLock.Action<T> action) {
        try {
            return new ProfileMutationLock(appConfig).withLock(action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to lock provider and cron mutations.", e);
        }
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
