package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import com.jimuqu.solon.claw.usage.UsageBackfillService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** Usage pricing bean configuration. */
@Configuration
public class PricingConfiguration {
    @Bean
    public PriceCatalog priceCatalog(AppConfig appConfig) {
        return PriceCatalog.forConfig(appConfig);
    }

    @Bean
    public UsageCostCalculator usageCostCalculator(PriceCatalog priceCatalog) {
        return new UsageCostCalculator(priceCatalog);
    }

    @Bean
    public UsageBackfillService usageBackfillService(
            UsageEventRepository usageEventRepository,
            AgentRunRepository agentRunRepository,
            SessionRepository sessionRepository,
            UsageCostCalculator usageCostCalculator) {
        return new UsageBackfillService(
                usageEventRepository, agentRunRepository, sessionRepository, usageCostCalculator);
    }
}
