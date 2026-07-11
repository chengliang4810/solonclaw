package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import org.noear.snack4.ONode;

/**
 * 模型上下文长度在线目录服务，聚合 models.dev 和 OpenRouter 两个公开数据源。
 *
 * <p>对齐外部对标仓库的在线探测能力：models.dev 覆盖 109 个提供方 4000+ 模型，OpenRouter 作为兜底。 采用三层缓存（内存 1 小时 → 磁盘 1 小时 →
 * 网络失败降级过期磁盘 5 分钟），首次查询时同步拉取， 后续命中内存缓存。查询按 dialect + providerKey + baseUrl 推断对应的在线 provider 标识。
 */
public class ModelContextCatalogService {
    /** 日志记录器。 */
    private static final Log log = LogFactory.get();

    /** models.dev 聚合数据 API 地址，无需认证。 */
    private static final String MODELS_DEV_URL = "https://models.dev/api.json";

    /** OpenRouter 模型列表 API 地址，无需认证。 */
    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    /** 内存和磁盘缓存的 TTL，对齐外部对标仓库的 1 小时。 */
    private static final long CACHE_TTL_MILLIS = 60L * 60L * 1000L;

    /** 网络失败降级到过期磁盘缓存时的短 TTL，促使尽快重试网络。 */
    private static final long STALE_CACHE_TTL_MILLIS = 5L * 60L * 1000L;

    /** OpenRouter 响应中 context_length 缺失时的默认值。 */
    private static final int OPENROUTER_DEFAULT_CONTEXT_LENGTH = 128000;

    /** 磁盘缓存文件名。 */
    private static final String CACHE_FILE_NAME = "model-context-catalog.json";

    /** 应用配置，用于读取缓存目录和开关。 */
    private final AppConfig appConfig;

    /** models.dev 内存缓存：providerId → (modelId → contextLength)。 */
    private volatile Map<String, Map<String, Integer>> modelsDevCache;

    /** models.dev 内存缓存的时间戳（毫秒）。 */
    private volatile long modelsDevCacheTime;

    /** OpenRouter 内存缓存：modelKey → contextLength，含别名。 */
    private volatile Map<String, Integer> openRouterCache;

    /** OpenRouter 内存缓存的时间戳（毫秒）。 */
    private volatile long openRouterCacheTime;

    /**
     * 创建模型上下文长度在线目录服务。
     *
     * @param appConfig 应用运行配置。
     */
    public ModelContextCatalogService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 按 dialect、providerKey、baseUrl、model 查询在线目录中的上下文长度。
     *
     * @param dialect 协议方言（openai/anthropic/gemini/ollama）。
     * @param providerKey 提供方配置键（如 openrouter、openai、anthropic 等）。
     * @param baseUrl 提供方 baseUrl，用于识别 OpenRouter 等兼容提供方。
     * @param model 已规范化的模型名。
     * @return 命中时返回上下文长度，未命中返回空。
     */
    public OptionalInt getContextLength(
            String dialect, String providerKey, String baseUrl, String model) {
        if (StrUtil.isBlank(model) || !isRefreshEnabled()) {
            return OptionalInt.empty();
        }
        String normalizedModel = model.trim();
        String lowerModel = normalizedModel.toLowerCase();

        // 优先查 models.dev（覆盖面更广、更权威）
        OptionalInt fromModelsDev =
                lookupModelsDev(dialect, providerKey, baseUrl, normalizedModel, lowerModel);
        if (fromModelsDev.isPresent()) {
            return fromModelsDev;
        }

        // 兜底查 OpenRouter（覆盖聚合提供方和部分 models.dev 未收录模型）
        return lookupOpenRouter(normalizedModel, lowerModel);
    }

    /**
     * 判断是否启用在线刷新。
     *
     * @return 启用时返回 true。
     */
    private boolean isRefreshEnabled() {
        return appConfig != null
                && appConfig.getLlm() != null
                && appConfig.getLlm().isModelsDevRefreshEnabled();
    }

