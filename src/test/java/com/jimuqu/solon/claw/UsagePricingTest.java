package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class UsagePricingTest {
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
                                + "\"source\":\"test-catalog\""
                                + "}]"
                                + "}");

        UsageCost priced =
                new UsageCostCalculator(catalog)
                        .calculate("default", "gpt-5.4", 100, 25, 40, 10, 3);
        assertThat(priced.isPricingAvailable()).isTrue();
        assertThat(priced.getCurrency()).isEqualTo("USD");
        assertThat(priced.getTotalMicros()).isEqualTo(590L);
        assertThat(priced.getPriceSource()).isEqualTo("test-catalog");
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
        event.setCostMicros(420);
        event.setCurrency("USD");
        event.setPriceSource("test-catalog");
        event.setPricingAvailable(true);
        event.setCreatedAt(1000L);
        event.setPricedAt(1000L);
        assertThat(usageRepository.insertIfAbsent(event)).isTrue();
        assertThat(usageRepository.insertIfAbsent(event)).isFalse();

        List<UsageEventRecord> stored = usageRepository.listRecent(10);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCostMicros()).isEqualTo(420L);

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
                        "{\"prices\":[{\"provider\":\"default\",\"model\":\"gpt-5.4\",\"currency\":\"USD\",\"input_micros_per_token\":2,\"output_micros_per_token\":10,\"source\":\"test-catalog\"}]}");
        UsageBackfillService backfill =
                new UsageBackfillService(
                        usageRepository,
                        runRepository,
                        sessionRepository,
                        new UsageCostCalculator(catalog));

        assertThat(backfill.backfillApproximate()).isEqualTo(2);
        assertThat(backfill.backfillApproximate()).isZero();
        assertThat(usageRepository.findByEventId("backfill-run-run-2").isBackfillApproximate())
                .isTrue();
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
