package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.noear.snack4.ONode;

/**
 * 模型上下文长度与价格在线目录服务，聚合 models.dev 和 OpenRouter 两个公开数据源。
 *
 * <p>对齐外部对标仓库的在线探测能力：models.dev 覆盖 109 个提供方 4000+ 模型，OpenRouter 作为兜底。上下文长度采用三层缓存（内存 1 小时 → 磁盘 1 小时
 * → 网络失败降级过期磁盘 5 分钟），价格复用同一次异步刷新并保存在内存中。查询按 dialect + providerKey + baseUrl 推断对应的在线 provider 标识。
 */
public class ModelContextCatalogService implements AutoCloseable {
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

    /** 首次价格查询等待双源刷新完成的最长时间，覆盖两个 HTTP 请求的超时总和。 */
    private static final long INITIAL_PRICE_REFRESH_WAIT_MILLIS = 30000L;

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

    /** models.dev 价格缓存：providerId → (modelId → price)。 */
    private volatile Map<String, Map<String, ModelPrice>> modelsDevPriceCache;

    /** models.dev 价格缓存时间戳（毫秒）。 */
    private volatile long modelsDevPriceCacheTime;

    /** OpenRouter 价格缓存：modelId → price。 */
    private volatile Map<String, ModelPrice> openRouterPriceCache;

    /** OpenRouter 价格缓存时间戳（毫秒）。 */
    private volatile long openRouterPriceCacheTime;

    /** 首轮双源刷新完成信号，避免冷启动时过早把模型标记为未计价。 */
    private final CountDownLatch initialRefreshFinished = new CountDownLatch(1);

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
     * 按 Provider 和模型名查询在线价格；Provider 未命中时按模型名遍历目录并返回第一条精确结果。
     *
     * @param providerKey 用户配置的 Provider 键。
     * @param model 模型名称。
     * @return 命中时返回价格副本，否则返回 null。
     */
    public ModelPrice getPrice(String providerKey, String model) {
        if (StrUtil.isBlank(model) || !isRefreshEnabled()) {
            return null;
        }
        awaitInitialPriceRefresh();
        String normalizedModel = model.trim();
        AppConfig.ProviderConfig provider =
                appConfig == null || appConfig.getProviders() == null
                        ? null
                        : appConfig.getProviders().get(providerKey);
        String providerId =
                resolveModelsDevProviderId(
                        provider == null ? null : provider.getDialect(),
                        providerKey,
                        provider == null ? null : provider.getBaseUrl());

        Map<String, Map<String, ModelPrice>> modelsDevPrices = getModelsDevPriceCache();
        ModelPrice matched = findProviderPrice(modelsDevPrices, providerId, normalizedModel);
        if (matched == null) {
            matched = findFirstPrice(modelsDevPrices, normalizedModel);
        }
        if (matched == null) {
            matched = findPrice(getOpenRouterPriceCache(), normalizedModel);
        }
        return matched == null ? null : matched.copy();
    }

    /** 启动一次非阻塞目录刷新，供应用启动阶段预热价格与上下文缓存。 */
    public void refreshAsync() {
        if (isRefreshEnabled()) {
            triggerAsyncRefresh();
        }
    }

