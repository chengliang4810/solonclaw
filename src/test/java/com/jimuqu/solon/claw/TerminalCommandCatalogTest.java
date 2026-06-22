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
                        "/setup",
                        "/config",
                        "/version",
                        "/whoami",
                        "/commands",
                        "/insights",
                        "/plugins",
                        "/curator",
                        "/toolsets",
                        "/browser",
                        "/debug",
                        "/update",
                        "/history",
                        "/reload-skills",
                        "/platform",
                        "/skin",
                        "/fast",
                        "/reasoning",
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
                        "/subgoal",
                        "/quit")
                .contains("/help", "/cron", "/reload-mcp", "/security", "/sessions");

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
        assertThat(model.getCategory()).isEqualTo("model");
        assertThat(CommandRegistry.resolve("/model").getName()).isEqualTo("model");

        CommandDescriptor setup = CommandRegistry.get("setup");
        assertThat(setup).isNotNull();
        assertThat(setup.getCategory()).isEqualTo("configuration");
        assertThat(CommandRegistry.resolve("/setup").getName()).isEqualTo("setup");

        CommandDescriptor config = CommandRegistry.get("config");
        assertThat(config).isNotNull();
        assertThat(config.getCategory()).isEqualTo("configuration");
        assertThat(CommandRegistry.resolve("/config").getName()).isEqualTo("config");

        CommandDescriptor platforms = CommandRegistry.get("platforms");
        assertThat(platforms.getAliases()).contains("gateway");
        assertThat(CommandRegistry.resolve("/gateway").getName()).isEqualTo("platforms");

        CommandDescriptor platform = CommandRegistry.get("platform");
        assertThat(platform).isNotNull();
        assertThat(platform.getCategory()).isEqualTo("gateway");
        assertThat(CommandRegistry.resolve("/platform").getName()).isEqualTo("platform");

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
        assertThat(reloadMcp.getCategory()).isEqualTo("mcp");
        assertThat(CommandRegistry.resolve("/reload-mcp").getName()).isEqualTo("reload-mcp");

        CommandDescriptor reloadSkills = CommandRegistry.get("reload-skills");
        assertThat(reloadSkills.getCategory()).isEqualTo("skill");
        assertThat(CommandRegistry.resolve("/reload-skills").getName()).isEqualTo("reload-skills");

        CommandDescriptor insights = CommandRegistry.get("insights");
        assertThat(insights).isNotNull();
        assertThat(insights.getCategory()).isEqualTo("info");
        assertThat(CommandRegistry.resolve("/insights").getName()).isEqualTo("insights");

        CommandDescriptor plugins = CommandRegistry.get("plugins");
        assertThat(plugins).isNotNull();
        assertThat(plugins.getCategory()).isEqualTo("tool");
        assertThat(CommandRegistry.resolve("/plugins").getName()).isEqualTo("plugins");

        CommandDescriptor curator = CommandRegistry.get("curator");
        assertThat(curator).isNotNull();
        assertThat(curator.getCategory()).isEqualTo("skill");
        assertThat(CommandRegistry.resolve("/curator").getName()).isEqualTo("curator");

        CommandDescriptor toolsets = CommandRegistry.get("toolsets");
        assertThat(toolsets).isNotNull();
        assertThat(toolsets.getCategory()).isEqualTo("tool");
        assertThat(CommandRegistry.resolve("/toolsets").getName()).isEqualTo("toolsets");

        CommandDescriptor browser = CommandRegistry.get("browser");
        assertThat(browser).isNotNull();
        assertThat(browser.getCategory()).isEqualTo("tool");
        assertThat(CommandRegistry.resolve("/browser").getName()).isEqualTo("browser");

        CommandDescriptor debug = CommandRegistry.get("debug");
        assertThat(debug).isNotNull();
        assertThat(debug.getCategory()).isEqualTo("info");
        assertThat(CommandRegistry.resolve("/debug").getName()).isEqualTo("debug");

        CommandDescriptor update = CommandRegistry.get("update");
        assertThat(update).isNotNull();
        assertThat(update.getCategory()).isEqualTo("system");
        assertThat(CommandRegistry.resolve("/update").getName()).isEqualTo("update");

        CommandDescriptor history = CommandRegistry.get("history");
        assertThat(history).isNotNull();
        assertThat(history.getCategory()).isEqualTo("terminal");
        assertThat(CommandRegistry.resolve("/history").getName()).isEqualTo("history");

        CommandDescriptor skin = CommandRegistry.get("skin");
        assertThat(skin).isNotNull();
        assertThat(skin.getCategory()).isEqualTo("terminal");
        assertThat(CommandRegistry.resolve("/skin").getName()).isEqualTo("skin");

        CommandDescriptor quit = CommandRegistry.get("quit");
        assertThat(quit).isNotNull();
        assertThat(quit.getCategory()).isEqualTo("terminal");
        assertThat(quit.getAliases()).contains("exit");
        assertThat(CommandRegistry.resolve("/quit").getName()).isEqualTo("quit");
        assertThat(CommandRegistry.resolve("/exit").getName()).isEqualTo("quit");
    }

    @Test
    void shouldExposeOnlyRegisteredSlashCommandNames() {
        assertThat(TerminalCommandCatalog.slashCommands())
                .doesNotContain(
                        "/model pick",
                        "/approve all session",
                        "/security terminal-output",
                        "/cron upcoming",
                        "/reload-mcp always",
                        "/config env-path",
                        "/config migrate",
                        "/migrate",
                        "/exit!",
                        "/quit!");
    }
}
