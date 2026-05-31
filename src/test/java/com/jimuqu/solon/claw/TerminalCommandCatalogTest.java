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
                        "/whoami",
                        "/commands",
                        "/reload-skills",
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
                .contains("/help", "/cron", "/kanban", "/reload-mcp", "/security", "/sessions");

        CommandDescriptor footer = CommandRegistry.get("footer");
        assertThat(footer).isNotNull();
        assertThat(footer.getCategory()).isEqualTo("terminal");
        assertThat(footer.getDescription()).isNotBlank();
        assertThat(footer.getScopes()).contains("cli", "gateway", "tui");
        assertThat(footer.isEnabledByDefault()).isTrue();

        CommandDescriptor statusbar = CommandRegistry.get("statusbar");
        assertThat(statusbar.getAliases()).contains("status-bar", "sb");
        assertThat(CommandRegistry.resolve("/status-bar").getName()).isEqualTo("statusbar");
        assertThat(CommandRegistry.resolve("/sb").getName()).isEqualTo("statusbar");

        CommandDescriptor branch = CommandRegistry.get("branch");
        assertThat(branch.getAliases()).contains("fork");
        assertThat(CommandRegistry.resolve("/fork").getName()).isEqualTo("branch");

        CommandDescriptor sethome = CommandRegistry.get("sethome");
        assertThat(sethome.getAliases()).contains("set-home");
        assertThat(CommandRegistry.resolve("/set-home").getName()).isEqualTo("sethome");

        CommandDescriptor model = CommandRegistry.get("model");
        assertThat(model.getAliases()).contains("provider");
        assertThat(CommandRegistry.resolve("/provider").getName()).isEqualTo("model");

        CommandDescriptor platforms = CommandRegistry.get("platforms");
        assertThat(platforms.getAliases()).contains("gateway");
        assertThat(CommandRegistry.resolve("/gateway").getName()).isEqualTo("platforms");

        CommandDescriptor queue = CommandRegistry.get("queue");
        assertThat(queue.getAliases()).contains("q");
        assertThat(CommandRegistry.resolve("/q").getName()).isEqualTo("queue");

        CommandDescriptor background = CommandRegistry.get("background");
        assertThat(background.getAliases()).contains("bg", "btw");
        assertThat(CommandRegistry.resolve("/bg").getName()).isEqualTo("background");
        assertThat(CommandRegistry.resolve("/btw").getName()).isEqualTo("background");

        CommandDescriptor tasks = CommandRegistry.get("tasks");
        assertThat(tasks.getAliases()).contains("agents");
        assertThat(CommandRegistry.resolve("/agents").getName()).isEqualTo("tasks");

        CommandDescriptor reloadMcp = CommandRegistry.get("reload-mcp");
        assertThat(reloadMcp.getAliases()).contains("reload_mcp");
        assertThat(CommandRegistry.resolve("/reload_mcp").getName()).isEqualTo("reload-mcp");

        CommandDescriptor reloadSkills = CommandRegistry.get("reload-skills");
        assertThat(reloadSkills.getAliases()).contains("reload_skills");
        assertThat(CommandRegistry.resolve("/reload_skills").getName()).isEqualTo("reload-skills");
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
