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

/** 承载价格配置并集中创建运行组件。 */
@Configuration
public class PricingConfiguration {
    /**
     * 执行价格Catalog相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回价格Catalog结果。
     */
    @Bean
    public PriceCatalog priceCatalog(AppConfig appConfig) {
        return PriceCatalog.forConfig(appConfig);
    }

    /**
     * 执行用量成本Calculator相关逻辑。
     *
     * @param priceCatalog 价格Catalog参数。
     * @return 返回用量成本Calculator结果。
     */
    @Bean
    public UsageCostCalculator usageCostCalculator(PriceCatalog priceCatalog) {
        return new UsageCostCalculator(priceCatalog);
    }

    /**
     * 执行用量Backfill服务相关逻辑。
     *
     * @param usageEventRepository 用量事件仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param usageCostCalculator 用量成本Calculator参数。
     * @return 返回用量Backfill服务结果。
     */
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