    /** 首次价格查询等待已启动的双源刷新；超时或中断时按当前缓存继续。 */
    private void awaitInitialPriceRefresh() {
        if (closed.get() || initialRefreshFinished.getCount() == 0L) {
            return;
        }
        refreshAsync();
        try {
            initialRefreshFinished.await(INITIAL_PRICE_REFRESH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在指定 models.dev Provider 中查找价格。
     *
     * @param catalog models.dev 价格目录。
     * @param providerId models.dev Provider 标识。
     * @param model 模型名称。
     * @return 命中价格或 null。
     */
    private ModelPrice findProviderPrice(
            Map<String, Map<String, ModelPrice>> catalog, String providerId, String model) {
        if (catalog == null || StrUtil.isBlank(providerId)) {
            return null;
        }
        return findPrice(catalog.get(providerId), model);
    }

    /**
     * 按 models.dev 返回顺序遍历所有 Provider，返回第一条模型名精确匹配的价格。
     *
     * @param catalog models.dev 价格目录。
     * @param model 模型名称。
     * @return 第一条命中价格或 null。
     */
    private ModelPrice findFirstPrice(Map<String, Map<String, ModelPrice>> catalog, String model) {
        if (catalog == null || catalog.isEmpty()) {
            return null;
        }
        for (Map<String, ModelPrice> providerPrices : catalog.values()) {
            ModelPrice matched = findPrice(providerPrices, model);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    /**
     * 在单个价格映射中按模型全名或去除首段路由前缀后的名称做精确匹配。
     *
     * @param prices 单个来源的价格映射。
     * @param model 模型名称。
     * @return 命中价格或 null。
     */
    private ModelPrice findPrice(Map<String, ModelPrice> prices, String model) {
        if (prices == null || prices.isEmpty() || StrUtil.isBlank(model)) {
            return null;
        }
        String requested = normalizeModelKey(model);
        String requestedBare = bareModelKey(requested);
        for (ModelPrice price : prices.values()) {
            if (price == null || StrUtil.isBlank(price.getModel())) {
                continue;
            }
            String candidate = normalizeModelKey(price.getModel());
            if (requested.equals(candidate)
                    || requestedBare.equals(candidate)
                    || requested.equals(bareModelKey(candidate))
                    || requestedBare.equals(bareModelKey(candidate))) {
                return price;
            }
        }
        return null;
    }

    /** 规范化在线目录模型键。 */
    private String normalizeModelKey(String model) {
        return StrUtil.nullToEmpty(model).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 去除模型键首段路由前缀。 */
    private String bareModelKey(String model) {
        String value = normalizeModelKey(model);
        int slash = value.indexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
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
            case "stepfun":
                return "stepfun";
            case "stepfun-ai":
                return "stepfun-ai";
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
     * 获取 models.dev 价格缓存；无缓存时复用目录刷新任务，不阻塞当前请求。
     *
     * @return models.dev 价格目录。
     */
    private Map<String, Map<String, ModelPrice>> getModelsDevPriceCache() {
        getModelsDevCache();
        Map<String, Map<String, ModelPrice>> cached = modelsDevPriceCache;
        if (cached == null || cached.isEmpty()) {
            triggerAsyncRefresh();
            return Collections.emptyMap();
        }
        if ((System.currentTimeMillis() - modelsDevPriceCacheTime) >= CACHE_TTL_MILLIS) {
            triggerAsyncRefresh();
        }
        return cached;
    }

    /**
     * 获取 OpenRouter 价格缓存；无缓存时复用目录刷新任务，不阻塞当前请求。
     *
     * @return OpenRouter 价格目录。
     */
    private Map<String, ModelPrice> getOpenRouterPriceCache() {
        getOpenRouterCache();
        Map<String, ModelPrice> cached = openRouterPriceCache;
        if (cached == null || cached.isEmpty()) {
            triggerAsyncRefresh();
            return Collections.emptyMap();
        }
        if ((System.currentTimeMillis() - openRouterPriceCacheTime) >= CACHE_TTL_MILLIS) {
            triggerAsyncRefresh();
        }
        return cached;
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
    private final AtomicBoolean asyncRefreshScheduled = new AtomicBoolean(false);

    /** 生命周期锁，保证关闭和延迟创建执行器不会交叉产生新的线程。 */
    private final Object executorLifecycleLock = new Object();

    /** 服务关闭标志；Profile 运行时重载后禁止旧实例再次调度网络请求。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 异步刷新执行器，daemon 线程，不阻止 JVM 退出。 */
    private volatile ExecutorService asyncRefreshExecutor;

    /** 触发异步网络刷新，避免阻塞查询调用方。 */
    private void triggerAsyncRefresh() {
        if (closed.get() || !asyncRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        ExecutorService executor = getAsyncExecutor();
        if (executor == null) {
            asyncRefreshScheduled.set(false);
            initialRefreshFinished.countDown();
            return;
        }
        try {
            executor.submit(this::performAsyncRefresh);
        } catch (RejectedExecutionException e) {
            asyncRefreshScheduled.set(false);
            initialRefreshFinished.countDown();
            if (!closed.get()) {
                log.debug("模型目录异步刷新任务被拒绝: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取或创建异步刷新执行器。
     *
     * @return 返回异步执行器。
     */
    private ExecutorService getAsyncExecutor() {
        synchronized (executorLifecycleLock) {
            if (closed.get()) {
                return null;
            }
            if (asyncRefreshExecutor == null) {
                asyncRefreshExecutor =
                        Executors.newSingleThreadExecutor(
                                r -> {
                                    Thread t = new Thread(r, "model-context-catalog-refresh");
                                    t.setDaemon(true);
                                    return t;
                                });
            }
            return asyncRefreshExecutor;
        }
    }

    /** 执行异步网络刷新，拉取 models.dev 和 OpenRouter 数据并写入缓存。 */
    private void performAsyncRefresh() {
        try {
            if (closed.get()) {
                return;
            }
            Map<String, Map<String, ModelPrice>> freshModelsDevPrices = new LinkedHashMap<>();
            Map<String, ModelPrice> freshOpenRouterPrices = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> freshModelsDev =
                    fetchModelsDevFromRemote(freshModelsDevPrices);
            Map<String, Integer> freshOpenRouter = fetchOpenRouterFromRemote(freshOpenRouterPrices);
            if (closed.get()) {
                return;
            }
            long refreshedAt = System.currentTimeMillis();
            if (!freshModelsDev.isEmpty()) {
                modelsDevCache = freshModelsDev;
                modelsDevCacheTime = refreshedAt;
            }
            if (!freshOpenRouter.isEmpty()) {
                openRouterCache = freshOpenRouter;
                openRouterCacheTime = refreshedAt;
            }
            if (!freshModelsDevPrices.isEmpty()) {
                modelsDevPriceCache = freshModelsDevPrices;
                modelsDevPriceCacheTime = refreshedAt;
            }
            if (!freshOpenRouterPrices.isEmpty()) {
                openRouterPriceCache = freshOpenRouterPrices;
                openRouterPriceCacheTime = refreshedAt;
            }
            if (!freshModelsDev.isEmpty()
                    || !freshOpenRouter.isEmpty()
                    || !freshModelsDevPrices.isEmpty()
                    || !freshOpenRouterPrices.isEmpty()) {
                saveDiskCache(modelsDevCache, openRouterCache);
            }
        } catch (Exception e) {
            log.debug("异步刷新模型目录失败: {}", e.getMessage());
        } finally {
            initialRefreshFinished.countDown();
            asyncRefreshScheduled.set(false);
        }
    }

    /**
     * 关闭当前 Profile 的异步刷新线程，避免 Profile 重载后保留旧配置和缓存目录。
     *
     * <p>关闭幂等；与首次懒加载执行器的创建共享同一生命周期锁，防止关闭期间新建线程。
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        initialRefreshFinished.countDown();
        asyncRefreshScheduled.set(false);
        ExecutorService executor;
        synchronized (executorLifecycleLock) {
            executor = asyncRefreshExecutor;
            asyncRefreshExecutor = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** 供容器和手动资源管理使用的关闭入口。 */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * 从 models.dev 远端拉取并解析数据。
     *
     * @param prices 输出 providerId → (modelId → price) 价格映射。
     * @return 返回 providerId → (modelId → contextLength) 映射，失败返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> fetchModelsDevFromRemote(
            Map<String, Map<String, ModelPrice>> prices) {
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
            long fetchedAt = System.currentTimeMillis();
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
                Map<String, ModelPrice> providerPrices = new LinkedHashMap<>();
                for (Map.Entry<String, Object> modelEntry :
                        ((Map<String, Object>) modelsObj).entrySet()) {
                    Integer context = extractModelsDevContext(modelEntry.getValue());
                    if (context != null) {
                        providerModels.put(modelEntry.getKey(), context);
                    }
                    ModelPrice price =
                            extractModelsDevPrice(
                                    providerEntry.getKey(),
                                    modelEntry.getKey(),
                                    modelEntry.getValue(),
                                    fetchedAt);
                    if (price != null) {
                        providerPrices.put(modelEntry.getKey(), price);
                    }
                }
                if (!providerModels.isEmpty()) {
                    result.put(providerEntry.getKey(), providerModels);
                }
                if (prices != null && !providerPrices.isEmpty()) {
                    prices.put(providerEntry.getKey(), providerPrices);
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
     * 从 models.dev 模型条目提取每百万 Token 价格。
     *
     * @param providerId models.dev Provider 标识。
     * @param modelId 模型标识。
     * @param modelEntry 模型条目。
     * @param fetchedAt 拉取时间。
     * @return 有价格字段时返回价格，否则返回 null。
     */
    @SuppressWarnings("unchecked")
    static ModelPrice extractModelsDevPrice(
            String providerId, String modelId, Object modelEntry, long fetchedAt) {
        if (!(modelEntry instanceof Map)) {
            return null;
        }
        Object costObject = ((Map<String, Object>) modelEntry).get("cost");
        if (!(costObject instanceof Map)) {
            return null;
        }
        Map<String, Object> cost = (Map<String, Object>) costObject;
        ModelPrice price =
                onlinePrice(providerId, modelId, "models.dev", MODELS_DEV_URL, fetchedAt);
        boolean configured = applyPerMillionPrice(price, "input", cost.get("input"));
        configured |= applyPerMillionPrice(price, "output", cost.get("output"));
        configured |= applyPerMillionPrice(price, "cache_read", cost.get("cache_read"));
        configured |= applyPerMillionPrice(price, "cache_write", cost.get("cache_write"));
        configured |= applyPerMillionPrice(price, "reasoning", cost.get("reasoning"));
        price.setPricingVersion("models.dev-api");
        return configured ? price : null;
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
     * @param prices 输出 modelKey → price 价格映射。
     * @return 返回 modelKey → contextLength 映射（含别名），失败返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> fetchOpenRouterFromRemote(Map<String, ModelPrice> prices) {
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
            long fetchedAt = System.currentTimeMillis();
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
                    ModelPrice price = extractOpenRouterPrice(id, model.get("pricing"), fetchedAt);
                    if (prices != null && price != null) {
                        prices.put(id, price);
                    }
                    // 裸模型名别名（去掉 provider 前缀，如 anthropic/claude-opus-4.6 → claude-opus-4.6）
                    int slash = id.indexOf('/');
                    if (slash >= 0 && slash < id.length() - 1) {
                        String bareName = id.substring(slash + 1);
                        if (StrUtil.isNotBlank(bareName)) {
                            result.putIfAbsent(bareName, contextLength);
                            if (prices != null && price != null) {
                                prices.putIfAbsent(bareName, price);
                            }
                        }
                    }
                }
                String canonical = StrUtil.nullToEmpty((String) model.get("canonical_slug")).trim();
                if (StrUtil.isNotBlank(canonical)) {
                    result.putIfAbsent(canonical, contextLength);
                    if (prices != null && StrUtil.isNotBlank(id)) {
                        ModelPrice price = prices.get(id);
                        if (price != null) {
                            prices.putIfAbsent(canonical, price);
                        }
                    }
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
     * 从 OpenRouter 模型条目提取按 Token 计价并转换为每百万 Token 价格。
     *
     * @param modelId OpenRouter 模型标识。
     * @param pricingObject pricing 字段。
     * @param fetchedAt 拉取时间。
     * @return 有价格字段时返回价格，否则返回 null。
     */
    @SuppressWarnings("unchecked")
    static ModelPrice extractOpenRouterPrice(String modelId, Object pricingObject, long fetchedAt) {
        if (!(pricingObject instanceof Map)) {
            return null;
        }
        Map<String, Object> pricing = (Map<String, Object>) pricingObject;
        ModelPrice price =
                onlinePrice(
                        "openrouter",
                        modelId,
                        "openrouter_models_api",
                        OPENROUTER_MODELS_URL,
                        fetchedAt);
        boolean configured = applyPerTokenPrice(price, "input", pricing.get("prompt"));
        configured |= applyPerTokenPrice(price, "output", pricing.get("completion"));
        configured |=
                applyPerTokenPrice(
                        price,
                        "cache_read",
                        firstValue(pricing, "input_cache_read", "cache_read", "cached_prompt"));
        configured |=
                applyPerTokenPrice(
                        price,
                        "cache_write",
                        firstValue(
                                pricing,
                                "input_cache_write",
                                "input_cache_write_1h",
                                "cache_write",
                                "cache_creation"));
        configured |= applyPerTokenPrice(price, "reasoning", pricing.get("internal_reasoning"));
        Long requestMicros = usdToMicros(pricing.get("request"));
        if (requestMicros != null) {
            price.setRequestMicrosPerRequest(requestMicros.longValue());
            configured = true;
        }
        price.setPricingVersion("openrouter-models-api");
        return configured ? price : null;
    }

    /** 创建在线目录价格的公共元数据。 */
    private static ModelPrice onlinePrice(
            String provider, String model, String source, String sourceUrl, long fetchedAt) {
        ModelPrice price = new ModelPrice();
        price.setProvider(provider);
        price.setModel(model);
        price.setCurrency("USD");
        price.setSource(source);
        price.setSourceUrl(sourceUrl);
        price.setFetchedAt(fetchedAt);
        return price;
    }

    /**
     * 应用 models.dev 每百万 Token 价格。
     *
     * @param price 目标价格。
     * @param field 价格字段。
     * @param raw 原始价格。
     * @return 成功解析时返回 true。
     */
    private static boolean applyPerMillionPrice(ModelPrice price, String field, Object raw) {
        BigDecimal value = decimalValue(raw);
        if (value == null) {
            return false;
        }
        price.applyTokenPrice(field, value.stripTrailingZeros().toPlainString(), null);
        return true;
    }

    /**
     * 应用 OpenRouter 每 Token 美元价格并转换为每百万 Token 价格。
     *
     * @param price 目标价格。
     * @param field 价格字段。
     * @param raw 原始价格。
     * @return 成功解析时返回 true。
     */
    private static boolean applyPerTokenPrice(ModelPrice price, String field, Object raw) {
        BigDecimal value = decimalValue(raw);
        if (value == null) {
            return false;
        }
        price.applyTokenPrice(
                field, value.movePointRight(6).stripTrailingZeros().toPlainString(), null);
        return true;
    }

    /** 将非负十进制值转换为 BigDecimal，无效或负数返回 null。 */
    private static BigDecimal decimalValue(Object raw) {
        if (raw == null || StrUtil.isBlank(String.valueOf(raw))) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw).trim());
            return value.signum() < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 将每请求美元价格安全转换为微美元。 */
    private static Long usdToMicros(Object raw) {
        BigDecimal value = decimalValue(raw);
        if (value == null) {
            return null;
        }
        BigDecimal micros = value.movePointRight(6).setScale(0, RoundingMode.HALF_UP);
        if (micros.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return null;
        }
        return Long.valueOf(micros.longValue());
    }

    /** 从远端价格映射中返回第一个存在的字段值。 */
    private static Object firstValue(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return value;
            }
        }
        return null;
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
