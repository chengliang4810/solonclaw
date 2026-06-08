package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ProviderDisplayGroupingTest {
    @Test
    void shouldExposeGroupedProviderRowsForDashboardModels() {
        AppConfig config = config();
        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();

        assertThat(result.get("models")).asList().hasSize(3);
        assertThat(result.get("model_groups")).asList().hasSize(1);
        Map<?, ?> group = (Map<?, ?>) ((List<?>) result.get("model_groups")).get(0);
        assertThat(group.get("kind")).isEqualTo("group");
        assertThat(group.get("group_id")).isEqualTo("backup-group");
        assertThat(group.get("label")).isEqualTo("Backup Group");
        assertThat(group.get("description")).isEqualTo("Global and China endpoints");
        assertThat(group.get("members")).asList().hasSize(2);
        assertThat(group.toString())
                .contains("backup")
                .contains("backup-cn")
                .contains("Global endpoint")
                .contains("China endpoint");
    }

    @Test
    void shouldExposeConfiguredPricingMetadataForDashboardModels() {
        AppConfig config = config();
        ModelPrice price = new ModelPrice();
        price.setProvider("default");
        price.setModel("gpt-main");
        price.setCurrency("USD");
        price.setInputMicrosPerToken(3);
        price.setOutputMicrosPerToken(15);
        price.setCacheReadMicrosPerToken(1);
        price.setCacheWriteMicrosPerToken(4);
        price.setReasoningMicrosPerToken(20);
        price.setSource("test-catalog");
        config.getPricing().getPrices().add(price);

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();
        Map<?, ?> model = (Map<?, ?>) ((List<?>) result.get("models")).get(0);
        Map<?, ?> pricing = (Map<?, ?>) model.get("pricing");

        assertThat(pricing).isNotNull();
        assertThat(pricing.get("currency")).isEqualTo("USD");
        assertThat(pricing.get("input")).isEqualTo("$3.00");
        assertThat(pricing.get("output")).isEqualTo("$15.00");
        assertThat(pricing.get("cache")).isEqualTo("$1.00");
        assertThat(pricing.get("cache_read")).isEqualTo("$1.00");
        assertThat(pricing.get("cache_write")).isEqualTo("$4.00");
        assertThat(pricing.get("reasoning")).isEqualTo("$20.00");
        assertThat(pricing.get("free")).isEqualTo(Boolean.FALSE);
        assertThat(pricing.get("source")).isEqualTo("test-catalog");
    }

    @Test
    void shouldExposeBuiltInDecimalPricingMetadataForDashboardModels() {
        AppConfig config = config();
        config.getProviders().get("default").setDefaultModel("gpt-4o-mini");

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();
        Map<?, ?> model = (Map<?, ?>) ((List<?>) result.get("models")).get(0);
        Map<?, ?> pricing = (Map<?, ?>) model.get("pricing");

        assertThat(pricing).isNotNull();
        assertThat(pricing.get("input")).isEqualTo("$0.15");
        assertThat(pricing.get("output")).isEqualTo("$0.60");
        assertThat(pricing.get("cache")).isEqualTo("$0.08");
        assertThat(pricing.get("free")).isEqualTo(Boolean.FALSE);
        assertThat(pricing.get("source")).isEqualTo("official_docs_snapshot");
    }

    @Test
    void shouldMarkFreePricingAndLeaveUnpricedModelsUnchanged() {
        AppConfig config = config();
        ModelPrice price = new ModelPrice();
        price.setProvider("default");
        price.setModel("gpt-main");
        config.getPricing().getPrices().add(price);

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();
        List<?> models = (List<?>) result.get("models");
        Map<?, ?> defaultModel = (Map<?, ?>) models.get(0);
        Map<?, ?> backupModel = (Map<?, ?>) models.get(1);
        Map<?, ?> pricing = (Map<?, ?>) defaultModel.get("pricing");

        assertThat(pricing.get("free")).isEqualTo(Boolean.TRUE);
        assertThat(pricing.get("input")).isEqualTo("free");
        assertThat(pricing.get("output")).isEqualTo("free");
        assertThat(pricing.get("cache")).isEqualTo("free");
        assertThat(pricing.get("cache_read")).isEqualTo("free");
        assertThat(pricing.get("cache_write")).isEqualTo("free");
        assertThat(pricing.get("reasoning")).isEqualTo("free");
        assertThat(backupModel.containsKey("pricing")).isFalse();
    }

    @Test
    void shouldFormatNonUsdPricingWithCurrencyCode() {
        AppConfig config = config();
        ModelPrice price = new ModelPrice();
        price.setProvider("default");
        price.setModel("gpt-main");
        price.setCurrency("CNY");
        price.setInputMicrosPerToken(3);
        price.setOutputMicrosPerToken(15);
        config.getPricing().getPrices().add(price);

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();
        Map<?, ?> model = (Map<?, ?>) ((List<?>) result.get("models")).get(0);
        Map<?, ?> pricing = (Map<?, ?>) model.get("pricing");

        assertThat(pricing.get("currency")).isEqualTo("CNY");
        assertThat(pricing.get("input")).isEqualTo("CNY 3.00");
        assertThat(pricing.get("output")).isEqualTo("CNY 15.00");
    }

    @Test
    void shouldExposeBuiltInPricingForCustomProviderKeysByDialect() {
        AppConfig config = config();
        AppConfig.ProviderConfig openAiDirect = new AppConfig.ProviderConfig();
        openAiDirect.setName("OpenAI Direct");
        openAiDirect.setBaseUrl("https://api.openai.com");
        openAiDirect.setDefaultModel("gpt-4o-mini");
        openAiDirect.setDialect("openai");
        config.getProviders().put("openai-direct", openAiDirect);

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();
        Map<?, ?> openAiDirectModel = null;
        for (Object item : (List<?>) result.get("models")) {
            Map<?, ?> row = (Map<?, ?>) item;
            if ("openai-direct".equals(row.get("provider"))) {
                openAiDirectModel = row;
                break;
            }
        }

        assertThat(openAiDirectModel).isNotNull();
        Map<?, ?> pricing = (Map<?, ?>) openAiDirectModel.get("pricing");
        assertThat(pricing.get("input")).isEqualTo("$0.15");
        assertThat(pricing.get("source")).isEqualTo("official_docs_snapshot");
    }

    @Test
    void shouldExposeProviderAwareModelListAndApiUrlsInDashboardModels() {
        AppConfig config = config();
        AppConfig.ProviderConfig moonshotProvider = new AppConfig.ProviderConfig();
        moonshotProvider.setName("Moonshot");
        moonshotProvider.setBaseUrl("https://api.moonshot.ai/v1");
        moonshotProvider.setDefaultModel("moonshot-v1-8k");
        moonshotProvider.setDialect("openai");
        config.getProviders().put("moonshot", moonshotProvider);

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.JimuquModels();
        List<?> models = (List<?>) result.get("models");
        Map<?, ?> moonshot = null;
        for (Object item : models) {
            Map<?, ?> row = (Map<?, ?>) item;
            if ("moonshot".equals(row.get("provider"))) {
                moonshot = row;
                break;
            }
        }

        assertThat(moonshot).isNotNull();
        assertThat(moonshot.get("api_url"))
                .isEqualTo("https://api.moonshot.ai/v1/chat/completions");
        assertThat(moonshot.get("model_list_url")).isEqualTo("https://api.moonshot.ai/v1/models");
        assertThat(((Map<?, ?>) moonshot.get("metadata")).get("model_list_url"))
                .isEqualTo("https://api.moonshot.ai/v1/models");
    }

    @Test
    void shouldExposeProviderProfilesWithAuthCatalogPricingParametersAndRouting() {
        AppConfig config = config();
        config.getProviders().get("default").setDefaultModel("gpt-4o-mini");
        config.getProviders().get("default").setApiKey("sk-provider-profile-test");
        config.getLlm().setTemperature(0.2D);
        config.getLlm().setMaxTokens(2048);
        config.getLlm().setReasoningEffort("high");
        config.getLlm().getPromptCache().setEnabled(true);
        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("backup");
        fallback.setModel("gpt-backup");
        config.getFallbackProviders().add(fallback);

        DashboardProviderService service =
                new DashboardProviderService(config, null, new LlmProviderService(config));

        Map<String, Object> result = service.listProviders();
        List<?> profiles = (List<?>) result.get("providerProfiles");
        Map<?, ?> defaultProfile = findProfile(profiles, "default");
        Map<?, ?> backupProfile = findProfile(profiles, "backup");

        assertThat(profiles).hasSize(3);
        assertThat(((Map<?, ?>) defaultProfile.get("authentication")).get("configured"))
                .isEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) defaultProfile.get("catalog")).get("model_list_url"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(((Map<?, ?>) defaultProfile.get("capabilities")).get("tool_calling"))
                .isEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) defaultProfile.get("parameters")).get("reasoning_effort"))
                .isEqualTo("high");
        assertThat(((Map<?, ?>) defaultProfile.get("pricing")).get("input")).isEqualTo("$0.15");
        assertThat(((Map<?, ?>) defaultProfile.get("routing")).get("role")).isEqualTo("primary");
        assertThat(((Map<?, ?>) backupProfile.get("routing")).get("role")).isEqualTo("fallback");
        assertThat(((Map<?, ?>) backupProfile.get("routing")).get("fallback_order"))
                .isEqualTo(Integer.valueOf(0));
    }

    private Map<?, ?> findProfile(List<?> profiles, String provider) {
        for (Object item : profiles) {
            Map<?, ?> row = (Map<?, ?>) item;
            if (provider.equals(row.get("provider"))) {
                return row;
            }
        }
        throw new AssertionError("profile not found: " + provider);
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default Provider");
        provider.setBaseUrl("https://api.openai.com");
        provider.setDefaultModel("gpt-main");
        provider.setDialect("openai");
        config.getProviders().put("default", provider);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-main");

        AppConfig.ProviderConfig backupProvider = new AppConfig.ProviderConfig();
        backupProvider.setName("Backup Provider");
        backupProvider.setBaseUrl("https://api.openai.com");
        backupProvider.setDefaultModel("gpt-backup");
        backupProvider.setDialect("openai");
        backupProvider.setGroupId("backup-group");
        backupProvider.setGroupLabel("Backup Group");
        backupProvider.setGroupDescription("Global and China endpoints");
        backupProvider.setDisplayDescription("Global endpoint");
        config.getProviders().put("backup", backupProvider);

        AppConfig.ProviderConfig cnProvider = new AppConfig.ProviderConfig();
        cnProvider.setName("Backup China");
        cnProvider.setBaseUrl("https://api.openai.com");
        cnProvider.setDefaultModel("gpt-cn");
        cnProvider.setDialect("openai");
        cnProvider.setGroupId("backup-group");
        cnProvider.setGroupLabel("Backup Group");
        cnProvider.setGroupDescription("Global and China endpoints");
        cnProvider.setDisplayDescription("China endpoint");
        config.getProviders().put("backup-cn", cnProvider);
        return config;
    }
}
