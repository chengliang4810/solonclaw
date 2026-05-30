package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalCommandCatalog;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TerminalCommandCatalogTest {
    @Test
    void shouldExposeRegisteredSlashCommandsWithMetadata() {
        List<String> commands = TerminalCommandCatalog.slashCommands();

        assertThat(commands)
                .contains(
                        "/new",
                        "/retry",
                        "/undo",
                        "/model",
                        "/fast",
                        "/queue",
                        "/steer",
                        "/stop",
                        "/background",
                        "/tasks",
                        "/statusbar",
                        "/footer",
                        "/copy",
                        "/paste",
                        "/image",
                        "/handoff",
                        "/subgoal")
                .contains("/help", "/cron", "/kanban", "/reload-mcp", "/security");

        CommandDescriptor footer = CommandRegistry.get("footer");
        assertThat(footer).isNotNull();
        assertThat(footer.getCategory()).isEqualTo("terminal");
        assertThat(footer.getDescription()).isNotBlank();
        assertThat(footer.getScopes()).contains("cli", "gateway", "tui");
        assertThat(footer.isEnabledByDefault()).isTrue();
        assertThat(CommandRegistry.resolve("/status-bar").getName()).isEqualTo("statusbar");

        CommandDescriptor branch = CommandRegistry.get("branch");
        assertThat(branch.getAliases()).contains("fork");
        assertThat(CommandRegistry.resolve("/fork").getName()).isEqualTo("branch");

        CommandDescriptor sethome = CommandRegistry.get("sethome");
        assertThat(sethome.getAliases()).contains("set-home");
        assertThat(CommandRegistry.resolve("/set-home").getName()).isEqualTo("sethome");
    }

    @Test
    void shouldKeepLegacySubcommandsInTerminalCatalog() {
        assertThat(TerminalCommandCatalog.slashCommands())
                .contains(
                        "/acp status",
                        "/model pick",
                        "/approve all session",
                        "/security terminal-output",
                        "/cron upcoming",
                        "/kanban dispatch",
                        "/reload-mcp always",
                        "/exit!",
                        "/quit!");
    }
}
