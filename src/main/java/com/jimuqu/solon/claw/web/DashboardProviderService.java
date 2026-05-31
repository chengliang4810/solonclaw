package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.ProviderDisplayGrouping;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Dashboard provider 配置管理服务。 */
public class DashboardProviderService {
    private static final long MODEL_LIST_CACHE_TTL_MILLIS = 60L * 60L * 1000L;
    private static final int MODEL_LIST_CACHE_MAX_ENTRIES = 64;

    private final AppConfig appConfig;
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;
    private final LlmProviderService llmProviderService;
    private final ModelMetadataService modelMetadataService;
    private final SecurityPolicyService securityPolicyService;
    private final Map<String, ModelListCacheEntry> modelListCache =
            new LinkedHashMap<String, ModelListCacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, ModelListCacheEntry> eldest) {
                    return size() > MODEL_LIST_CACHE_MAX_ENTRIES;
                }
            };

    public DashboardProviderService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService) {
        this(appConfig, gatewayRuntimeRefreshService, llmProviderService, null);
    }

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
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
    }

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
        return result;
    }

    public Map<String, Object> JimuquModels() {
        Map<String, Object> result = listProviders();
        List<Map<String, Object>> models = new ArrayList<Map<String, Object>>();
        List<ProviderDisplayGrouping.Item> groupItems =
                new ArrayList<ProviderDisplayGrouping.Item>();
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
            model.put("role", entry.getKey().equals(appConfig.getModel().getProviderKey()) ? "primary" : "auxiliary");
            model.put("status", providerStatus(provider));
            model.put("metadata", metadataMap(metadata));
            model.put("aliases", metadata.getAliases());
            model.put("context_window", metadata.getContextWindow());
            model.put("max_output", metadata.getMaxOutput());
            model.put("reasoning_effort", appConfig.getLlm().getReasoningEffort());
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

    private Map<String, Object> metadataMap(ModelMetadata metadata) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("provider", metadata.getProvider());
        map.put("model", metadata.getModel());
        map.put("dialect", metadata.getDialect());
        map.put("context_window", metadata.getContextWindow());
        map.put("max_output", metadata.getMaxOutput());
        map.put("tool_calling", Boolean.valueOf(metadata.isSupportsTools()));
        map.put("vision", Boolean.valueOf(metadata.isSupportsVision()));
        map.put("streaming", Boolean.valueOf(metadata.isSupportsStreaming()));
        map.put("reasoning", Boolean.valueOf(metadata.isSupportsReasoning()));
        map.put("prompt_cache", Boolean.valueOf(metadata.isSupportsPromptCache()));
        map.put("default_model", Boolean.valueOf(metadata.isDefaultModel()));
        map.put("supported", Boolean.valueOf(metadata.isSupported()));
        return map;
    }

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

    public Map<String, Object> listRemoteModels(Map<String, Object> data) {
        String providerKey = readString(data, "providerKey");
        String baseUrl = readString(data, "baseUrl");
        String apiKey = readString(data, "apiKey");
        String dialect = LlmProviderSupport.normalizeDialect(readString(data, "dialect"));
        AppConfig.ProviderConfig provider =
                StrUtil.isBlank(providerKey) ? null : appConfig.getProviders().get(providerKey);
        if (provider != null) {
            baseUrl = StrUtil.blankToDefault(baseUrl, provider.getBaseUrl());
            apiKey = StrUtil.blankToDefault(apiKey, provider.getApiKey());
            dialect =
                    LlmProviderSupport.normalizeDialect(
                            StrUtil.blankToDefault(dialect, provider.getDialect()));
        }
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不能为空。");
        }
        assertSafeProviderBaseUrl(baseUrl);
        if (StrUtil.isBlank(dialect) || !LlmProviderSupport.isSupportedDialect(dialect)) {
            throw new IllegalArgumentException("不支持的 dialect：" + dialect);
        }

        String url = LlmProviderSupport.buildModelListUrl(baseUrl, dialect);
        assertSafeProviderUrl(url);
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
                            SecretRedactor.maskUrl(url), parseModels(body, dialect), currentTimeMillis());
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

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    protected long modelListCacheTtlMillis() {
        return MODEL_LIST_CACHE_TTL_MILLIS;
    }

    private ModelListCacheEntry cachedModelList(String cacheKey) {
        synchronized (modelListCache) {
            return modelListCache.get(cacheKey);
        }
    }

    private void cacheModelList(String cacheKey, ModelListCacheEntry entry) {
        synchronized (modelListCache) {
            modelListCache.put(cacheKey, entry);
        }
    }

    private boolean isFresh(ModelListCacheEntry entry) {
        if (entry == null) {
            return false;
        }
        long ttl = Math.max(0L, modelListCacheTtlMillis());
        return ttl > 0L && currentTimeMillis() - entry.cachedAt <= ttl;
    }

    private String modelListCacheKey(String providerKey, String url, String dialect, String apiKey) {
        return StrUtil.nullToEmpty(providerKey)
                + "|"
                + StrUtil.nullToEmpty(dialect)
                + "|"
                + StrUtil.nullToEmpty(url)
                + "|key:"
                + Integer.toHexString(StrUtil.nullToEmpty(apiKey).hashCode());
    }

    private Map<String, Object> modelListResult(ModelListCacheEntry entry, String cacheStatus) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("url", entry.url);
        result.put("models", new ArrayList<String>(entry.models));
        result.put("cache", cacheStatus);
        return result;
    }

    protected HttpResponse executeModelListRequest(
            String url, String apiKey, String dialect, int redirectCount) {
        return executeModelListRequest(url, url, apiKey, dialect, redirectCount);
    }

    private HttpResponse executeModelListRequest(
            String initialUrl, String url, String apiKey, String dialect, int redirectCount) {
        assertSafeProviderUrl(url);
        HttpRequest request = HttpRequest.get(url).timeout(15000).setFollowRedirects(false);
        if (StrUtil.isNotBlank(apiKey) && shouldForwardCredentials(initialUrl, url)) {
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

    private void assertSafeProviderUrl(String url) {
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Provider model list URL blocked: "
                            + com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(verdict.getUrl())
                            + " ("
                            + verdict.getMessage()
                            + ")");
        }
    }

    private boolean shouldForwardCredentials(String initialUrl, String url) {
        try {
            URI initial = URI.create(initialUrl);
            URI current = URI.create(url);
            return StrUtil.equalsIgnoreCase(initial.getScheme(), current.getScheme())
                    && StrUtil.equalsIgnoreCase(initial.getHost(), current.getHost())
                    && effectivePort(initial) == effectivePort(current);
        } catch (Exception e) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("获取模型列表重定向 URL 无效。", e);
        }
    }

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
        appendProviderDisplay(
                item, ProviderDisplayGrouping.providerDisplay(providerKey, provider));
        return item;
    }

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

    private void ensureProviderKey(String providerKey) {
        if (StrUtil.isBlank(providerKey)) {
            throw new IllegalArgumentException("providerKey 不能为空。");
        }
    }

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

    private void putIfNotBlank(Map<String, Object> result, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            result.put(key, value.trim());
        }
    }

    private Boolean readBooleanValue(
            Map<String, Object> source, String sourceKey, Map<String, Object> base, String baseKey) {
        if (source != null && source.containsKey(sourceKey)) {
            return toOptionalBoolean(source.get(sourceKey));
        }
        if (base != null && base.containsKey(baseKey)) {
            return toOptionalBoolean(base.get(baseKey));
        }
        return null;
    }

    private Boolean toOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.length() == 0) {
            return null;
        }
        return Boolean.valueOf(
                "true".equalsIgnoreCase(text)
                        || "1".equals(text)
                        || "yes".equalsIgnoreCase(text));
    }

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

    private String readString(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return "";
        }
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

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

    private void addModel(List<String> models, Object model) {
        String normalized = model == null ? "" : String.valueOf(model).trim();
        if (StrUtil.isNotBlank(normalized) && !models.contains(normalized)) {
            models.add(normalized);
        }
    }

    private String trimForError(String body) {
        String text =
                SecretRedactor.redact(StrUtil.nullToEmpty(body), 1000)
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .trim();
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

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

    private static class ModelListCacheEntry {
        private final String url;
        private final List<String> models;
        private final long cachedAt;

        private ModelListCacheEntry(String url, List<String> models, long cachedAt) {
            this.url = url;
            this.models =
                    models == null
                            ? Collections.<String>emptyList()
                            : new ArrayList<String>(models);
            this.cachedAt = cachedAt;
        }
    }
}
