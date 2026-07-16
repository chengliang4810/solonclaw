package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/** 验证模型目录异步刷新线程随 Profile 运行时销毁而释放。 */
class ModelContextCatalogServiceTest {
    /** 两个公开目录的价格单位必须统一转换为每百万 Token 美元。 */
    @Test
    void parsesModelsDevAndOpenRouterPricing() {
        Map<String, Object> modelsDevCost = new LinkedHashMap<String, Object>();
        modelsDevCost.put("input", new BigDecimal("0.185"));
        modelsDevCost.put("output", new BigDecimal("1.11"));
        modelsDevCost.put("cache_read", new BigDecimal("0.037"));
        modelsDevCost.put("reasoning", new BigDecimal("1.23"));
        Map<String, Object> modelsDevEntry = new LinkedHashMap<String, Object>();
        modelsDevEntry.put("cost", modelsDevCost);

        ModelPrice modelsDev =
                ModelContextCatalogService.extractModelsDevPrice(
                        "stepfun", "step-3.7-flash", modelsDevEntry, 123L);
        assertThat(modelsDev).isNotNull();
        assertThat(modelsDev.inputMicrosPerTokenExact()).isEqualByComparingTo("0.185");
        assertThat(modelsDev.outputMicrosPerTokenExact()).isEqualByComparingTo("1.11");
        assertThat(modelsDev.cacheReadMicrosPerTokenExact()).isEqualByComparingTo("0.037");
        assertThat(modelsDev.reasoningMicrosPerTokenExact()).isEqualByComparingTo("1.23");
        assertThat(modelsDev.getSource()).isEqualTo("models.dev");

        Map<String, Object> openRouterPricing = new LinkedHashMap<String, Object>();
        openRouterPricing.put("prompt", "0.0000002");
        openRouterPricing.put("completion", "0.00000115");
        openRouterPricing.put("input_cache_read", "0.00000004");
        openRouterPricing.put("internal_reasoning", "0.00000125");
        openRouterPricing.put("request", "0.001");

        ModelPrice openRouter =
                ModelContextCatalogService.extractOpenRouterPrice(
                        "stepfun/step-3.7-flash", openRouterPricing, 456L);
        assertThat(openRouter).isNotNull();
        assertThat(openRouter.inputMicrosPerTokenExact()).isEqualByComparingTo("0.2");
        assertThat(openRouter.outputMicrosPerTokenExact()).isEqualByComparingTo("1.15");
        assertThat(openRouter.cacheReadMicrosPerTokenExact()).isEqualByComparingTo("0.04");
        assertThat(openRouter.reasoningMicrosPerTokenExact()).isEqualByComparingTo("1.25");
        assertThat(openRouter.getRequestMicrosPerRequest()).isEqualTo(1000L);
        assertThat(openRouter.getSource()).isEqualTo("openrouter_models_api");
    }

    /** 同名价格优先使用 models.dev，只有未命中时才回退 OpenRouter。 */
    @Test
    void priceLookupPrefersModelsDevThenFallsBackToOpenRouter() throws Exception {
        ModelContextCatalogService service = new ModelContextCatalogService(new AppConfig());
        long now = System.currentTimeMillis();

        ModelPrice modelsDev = new ModelPrice();
        modelsDev.setProvider("first-provider");
        modelsDev.setModel("shared-model");
        modelsDev.setInputCostPerMillion("1");
        modelsDev.setSource("models.dev");
        Map<String, ModelPrice> providerPrices = new LinkedHashMap<String, ModelPrice>();
        providerPrices.put("shared-model", modelsDev);
        Map<String, Map<String, ModelPrice>> modelsDevPrices =
                new LinkedHashMap<String, Map<String, ModelPrice>>();
        modelsDevPrices.put("first-provider", providerPrices);

        ModelPrice openRouterShared = new ModelPrice();
        openRouterShared.setProvider("openrouter");
        openRouterShared.setModel("provider/shared-model");
        openRouterShared.setInputCostPerMillion("2");
        openRouterShared.setSource("openrouter_models_api");
        ModelPrice openRouterOnly = openRouterShared.copy();
        openRouterOnly.setModel("provider/openrouter-only");
        Map<String, ModelPrice> openRouterPrices = new LinkedHashMap<String, ModelPrice>();
        openRouterPrices.put("provider/shared-model", openRouterShared);
        openRouterPrices.put("provider/openrouter-only", openRouterOnly);

        setField(
                service,
                "modelsDevCache",
                Collections.singletonMap(
                        "first-provider", Collections.singletonMap("shared-model", 1)));
        setField(service, "modelsDevCacheTime", Long.valueOf(now));
        setField(service, "modelsDevPriceCache", modelsDevPrices);
        setField(service, "modelsDevPriceCacheTime", Long.valueOf(now));
        setField(
                service,
                "openRouterCache",
                Collections.singletonMap("provider/openrouter-only", Integer.valueOf(1)));
        setField(service, "openRouterCacheTime", Long.valueOf(now));
        setField(service, "openRouterPriceCache", openRouterPrices);
        setField(service, "openRouterPriceCacheTime", Long.valueOf(now));
        service.shutdown();

        assertThat(service.getPrice("arbitrary", "shared-model").getSource())
                .isEqualTo("models.dev");
        assertThat(service.getPrice("arbitrary", "openrouter-only").getSource())
                .isEqualTo("openrouter_models_api");
    }

