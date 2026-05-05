package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** CLI/TUI bean wiring. */
@Configuration
public class CliConfiguration {
    @Bean
    public CliRuntime cliRuntime(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            AgentRunControlService agentRunControlService) {
        return new CliRuntime(commandService, conversationOrchestrator, agentRunControlService);
    }

    @Bean
    public CliRunner cliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService dashboardMcpService) {
        return new CliRunner(cliRuntime, sessionRepository, dashboardMcpService);
    }
}
