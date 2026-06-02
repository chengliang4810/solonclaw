package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.llm.LlmUsage;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.pricing.UsageCost;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteUsageEventRepository;
import com.jimuqu.solon.claw.usage.UsageBackfillService;
import com.jimuqu.solon.claw.usage.UsageEventRecord;
import com.jimuqu.solon.claw.web.DashboardAnalyticsService;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class UsagePricingTest {
    @Test
    void builtInPriceCatalogCalculatesCostsWithoutUserConfiguration() {
        PriceCatalog catalog = PriceCatalog.configuredWithDefaults(null);

        UsageCost openAi =
                new UsageCostCalculator(catalog)
                        .calculate("openai-responses", "openai/gpt-4o-mini", 1000000, 1000000, 1000000, 0, 0);
        assertThat(openAi.isPricingAvailable()).isTrue();
        assertThat(openAi.getTotalMicros()).isEqualTo(825000L);
        assertThat(openAi.getPriceSource()).isEqualTo("official_docs_snapshot");
        assertThat(openAi.getPricingVersion()).isEqualTo("openai-pricing-2026-03-16");

        UsageCost anthropic =
                new UsageCostCalculator(catalog)
                        .calculate("anthropic", "anthropic/claude-sonnet-4.6", 100, 20, 10, 4, 0);
        assertThat(anthropic.isPricingAvailable()).isTrue();
        assertThat(anthropic.getTotalMicros()).isEqualTo(618L);
        assertThat(anthropic.getPriceSourceUrl())
                .isEqualTo("https://platform.claude.com/docs/en/about-claude/pricing");

        UsageCost ollama =
                new UsageCostCalculator(catalog)
                        .calculate("ollama", "ollama/llama3", 100, 20, 0, 0, 0);
        assertThat(ollama.isPricingAvailable()).isTrue();
        assertThat(ollama.getTotalMicros()).isZero();
        assertThat(ollama.getPriceSource()).isEqualTo("local_runtime");
    }

    @Test
    void configuredPricesOverrideBuiltInDefaultsByExactNormalizedKey() {
        ModelPrice override = new ModelPrice();
        override.setProvider("openai");
        override.setModel("gpt-4o-mini");
        override.setCurrency("USD");
        override.setInputCostPerMillion("9.00");
        override.setOutputCostPerMillion("99.00");
        override.setSource("user_override");
        override.setPricingVersion("user-pricing-2026-06");

        ModelPrice unrelated = new ModelPrice();
        unrelated.setProvider("custom-provider");
        unrelated.setModel("gpt-4o-mini");
        unrelated.setInputMicrosPerToken(1);
        unrelated.setOutputMicrosPerToken(1);
        unrelated.setSource("custom-catalog");

        PriceCatalog catalog = PriceCatalog.configuredWithDefaults(Arrays.asList(override, unrelated));

        UsageCost overridden =
                new UsageCostCalculator(catalog)
                        .calculate("openai", "gpt-4o-mini", 1, 1, 0, 0, 0);
        assertThat(overridden.isPricingAvailable()).isTrue();
        assertThat(overridden.getTotalMicros()).isEqualTo(108L);
        assertThat(overridden.getPriceSource()).isEqualTo("user_override");
        assertThat(overridden.getPricingVersion()).isEqualTo("user-pricing-2026-06");

        UsageCost builtInStillAvailable =
                new UsageCostCalculator(catalog)
                        .calculate("openai", "gpt-4.1", 100, 20, 0, 0, 0);
        assertThat(builtInStillAvailable.isPricingAvailable()).isTrue();
        assertThat(builtInStillAvailable.getPriceSource()).isEqualTo("official_docs_snapshot");

        UsageCost unknown =
                new UsageCostCalculator(catalog)
                        .calculate("anthropic", "unknown-model", 100, 20, 0, 0, 0);
        assertThat(unknown.isPricingAvailable()).isFalse();
        assertThat(unknown.getUnpricedTotalTokens()).isEqualTo(120L);
    }

    @Test
    void builtInPricesApplyToConfiguredProviderAliasesByDialect() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("gpt-4o-mini");
        provider.setDialect("openai");
        config.getProviders().put("openai-direct", provider);

        PriceCatalog catalog = PriceCatalog.forConfig(config);

        UsageCost cost =
                new UsageCostCalculator(catalog)
                        .calculate("openai-direct", "gpt-4o-mini", 1000000, 1000000, 0, 0, 0);
        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(750000L);
        assertThat(cost.getPriceSource()).isEqualTo("official_docs_snapshot");
    }

    @Test
    void configuredPricesPartiallyOverrideBuiltInDefaults() {
        ModelPrice override = new ModelPrice();
        override.setProvider("openai");
        override.setModel("gpt-4o-mini");
        override.setInputCostPerMillion("9.00");
        override.setSource("user_override");

        PriceCatalog catalog = PriceCatalog.configuredWithDefaults(Arrays.asList(override));

        UsageCost cost =
                new UsageCostCalculator(catalog)
                        .calculate("openai", "gpt-4o-mini", 1000000, 1000000, 1000000, 0, 0);

        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(9675000L);
        assertThat(cost.getPriceSource()).isEqualTo("user_override");
    }

    @Test
    void costCalculatorRoundsAfterAggregatingDecimalTokenCosts() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{\"prices\":[{\"provider\":\"openai\",\"model\":\"micro\",\"input_cost_per_million\":\"0.30\",\"output_cost_per_million\":\"0.30\",\"cache_read_cost_per_million\":\"0.30\"}]}");

        UsageCost cost =
                new UsageCostCalculator(catalog).calculate("openai", "micro", 1, 1, 1, 0, 0);

        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(1L);
    }

    @Test
    void priceCatalogParsesPerMillionAliasesWithDecimalPrecision() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{\"prices\":[{\"provider\":\"openai\",\"model\":\"tiny\",\"currency\":\"USD\",\"input_cost_per_million\":\"0.15\",\"output_cost_per_million\":\"0.60\",\"cache_read_cost_per_million\":\"0.075\",\"source\":\"test-catalog\"}]}");

        UsageCost cost =
                new UsageCostCalculator(catalog)
                        .calculate("openai", "tiny", 1000000, 1000000, 1000000, 0, 0);

        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(825000L);
        assertThat(cost.getPriceSource()).isEqualTo("test-catalog");
    }

    @Test
    void costCalculatorUsesMicrosAndReportsUnpricedTokens() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{"
                                + "\"prices\":[{"
                                + "\"provider\":\"default\","
                                + "\"model\":\"gpt-5.4\","
                                + "\"currency\":\"USD\","
                                + "\"input_micros_per_token\":2,"
                                + "\"output_micros_per_token\":10,"
                                + "\"cache_read_micros_per_token\":1,"
                                + "\"cache_write_micros_per_token\":4,"
                                + "\"reasoning_micros_per_token\":20,"
                                + "\"request_micros_per_request\":30,"
                                + "\"source\":\"test-catalog\","
                                + "\"source_url\":\"https://pricing.example/catalog\","
                                + "\"pricing_version\":\"test-pricing-2026-06\","
                                + "\"fetched_at\":1800000000000"
                                + "}]"
                                + "}");

        UsageCost priced =
                new UsageCostCalculator(catalog)
                        .calculate("default", "gpt-5.4", 100, 25, 40, 10, 3);
        assertThat(priced.isPricingAvailable()).isTrue();
        assertThat(priced.getCurrency()).isEqualTo("USD");
        assertThat(priced.getTotalMicros()).isEqualTo(620L);
        assertThat(priced.getRequestCount()).isEqualTo(1L);
        assertThat(priced.getPriceSource()).isEqualTo("test-catalog");
        assertThat(priced.getPriceSourceUrl()).isEqualTo("https://pricing.example/catalog");
        assertThat(priced.getPricingVersion()).isEqualTo("test-pricing-2026-06");
        assertThat(priced.getPriceFetchedAt()).isEqualTo(1800000000000L);
        assertThat(priced.getUnpricedTotalTokens()).isZero();

        UsageCost unpriced =
                new UsageCostCalculator(catalog)
                        .calculate("default", "unknown-model", 100, 25, 40, 10, 3);
        assertThat(unpriced.isPricingAvailable()).isFalse();
        assertThat(unpriced.getTotalMicros()).isZero();
        assertThat(unpriced.getUnpricedInputTokens()).isEqualTo(100L);
        assertThat(unpriced.getUnpricedOutputTokens()).isEqualTo(25L);
        assertThat(unpriced.getUnpricedCacheReadTokens()).isEqualTo(40L);
        assertThat(unpriced.getUnpricedCacheWriteTokens()).isEqualTo(10L);
        assertThat(unpriced.getUnpricedReasoningTokens()).isEqualTo(3L);
        assertThat(unpriced.getRequestCount()).isEqualTo(1L);
    }

    @Test
    void costCalculatorSupportsExplicitRequestCountPricing() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{\"prices\":[{\"provider\":\"openrouter\",\"model\":\"charged\",\"currency\":\"USD\",\"input_micros_per_token\":2,\"output_micros_per_token\":10,\"request_micros_per_request\":100,\"source\":\"test-catalog\"}]}");

        UsageCost cost =
                new UsageCostCalculator(catalog)
                        .calculate("openrouter", "charged", 10, 5, 0, 0, 0, 3);

        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(370L);
        assertThat(cost.getRequestCount()).isEqualTo(3L);
    }

    @Test
    void costCalculatorSupportsCanonicalUsageObject() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{\"prices\":[{\"provider\":\"default\",\"model\":\"gpt-5.4\",\"currency\":\"USD\",\"input_micros_per_token\":2,\"output_micros_per_token\":10,\"cache_read_micros_per_token\":1,\"cache_write_micros_per_token\":4,\"reasoning_micros_per_token\":20,\"request_micros_per_request\":30,\"source\":\"test-catalog\"}]}");
        LlmUsage usage =
                LlmUsage.builder()
                        .inputTokens(100)
                        .outputTokens(25)
                        .cacheReadTokens(40)
                        .cacheWriteTokens(10)
                        .reasoningTokens(3)
                        .requestCount(2)
                        .build();

        UsageCost cost = new UsageCostCalculator(catalog).calculate("default", "gpt-5.4", usage);

        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(650L);
        assertThat(cost.getRequestCount()).isEqualTo(2L);
    }

    @Test
    void llmUsageNormalizesAnthropicOpenAiAndCustomRawShapes() {
        LlmUsage anthropic =
                LlmUsage.fromRawUsage(
                        "{\"input_tokens\":1000,\"output_tokens\":50,\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":100,\"request_count\":2}",
                        "anthropic",
                        "anthropic_messages");
        assertThat(anthropic.getInputTokens()).isEqualTo(1000L);
        assertThat(anthropic.getOutputTokens()).isEqualTo(50L);
        assertThat(anthropic.getCacheReadTokens()).isEqualTo(200L);
        assertThat(anthropic.getCacheWriteTokens()).isEqualTo(100L);
        assertThat(anthropic.getRequestCount()).isEqualTo(2L);
        assertThat(anthropic.getCanonicalPromptTokens()).isEqualTo(1300L);
        assertThat(anthropic.getCanonicalTotalTokens()).isEqualTo(1350L);
        assertThat(anthropic.getRawUsageJson()).contains("cache_creation_input_tokens");

        LlmUsage openAi =
                LlmUsage.fromRawUsage(
                        "{\"prompt_tokens\":1000,\"completion_tokens\":50,\"prompt_tokens_details\":{\"cached_tokens\":200,\"cache_write_tokens\":100},\"completion_tokens_details\":{\"reasoning_tokens\":7}}",
                        "openai",
                        "chat_completions");
        assertThat(openAi.getInputTokens()).isEqualTo(700L);
        assertThat(openAi.getOutputTokens()).isEqualTo(50L);
        assertThat(openAi.getCacheReadTokens()).isEqualTo(200L);
        assertThat(openAi.getCacheWriteTokens()).isEqualTo(100L);
        assertThat(openAi.getReasoningTokens()).isEqualTo(7L);
        assertThat(openAi.getCanonicalRequestCount()).isEqualTo(1L);
        assertThat(openAi.getCanonicalTotalTokens()).isEqualTo(1050L);

        LlmUsage custom =
                LlmUsage.fromRawUsage(
                        "{\"input_tokens\":20,\"output_tokens\":5,\"cache_read_tokens\":3,\"cache_write_tokens\":2,\"requests\":4}",
                        "custom",
                        "");
        assertThat(custom.getInputTokens()).isEqualTo(20L);
        assertThat(custom.getOutputTokens()).isEqualTo(5L);
        assertThat(custom.getCacheReadTokens()).isEqualTo(3L);
        assertThat(custom.getCacheWriteTokens()).isEqualTo(2L);
        assertThat(custom.getRequestCount()).isEqualTo(4L);
        assertThat(custom.toCanonicalMap()).containsEntry("request_count", Long.valueOf(4L));
    }

    @Test
    void llmUsageNormalizesAggregatedRawUsageArray() {
        LlmUsage usage =
                LlmUsage.fromRawUsage(
                        "[{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":3}},{\"input_tokens\":7,\"output_tokens\":4,\"request_count\":2}]",
                        "openai",
                        "chat_completions");

        assertThat(usage.getInputTokens()).isEqualTo(14L);
        assertThat(usage.getOutputTokens()).isEqualTo(6L);
        assertThat(usage.getCacheReadTokens()).isEqualTo(3L);
        assertThat(usage.getRequestCount()).isEqualTo(3L);
        assertThat(usage.getRawUsageJson()).startsWith("[");
    }

    @Test
    void priceCatalogSupportsPromptAndCompletionAliases() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{\"prices\":[{\"provider\":\"default\",\"model\":\"gpt-5.4\",\"currency\":\"USD\",\"prompt_micros_per_token\":2,\"completion_micros_per_token\":10,\"source\":\"test-catalog\"}]}");

        UsageCost cost =
                new UsageCostCalculator(catalog)
                        .calculate("default", "gpt-5.4", 100, 20, 0, 0, 0);

        assertThat(cost.isPricingAvailable()).isTrue();
        assertThat(cost.getTotalMicros()).isEqualTo(400L);
        assertThat(cost.getPriceSource()).isEqualTo("test-catalog");
    }

    @Test
    void costCalculatorNormalizesProviderPrefixesAndAnthropicVersionDots() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{"
                                + "\"prices\":[{"
                                + "\"provider\":\"anthropic\","
                                + "\"model\":\"claude-opus-4-7\","
                                + "\"currency\":\"USD\","
                                + "\"input_micros_per_token\":5,"
                                + "\"output_micros_per_token\":25,"
                                + "\"source\":\"test-catalog\""
                                + "}]"
                                + "}");

        UsageCost slashPrefixed =
                new UsageCostCalculator(catalog)
                        .calculate("anthropic", "anthropic/claude-opus-4.7", 100, 20, 0, 0, 0);
        assertThat(slashPrefixed.isPricingAvailable()).isTrue();
        assertThat(slashPrefixed.getTotalMicros()).isEqualTo(1000L);

        UsageCost colonPrefixed =
                new UsageCostCalculator(catalog)
                        .calculate("anthropic", "anthropic:claude-opus-4.7", 100, 20, 0, 0, 0);
        assertThat(colonPrefixed.isPricingAvailable()).isTrue();
        assertThat(colonPrefixed.getTotalMicros()).isEqualTo(1000L);
    }

    @Test
    void costCalculatorMatchesDefaultProviderToOpenAiCatalogConservatively() {
        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{"
                                + "\"prices\":[{"
                                + "\"provider\":\"openai\","
                                + "\"model\":\"gpt-5.4\","
                                + "\"currency\":\"USD\","
                                + "\"input_micros_per_token\":3,"
                                + "\"output_micros_per_token\":15,"
                                + "\"source\":\"test-catalog\""
                                + "}]"
                                + "}");

        UsageCost localProviderKey =
                new UsageCostCalculator(catalog)
                        .calculate("default", "gpt-5.4", 100, 20, 0, 0, 0);
        assertThat(localProviderKey.isPricingAvailable()).isTrue();
        assertThat(localProviderKey.getTotalMicros()).isEqualTo(600L);

        UsageCost responsesDialect =
                new UsageCostCalculator(catalog)
                        .calculate("openai-responses", "openai/gpt-5.4", 100, 20, 0, 0, 0);
        assertThat(responsesDialect.isPricingAvailable()).isTrue();
        assertThat(responsesDialect.getTotalMicros()).isEqualTo(600L);

        UsageCost unknownProvider =
                new UsageCostCalculator(catalog)
                        .calculate("custom-provider", "gpt-5.4", 100, 20, 0, 0, 0);
        assertThat(unknownProvider.isPricingAvailable()).isFalse();
    }

    @Test
    void usageEventsInsertReadAndBackfillAreIdempotent() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteUsageEventRepository usageRepository = new SqliteUsageEventRepository(database);
        SqliteAgentRunRepository runRepository = new SqliteAgentRunRepository(database);
        SqliteSessionRepository sessionRepository = new SqliteSessionRepository(database);

        UsageEventRecord event = new UsageEventRecord();
        event.setEventId("evt-1");
        event.setSessionId("session-1");
        event.setRunId("run-1");
        event.setSourceKey("MEMORY:chat:user");
        event.setProvider("default");
        event.setModel("gpt-5.4");
        event.setInputTokens(100);
        event.setOutputTokens(20);
        event.setCacheReadTokens(30);
        event.setCacheWriteTokens(10);
        event.setReasoningTokens(5);
        event.setTotalTokens(160);
        event.setRequestCount(2L);
        event.setCostMicros(420);
        event.setCurrency("USD");
        event.setPriceSource("test-catalog");
        event.setPriceSourceUrl("https://pricing.example/catalog");
        event.setPricingVersion("test-pricing-2026-06");
        event.setPriceFetchedAt(1800000000000L);
        event.setRawUsageJson("{\"input_tokens\":100,\"output_tokens\":20}");
        event.setPricingAvailable(true);
        event.setCreatedAt(1000L);
        event.setPricedAt(1000L);
        assertThat(usageRepository.insertIfAbsent(event)).isTrue();
        assertThat(usageRepository.insertIfAbsent(event)).isFalse();

        List<UsageEventRecord> stored = usageRepository.listRecent(10);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCostMicros()).isEqualTo(420L);
        assertThat(stored.get(0).getRequestCount()).isEqualTo(2L);
        assertThat(stored.get(0).getPriceSourceUrl()).isEqualTo("https://pricing.example/catalog");
        assertThat(stored.get(0).getPricingVersion()).isEqualTo("test-pricing-2026-06");
        assertThat(stored.get(0).getPriceFetchedAt()).isEqualTo(1800000000000L);
        assertThat(stored.get(0).getRawUsageJson()).contains("input_tokens");

        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-2");
        run.setSessionId("session-2");
        run.setSourceKey("MEMORY:chat:user");
        run.setStatus("success");
        run.setProvider("default");
        run.setModel("gpt-5.4");
        run.setInputTokens(50);
        run.setOutputTokens(25);
        run.setTotalTokens(75);
        run.setStartedAt(2000L);
        run.setFinishedAt(3000L);
        runRepository.saveRun(run);

        SessionRecord session = new SessionRecord();
        session.setSessionId("session-3");
        session.setSourceKey("MEMORY:chat:user2");
        session.setNdjson("");
        session.setCumulativeInputTokens(9);
        session.setCumulativeOutputTokens(8);
        session.setCumulativeTotalTokens(17);
        session.setLastResolvedProvider("default");
        session.setLastResolvedModel("gpt-5.4");
        session.setLastUsageAt(4000L);
        session.setCreatedAt(3500L);
        session.setUpdatedAt(4000L);
        sessionRepository.save(session);

        SessionRecord sessionWithRun = new SessionRecord();
        sessionWithRun.setSessionId("session-2");
        sessionWithRun.setSourceKey("MEMORY:chat:user");
        sessionWithRun.setNdjson("");
        sessionWithRun.setCumulativeInputTokens(50);
        sessionWithRun.setCumulativeOutputTokens(25);
        sessionWithRun.setCumulativeTotalTokens(75);
        sessionWithRun.setLastResolvedProvider("default");
        sessionWithRun.setLastResolvedModel("gpt-5.4");
        sessionWithRun.setLastUsageAt(3000L);
        sessionWithRun.setCreatedAt(2500L);
        sessionWithRun.setUpdatedAt(3000L);
        sessionRepository.save(sessionWithRun);

        PriceCatalog catalog =
                PriceCatalog.fromJson(
                        "{\"prices\":[{\"provider\":\"default\",\"model\":\"gpt-5.4\",\"currency\":\"USD\",\"input_micros_per_token\":2,\"output_micros_per_token\":10,\"source\":\"test-catalog\",\"source_url\":\"https://pricing.example/catalog\",\"pricing_version\":\"test-pricing-2026-06\",\"fetched_at\":1800000000000}]}");
        UsageBackfillService backfill =
                new UsageBackfillService(
                        usageRepository,
                        runRepository,
                        sessionRepository,
                        new UsageCostCalculator(catalog));

        assertThat(backfill.backfillApproximate()).isEqualTo(2);
        assertThat(backfill.backfillApproximate()).isZero();
        UsageEventRecord backfilledRun = usageRepository.findByEventId("backfill-run-run-2");
        assertThat(backfilledRun.isBackfillApproximate()).isTrue();
        assertThat(backfilledRun.getPriceSourceUrl()).isEqualTo("https://pricing.example/catalog");
        assertThat(backfilledRun.getPricingVersion()).isEqualTo("test-pricing-2026-06");
        assertThat(backfilledRun.getPriceFetchedAt()).isEqualTo(1800000000000L);
        assertThat(usageRepository.findByEventId("backfill-session-session-3").isBackfillApproximate())
                .isTrue();
        assertThat(usageRepository.findByEventId("backfill-session-session-2")).isNull();
    }

    @Test
    void analyticsUsesUsageEventsForCostsAndFallsBackToSessionTokensWithoutPricing()
            throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessionRepository = new SqliteSessionRepository(database);
        SqliteUsageEventRepository usageRepository = new SqliteUsageEventRepository(database);

        SessionRecord session = new SessionRecord();
        session.setSessionId("session-fallback");
        session.setSourceKey("MEMORY:chat:user");
        session.setNdjson("");
        session.setCumulativeInputTokens(10);
        session.setCumulativeOutputTokens(5);
        session.setCumulativeTotalTokens(15);
        session.setLastResolvedModel("fallback-model");
        session.setLastUsageAt(System.currentTimeMillis());
        session.setCreatedAt(System.currentTimeMillis());
        session.setUpdatedAt(System.currentTimeMillis());
        sessionRepository.save(session);

        UsageEventRecord event = new UsageEventRecord();
        event.setEventId("evt-priced");
        event.setSessionId("session-priced");
        event.setRunId("run-priced");
        event.setSourceKey("MEMORY:chat:user");
        event.setProvider("default");
        event.setModel("gpt-5.4");
        event.setInputTokens(100);
        event.setOutputTokens(20);
        event.setTotalTokens(120);
        event.setCostMicros(400);
        event.setCurrency("USD");
        event.setPricingAvailable(true);
        event.setPriceSource("test-catalog");
        event.setPricedAt(System.currentTimeMillis());
        event.setCreatedAt(System.currentTimeMillis());
        usageRepository.insertIfAbsent(event);

        DashboardAnalyticsService analyticsService =
                new DashboardAnalyticsService(sessionRepository, usageRepository);
        Map<String, Object> analytics = analyticsService.getUsage(30);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) analytics.get("totals");
        assertThat(((Number) totals.get("total_cost_micros")).longValue()).isEqualTo(400L);
        assertThat(totals.get("currency")).isEqualTo("USD");
        assertThat(totals.get("pricing_available")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) totals.get("unpriced_input_tokens")).longValue()).isZero();

        DashboardAnalyticsService fallbackOnly =
                new DashboardAnalyticsService(sessionRepository, null);
        Map<String, Object> fallback = fallbackOnly.getUsage(30);
        @SuppressWarnings("unchecked")
        Map<String, Object> fallbackTotals = (Map<String, Object>) fallback.get("totals");
        assertThat(((Number) fallbackTotals.get("total_input")).longValue()).isEqualTo(10L);
        assertThat(((Number) fallbackTotals.get("total_cost_micros")).longValue()).isZero();
        assertThat(fallbackTotals.get("pricing_available")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void analyticsCountsAllUsageEventsWithoutRepositoryLimitTruncation() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessionRepository = new SqliteSessionRepository(database);
        SqliteUsageEventRepository usageRepository = new SqliteUsageEventRepository(database);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10050; i++) {
            UsageEventRecord event = new UsageEventRecord();
            event.setEventId("evt-many-" + i);
            event.setSessionId("session-" + i);
            event.setRunId("run-" + i);
            event.setSourceKey("MEMORY:chat:user");
            event.setProvider("default");
            event.setModel("gpt-5.4");
            event.setInputTokens(1);
            event.setOutputTokens(1);
            event.setTotalTokens(2);
            event.setCostMicros(1);
            event.setCurrency("USD");
            event.setPricingAvailable(true);
            event.setCreatedAt(now - (i % 1000));
            usageRepository.insertIfAbsent(event);
        }

        DashboardAnalyticsService analyticsService =
                new DashboardAnalyticsService(sessionRepository, usageRepository);
        Map<String, Object> analytics = analyticsService.getUsage(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) analytics.get("totals");
        assertThat(((Number) totals.get("total_input")).longValue()).isEqualTo(10050L);
        assertThat(((Number) totals.get("total_output")).longValue()).isEqualTo(10050L);
        assertThat(((Number) totals.get("total_cost_micros")).longValue()).isEqualTo(10050L);
    }

    private AppConfig testConfig() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-pricing-test").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(runtimeHome, "data"), "state.db").getAbsolutePath());
        return config;
    }
}