    /**
     * 在 models.dev 目录中查找模型上下文长度。
     *
     * @param dialect 协议方言。
     * @param providerKey 提供方配置键。
     * @param baseUrl 提供方 baseUrl。
     * @param model 已规范化的模型名。
     * @param lowerModel 小写模型名。
     * @return 命中时返回上下文长度。
     */
    private OptionalInt lookupModelsDev(
            String dialect, String providerKey, String baseUrl, String model, String lowerModel) {
        Map<String, Map<String, Integer>> cache = getModelsDevCache();
        if (cache.isEmpty()) {
            return OptionalInt.empty();
        }
        String providerId = resolveModelsDevProviderId(dialect, providerKey, baseUrl);
        if (providerId == null) {
            return OptionalInt.empty();
        }
        Map<String, Integer> providerModels = cache.get(providerId);
        if (providerModels == null || providerModels.isEmpty()) {
            return OptionalInt.empty();
        }
        // 精确匹配
        Integer exact = providerModels.get(model);
        if (exact != null) {
            return OptionalInt.of(exact);
        }
        // 大小写不敏感匹配
        for (Map.Entry<String, Integer> entry : providerModels.entrySet()) {
            if (entry.getKey().toLowerCase().equals(lowerModel)) {
                return OptionalInt.of(entry.getValue());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * 在 OpenRouter 目录中查找模型上下文长度。
     *
     * @param model 已规范化的模型名。
     * @param lowerModel 小写模型名。
     * @return 命中时返回上下文长度。
     */
    private OptionalInt lookupOpenRouter(String model, String lowerModel) {
        Map<String, Integer> cache = getOpenRouterCache();
        if (cache.isEmpty()) {
            return OptionalInt.empty();
        }
        Integer exact = cache.get(model);
        if (exact != null) {
            return OptionalInt.of(exact);
        }
        // 大小写不敏感匹配
        for (Map.Entry<String, Integer> entry : cache.entrySet()) {
            if (entry.getKey().toLowerCase().equals(lowerModel)) {
                return OptionalInt.of(entry.getValue());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * 把本项目的 dialect + providerKey + baseUrl 映射到 models.dev 的 provider 标识。
     *
     * @param dialect 协议方言。
     * @param providerKey 提供方配置键。
     * @param baseUrl 提供方 baseUrl。
     * @return 返回 models.dev provider id，无法映射时返回 null。
     */
    private String resolveModelsDevProviderId(String dialect, String providerKey, String baseUrl) {
        // OpenRouter 通过 host 识别
        if (baseUrl != null && baseUrl.toLowerCase().contains("openrouter.ai")) {
            return "openrouter";
        }
        // providerKey 直接匹配
        if (StrUtil.isNotBlank(providerKey)) {
            String mapped = mapProviderKeyToModelsDev(providerKey);
            if (mapped != null) {
                return mapped;
            }
        }
        // 按 dialect 映射
        if (StrUtil.isNotBlank(dialect)) {
            return mapDialectToModelsDev(dialect);
        }
        return null;
    }

    /**
     * 把 providerKey 映射到 models.dev provider id。
     *
     * @param providerKey 提供方配置键。
     * @return 返回 models.dev provider id。
     */
    private String mapProviderKeyToModelsDev(String providerKey) {
        String key = providerKey.trim().toLowerCase();
        switch (key) {
            case "openrouter":
                return "openrouter";
            case "anthropic":
                return "anthropic";
            case "openai":
            case "openai-codex":
                return "openai";
            case "deepseek":
                return "deepseek";
            case "alibaba":
            case "qwen-oauth":
            case "qwen":
                return "alibaba";
            case "minimax":
                return "minimax";
            case "gemini":
            case "google":
                return "google";
            case "xai":
                return "xai";
            case "kimi":
            case "moonshot":
                return "kimi-for-coding";
            case "mistral":
                return "mistral";
            case "groq":
                return "groq";
            case "together":
            case "togetherai":
                return "togetherai";
            case "fireworks":
                return "fireworks-ai";
            case "perplexity":
                return "perplexity";
            case "cohere":
                return "cohere";
            case "huggingface":
                return "huggingface";
            default:
                return null;
        }
    }

    /**
     * 把 dialect 映射到 models.dev provider id。
     *
     * @param dialect 协议方言。
     * @return 返回 models.dev provider id。
     */
    private String mapDialectToModelsDev(String dialect) {
        String d = dialect.trim().toLowerCase();
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(d)) {
            return "anthropic";
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(d)) {
            return "google";
        }
        if (LlmConstants.PROVIDER_OPENAI.equals(d)
                || LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(d)) {
            return "openai";
        }
        // ollama 在 models.dev 中是 ollama-cloud，本地 ollama 通常不在目录中
        return null;
    }

    /**
     * 获取 models.dev 内存缓存，过期时触发刷新。
     *
     * <p>首次无缓存时先尝试磁盘（快），磁盘也没有时返回空并触发异步网络加载，不阻塞调用方。 内存缓存过期时同步刷新（此时说明已有数据，刷新代价可接受）。
     *
     * @return 返回 models.dev 目录数据，可能为空（表示尚未加载完成）。
     */
    private Map<String, Map<String, Integer>> getModelsDevCache() {
        long now = System.currentTimeMillis();
        if (modelsDevCache != null
                && !modelsDevCache.isEmpty()
                && (now - modelsDevCacheTime) < CACHE_TTL_MILLIS) {
            return modelsDevCache;
        }
        synchronized (this) {
            // 内存缓存仍有效（双重检查）
            if (modelsDevCache != null
                    && !modelsDevCache.isEmpty()
                    && (System.currentTimeMillis() - modelsDevCacheTime) < CACHE_TTL_MILLIS) {
                return modelsDevCache;
            }
            Map<String, Map<String, Integer>> loaded = loadOrFetchModelsDev();
            if (loaded != null && !loaded.isEmpty()) {
                modelsDevCache = loaded;
                modelsDevCacheTime = System.currentTimeMillis();
            }
            return loaded != null ? loaded : Collections.<String, Map<String, Integer>>emptyMap();
        }
    }

    /**
     * 获取 OpenRouter 内存缓存，过期时触发刷新。
     *
     * @return 返回 OpenRouter 目录数据。
     */
    private Map<String, Integer> getOpenRouterCache() {
        long now = System.currentTimeMillis();
        if (openRouterCache != null
                && !openRouterCache.isEmpty()
                && (now - openRouterCacheTime) < CACHE_TTL_MILLIS) {
            return openRouterCache;
        }
        synchronized (this) {
            if (openRouterCache != null
                    && !openRouterCache.isEmpty()
                    && (System.currentTimeMillis() - openRouterCacheTime) < CACHE_TTL_MILLIS) {
                return openRouterCache;
            }
            Map<String, Integer> loaded = loadOrFetchOpenRouter();
            if (loaded != null && !loaded.isEmpty()) {
                openRouterCache = loaded;
                openRouterCacheTime = System.currentTimeMillis();
            }
            return loaded != null ? loaded : Collections.<String, Integer>emptyMap();
        }
    }

    /**
     * 加载或拉取 models.dev 数据，按三层缓存策略执行。
     *
     * <p>磁盘缓存有效时直接用磁盘；磁盘过期时用过期磁盘数据并触发异步刷新； 磁盘不存在时返回空并触发异步首次加载（不阻塞调用方）。
     *
     * @return 返回 models.dev 目录数据。
     */
    private Map<String, Map<String, Integer>> loadOrFetchModelsDev() {
        Optional<DiskCache> disk = loadDiskCache();
        if (disk.isPresent()) {
            DiskCache cache = disk.get();
            if (cache.modelsDev != null && !cache.modelsDev.isEmpty()) {
                if ((System.currentTimeMillis() - cache.timestamp) < CACHE_TTL_MILLIS) {
                    // 磁盘缓存有效，直接用
                    return cache.modelsDev;
                }
                // 磁盘过期，用过期数据并异步刷新
                triggerAsyncRefresh();
                return cache.modelsDev;
            }
        }
        // 磁盘不存在，触发异步首次加载，本次返回空
        triggerAsyncRefresh();
        return Collections.emptyMap();
    }

    /**
     * 加载或拉取 OpenRouter 数据。
     *
     * @return 返回 OpenRouter 目录数据。
     */
    private Map<String, Integer> loadOrFetchOpenRouter() {
        Optional<DiskCache> disk = loadDiskCache();
        if (disk.isPresent()) {
            DiskCache cache = disk.get();
            if (cache.openRouter != null && !cache.openRouter.isEmpty()) {
                if ((System.currentTimeMillis() - cache.timestamp) < CACHE_TTL_MILLIS) {
                    return cache.openRouter;
                }
                triggerAsyncRefresh();
                return cache.openRouter;
            }
        }
        triggerAsyncRefresh();
        return Collections.emptyMap();
    }

    /** 异步刷新标志，避免重复提交刷新任务。 */
    private final java.util.concurrent.atomic.AtomicBoolean asyncRefreshScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 异步刷新执行器，daemon 线程，不阻止 JVM 退出。 */
    private volatile java.util.concurrent.ExecutorService asyncRefreshExecutor;

    /** 触发异步网络刷新，避免阻塞查询调用方。 */
    private void triggerAsyncRefresh() {
        if (!asyncRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        getAsyncExecutor().submit(this::performAsyncRefresh);
    }

    /**
     * 获取或创建异步刷新执行器。
     *
     * @return 返回异步执行器。
     */
    private java.util.concurrent.ExecutorService getAsyncExecutor() {
        if (asyncRefreshExecutor == null) {
            synchronized (this) {
                if (asyncRefreshExecutor == null) {
                    asyncRefreshExecutor =
                            java.util.concurrent.Executors.newSingleThreadExecutor(
                                    r -> {
                                        Thread t = new Thread(r, "model-context-catalog-refresh");
                                        t.setDaemon(true);
                                        return t;
                                    });
                }
            }
        }
        return asyncRefreshExecutor;
    }

    /** 执行异步网络刷新，拉取 models.dev 和 OpenRouter 数据并写入缓存。 */
    private void performAsyncRefresh() {
        try {
            Map<String, Map<String, Integer>> freshModelsDev = fetchModelsDevFromRemote();
            Map<String, Integer> freshOpenRouter = fetchOpenRouterFromRemote();
            if (!freshModelsDev.isEmpty()) {
                modelsDevCache = freshModelsDev;
                modelsDevCacheTime = System.currentTimeMillis();
            }
            if (!freshOpenRouter.isEmpty()) {
                openRouterCache = freshOpenRouter;
                openRouterCacheTime = System.currentTimeMillis();
            }
            if (!freshModelsDev.isEmpty() || !freshOpenRouter.isEmpty()) {
                saveDiskCache(modelsDevCache, openRouterCache);
            }
        } catch (Exception e) {
            log.debug("异步刷新模型目录失败: {}", e.getMessage());
        } finally {
            asyncRefreshScheduled.set(false);
        }
    }

    /**
     * 从 models.dev 远端拉取并解析数据。
     *
     * @return 返回 providerId → (modelId → contextLength) 映射，失败返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> fetchModelsDevFromRemote() {
        try {
            HttpResponse response =
                    HttpRequest.get(MODELS_DEV_URL)
                            .timeout(15000)
                            .setFollowRedirects(false)
                            .execute();
            if (response.getStatus() != 200) {
                log.debug("models.dev 返回非 200 状态: {}", response.getStatus());
                return Collections.emptyMap();
            }
            String body = response.body();
            response.close();
            Object parsed = ONode.deserialize(body, Object.class);
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }
            Map<String, Map<String, Integer>> result = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> providerEntry :
                    ((Map<String, Object>) parsed).entrySet()) {
                if (!(providerEntry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> providerData = (Map<String, Object>) providerEntry.getValue();
                Object modelsObj = providerData.get("models");
                if (!(modelsObj instanceof Map)) {
                    continue;
                }
                Map<String, Integer> providerModels = new ConcurrentHashMap<>();
                for (Map.Entry<String, Object> modelEntry :
                        ((Map<String, Object>) modelsObj).entrySet()) {
                    Integer context = extractModelsDevContext(modelEntry.getValue());
                    if (context != null) {
                        providerModels.put(modelEntry.getKey(), context);
                    }
                }
                if (!providerModels.isEmpty()) {
                    result.put(providerEntry.getKey(), providerModels);
                }
            }
            log.debug("models.dev 拉取成功，共 {} 个提供方", result.size());
            return result;
        } catch (Exception e) {
            log.debug("models.dev 拉取失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 从 models.dev 模型条目中提取上下文长度。
     *
     * @param modelEntry 模型条目。
     * @return 返回上下文长度，无效时返回 null。
     */
    @SuppressWarnings("unchecked")
    private Integer extractModelsDevContext(Object modelEntry) {
        if (!(modelEntry instanceof Map)) {
            return null;
        }
        Object limitObj = ((Map<String, Object>) modelEntry).get("limit");
        if (!(limitObj instanceof Map)) {
            return null;
        }
        Object context = ((Map<String, Object>) limitObj).get("context");
        if (context instanceof Number) {
            int value = ((Number) context).intValue();
            return value > 0 ? value : null;
        }
        return null;
    }

    /**
     * 从 OpenRouter 远端拉取并解析数据。
     *
     * @return 返回 modelKey → contextLength 映射（含别名），失败返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> fetchOpenRouterFromRemote() {
        try {
            HttpResponse response =
                    HttpRequest.get(OPENROUTER_MODELS_URL)
                            .timeout(10000)
                            .setFollowRedirects(false)
                            .execute();
            if (response.getStatus() != 200) {
                log.debug("OpenRouter 返回非 200 状态: {}", response.getStatus());
                return Collections.emptyMap();
            }
            String body = response.body();
            response.close();
            Object parsed = ONode.deserialize(body, Object.class);
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }
            Object dataArray = ((Map<String, Object>) parsed).get("data");
            if (!(dataArray instanceof java.util.List)) {
                return Collections.emptyMap();
            }
            Map<String, Integer> result = new ConcurrentHashMap<>();
            for (Object item : (java.util.List<Object>) dataArray) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> model = (Map<String, Object>) item;
                Object contextLengthObj = model.get("context_length");
                int contextLength =
                        contextLengthObj instanceof Number
                                ? ((Number) contextLengthObj).intValue()
                                : OPENROUTER_DEFAULT_CONTEXT_LENGTH;
                if (contextLength <= 0) {
                    continue;
                }
                String id = StrUtil.nullToEmpty((String) model.get("id")).trim();
                if (StrUtil.isNotBlank(id)) {
                    result.put(id, contextLength);
                    // 裸模型名别名（去掉 provider 前缀，如 anthropic/claude-opus-4.6 → claude-opus-4.6）
                    int slash = id.indexOf('/');
                    if (slash >= 0 && slash < id.length() - 1) {
                        String bareName = id.substring(slash + 1);
                        if (StrUtil.isNotBlank(bareName)) {
                            result.putIfAbsent(bareName, contextLength);
                        }
                    }
                }
                String canonical = StrUtil.nullToEmpty((String) model.get("canonical_slug")).trim();
                if (StrUtil.isNotBlank(canonical)) {
                    result.putIfAbsent(canonical, contextLength);
                }
            }
            log.debug("OpenRouter 拉取成功，共 {} 个模型条目", result.size());
            return result;
        } catch (Exception e) {
            log.debug("OpenRouter 拉取失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 从磁盘加载合并缓存。
     *
     * @return 返回磁盘缓存，文件不存在或损坏时返回空。
     */
    @SuppressWarnings("unchecked")
    private Optional<DiskCache> loadDiskCache() {
        File file = cacheFile();
        if (file == null || !file.isFile()) {
            return Optional.empty();
        }
        try {
            String json = FileUtil.readUtf8String(file);
            Object parsed = ONode.deserialize(json, Object.class);
            if (!(parsed instanceof Map)) {
                return Optional.empty();
            }
            Map<String, Object> root = (Map<String, Object>) parsed;
            long timestamp = 0;
            Object tsObj = root.get("timestamp");
            if (tsObj instanceof Number) {
                timestamp = ((Number) tsObj).longValue();
            }
            Map<String, Map<String, Integer>> modelsDev =
                    parseModelsDevSection(root.get("models_dev"));
            Map<String, Integer> openRouter = parseOpenRouterSection(root.get("openrouter"));
            return Optional.of(new DiskCache(timestamp, modelsDev, openRouter));
        } catch (Exception e) {
            log.debug("读取模型目录磁盘缓存失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析磁盘缓存中的 models.dev 区段。
     *
     * @param section 原始数据。
     * @return 返回 models.dev 目录数据。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> parseModelsDevSection(Object section) {
        if (!(section instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, Integer>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Object> providerEntry : ((Map<String, Object>) section).entrySet()) {
            if (!(providerEntry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Integer> providerModels = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> modelEntry :
                    ((Map<String, Object>) providerEntry.getValue()).entrySet()) {
                if (modelEntry.getValue() instanceof Number) {
                    int value = ((Number) modelEntry.getValue()).intValue();
                    if (value > 0) {
                        providerModels.put(modelEntry.getKey(), value);
                    }
                }
            }
            if (!providerModels.isEmpty()) {
                result.put(providerEntry.getKey(), providerModels);
            }
        }
        return result;
    }

    /**
     * 解析磁盘缓存中的 OpenRouter 区段。
     *
     * @param section 原始数据。
     * @return 返回 OpenRouter 目录数据。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseOpenRouterSection(Object section) {
        if (!(section instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) section).entrySet()) {
            if (entry.getValue() instanceof Number) {
                int value = ((Number) entry.getValue()).intValue();
                if (value > 0) {
                    result.put(entry.getKey(), value);
                }
            }
        }
        return result;
    }

    /**
     * 将两个数据源的缓存写入磁盘。
     *
     * @param modelsDev models.dev 目录数据。
     * @param openRouter OpenRouter 目录数据。
     */
    private void saveDiskCache(
            Map<String, Map<String, Integer>> modelsDev, Map<String, Integer> openRouter) {
        File file = cacheFile();
        if (file == null) {
            return;
        }
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("timestamp", System.currentTimeMillis());
            root.put("models_dev", modelsDev != null ? modelsDev : Collections.emptyMap());
            root.put("openrouter", openRouter != null ? openRouter : Collections.emptyMap());
            String json = ONode.serialize(root);
            FileUtil.mkParentDirs(file);
            FileUtil.writeUtf8String(json, file);
        } catch (Exception e) {
            log.debug("写入模型目录磁盘缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 解析磁盘缓存文件路径。
     *
     * @return 返回缓存文件，缓存目录未配置时返回 null。
     */
    private File cacheFile() {
        if (appConfig == null
                || appConfig.getRuntime() == null
                || StrUtil.isBlank(appConfig.getRuntime().getCacheDir())) {
            return null;
        }
        return new File(appConfig.getRuntime().getCacheDir(), CACHE_FILE_NAME);
    }

    /** 磁盘缓存的数据载体。 */
    private static final class DiskCache {
        /** 缓存写入时间戳。 */
        private final long timestamp;

        /** models.dev 目录数据。 */
        private final Map<String, Map<String, Integer>> modelsDev;

        /** OpenRouter 目录数据。 */
        private final Map<String, Integer> openRouter;

        /**
         * 创建磁盘缓存数据载体。
         *
         * @param timestamp 缓存写入时间戳。
         * @param modelsDev models.dev 目录数据。
         * @param openRouter OpenRouter 目录数据。
         */
        DiskCache(
                long timestamp,
                Map<String, Map<String, Integer>> modelsDev,
                Map<String, Integer> openRouter) {
            this.timestamp = timestamp;
            this.modelsDev = modelsDev;
            this.openRouter = openRouter;
        }
    }
}