    /** 已知 Provider 必须优先使用 models.dev 中同名目录，不能误映射到相邻 Provider。 */
    @Test
    void priceLookupUsesExactStepfunAiProvider() throws Exception {
        AppConfig config = new AppConfig();
        config.getProviders().put("stepfun-ai", new AppConfig.ProviderConfig());
        ModelContextCatalogService service = new ModelContextCatalogService(config);
        long now = System.currentTimeMillis();

        ModelPrice stepfun = price("stepfun", "shared-model", "1");
        ModelPrice stepfunAi = price("stepfun-ai", "shared-model", "2");
        Map<String, Map<String, ModelPrice>> prices =
                new LinkedHashMap<String, Map<String, ModelPrice>>();
        prices.put("stepfun", Collections.singletonMap("shared-model", stepfun));
        prices.put("stepfun-ai", Collections.singletonMap("shared-model", stepfunAi));
        setField(
                service,
                "modelsDevCache",
                Collections.singletonMap(
                        "stepfun-ai", Collections.singletonMap("shared-model", 1)));
        setField(service, "modelsDevCacheTime", Long.valueOf(now));
        setField(service, "modelsDevPriceCache", prices);
        setField(service, "modelsDevPriceCacheTime", Long.valueOf(now));
        service.shutdown();

        assertThat(service.getPrice("stepfun-ai", "shared-model").getProvider())
                .isEqualTo("stepfun-ai");
    }

    /** Profile 运行时销毁必须关闭懒创建的目录刷新线程，并禁止旧实例再次创建线程。 */
    @Test
    void shutdownClosesRefreshExecutorAndPreventsRecreation() throws Exception {
        ModelContextCatalogService service = new ModelContextCatalogService(new AppConfig());

        service.getContextLength("openai", "openai", "https://api.example.test/v1", "missing");
        ExecutorService executor = executorOf(service);
        assertThat(executor).isNotNull();

        service.shutdown();

        assertThat(executor.isShutdown()).isTrue();
        service.getContextLength("openai", "openai", "https://api.example.test/v1", "missing");
        assertThat(executorOf(service)).isNull();
    }

    /** 使用反射只验证资源生命周期，不暴露仅用于测试的生产访问器。 */
    private ExecutorService executorOf(ModelContextCatalogService service) throws Exception {
        Field field = ModelContextCatalogService.class.getDeclaredField("asyncRefreshExecutor");
        field.setAccessible(true);
        return (ExecutorService) field.get(service);
    }

    /** 创建仅含输入价格的目录条目。 */
    private ModelPrice price(String provider, String model, String inputCostPerMillion) {
        ModelPrice price = new ModelPrice();
        price.setProvider(provider);
        price.setModel(model);
        price.setInputCostPerMillion(inputCostPerMillion);
        price.setSource("models.dev");
        return price;
    }

    /** 写入缓存字段，避免查询顺序测试发起真实网络请求。 */
    private void setField(ModelContextCatalogService service, String name, Object value)
            throws Exception {
        Field field = ModelContextCatalogService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
