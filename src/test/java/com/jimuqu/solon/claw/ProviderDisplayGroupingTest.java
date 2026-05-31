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
