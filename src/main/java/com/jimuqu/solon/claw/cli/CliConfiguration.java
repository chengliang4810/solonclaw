package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 承载CLI配置并集中创建运行组件。 */
@Configuration
public class CliConfiguration {
    /**
     * 执行CLI运行时相关逻辑。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @return 返回cli运行时结果。
     */
    @Bean
    public CliRuntime cliRuntime(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            AgentRunControlService agentRunControlService) {
        return new CliRuntime(commandService, conversationOrchestrator, agentRunControlService);
    }

    /**
     * 执行CLIRunner相关逻辑。
     *
     * @param cliRuntime CLI运行时参数。
     * @param sessionRepository 会话仓储依赖。
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @return 返回cli Runner结果。
     */
    @Bean
    public CliRunner cliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            LlmProviderService llmProviderService) {
        return new CliRunner(
                cliRuntime,
                sessionRepository,
                appConfig,
                new CliAttachmentResolver(appConfig, attachmentCacheService),
                llmProviderService);
    }
}
