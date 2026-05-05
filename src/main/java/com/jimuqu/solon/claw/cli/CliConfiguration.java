package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** CLI/TUI bean wiring. */
@Configuration
public class CliConfiguration {
    @Bean
    public CliRuntime cliRuntime(
            CommandService commandService, ConversationOrchestrator conversationOrchestrator) {
        return new CliRuntime(commandService, conversationOrchestrator);
    }

    @Bean
    public CliRunner cliRunner(CliRuntime cliRuntime) {
        return new CliRunner(cliRuntime);
    }
}
