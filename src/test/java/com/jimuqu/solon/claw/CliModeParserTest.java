package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.ShellCompletionGenerator;
import com.jimuqu.solon.claw.cli.TerminalCommandCatalog;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class CliModeParserTest {
    @Test
    void shouldDisableConsoleLoggingForConsoleModesOnly() {
        String key = "solon.logging.appender.console.level";
        String original = System.getProperty(key);
        try {
            System.clearProperty(key);
            SolonClawApp.configureConsoleLogging(CliModeParser.parse(new String[] {"model"}));
            assertThat(System.getProperty(key)).isEqualTo("OFF");

            System.clearProperty(key);
            SolonClawApp.configureConsoleLogging(CliModeParser.parse(new String[0]));
            assertThat(System.getProperty(key)).isNull();

            System.setProperty(key, "WARN");
            SolonClawApp.configureConsoleLogging(CliModeParser.parse(new String[] {"--cli", "-p", "/setup"}));
            assertThat(System.getProperty(key)).isEqualTo("WARN");
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    @Test
    void shouldDefaultToServerMode() {
        CliMode mode = CliModeParser.parse(new String[0]);

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.SERVER);
        assertThat(mode.isConsoleMode()).isFalse();
    }

    @Test
    void shouldParseCliPromptAndSession() {
        CliMode mode =
                CliModeParser.parse(new String[] {"--cli", "--session", "work", "-p", "/status"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.getSessionId()).isEqualTo("work");
        assertThat(mode.getInput()).isEqualTo("/status");
    }

    @Test
    void shouldParseTuiAskRestAsInput() {
        CliMode mode =
                CliModeParser.parse(
                        new String[] {"tui", "--session=alpha", "--ask", "hello", "world"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.TUI);
        assertThat(mode.getSessionId()).isEqualTo("alpha");
        assertThat(mode.getInput()).isEqualTo("hello world");
    }

    @Test
    void shouldParseCompletionModeAsConsoleMode() {
        CliMode mode = CliModeParser.parse(new String[] {"completion", "zsh"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.COMPLETION);
        assertThat(mode.getInput()).isEqualTo("zsh");
        assertThat(mode.isConsoleMode()).isTrue();
    }

    @Test
    void shouldDisableNetworkListenersForConsoleModes() {
        assertThat(CliModeParser.parse(new String[0]).shouldStartNetworkListeners()).isTrue();
        assertThat(CliModeParser.parse(new String[] {"--cli", "-p", "/setup"}).shouldStartNetworkListeners())
                .isFalse();
        assertThat(CliModeParser.parse(new String[] {"--tui", "-p", "/setup"}).shouldStartNetworkListeners())
                .isFalse();
        assertThat(CliModeParser.parse(new String[] {"completion", "bash"}).shouldStartNetworkListeners())
                .isFalse();
        assertThat(CliModeParser.parse(new String[] {"model"}).shouldStartNetworkListeners()).isFalse();
    }

    @Test
    void shouldParseTopLevelModelCommandAsTerminalSetupCommand() {
        CliMode mode = CliModeParser.parse(new String[] {"model"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.isConsoleMode()).isTrue();
        assertThat(mode.getInput()).isEqualTo("/setup model");
    }

    @Test
    void shouldNotParseRemovedTopLevelModelRefreshArgument() {
        CliMode mode = CliModeParser.parse(new String[] {"model", "--refresh"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.SERVER);
        assertThat(mode.isConsoleMode()).isFalse();
        assertThat(mode.getInput()).isNull();
    }

    @Test
    void shouldParseTopLevelModelSetAsTerminalModelCommand() {
        CliMode mode =
                CliModeParser.parse(
                        new String[] {
                            "model",
                            "set",
                            "--provider",
                            "default",
                            "--base-url",
                            "https://api.example.com/v1",
                            "--api-key",
                            "Sk-Test-Secret-WithCase123",
                            "--model",
                            "mimo-v2.5-pro",
                            "--dialect",
                            "openai"
                        });

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.isConsoleMode()).isTrue();
        assertThat(mode.getInput())
                .isEqualTo(
                        "/model set --provider default --base-url https://api.example.com/v1 "
                                + "--api-key Sk-Test-Secret-WithCase123 --model mimo-v2.5-pro "
                                + "--dialect openai");
    }

    @Test
    void shouldParseTopLevelSetupSectionsAsTerminalCommands() {
        CliMode model = CliModeParser.parse(new String[] {"setup", "model"});
        CliMode gateway = CliModeParser.parse(new String[] {"setup", "gateway"});

        assertThat(model.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(model.getInput()).isEqualTo("/setup model");
        assertThat(gateway.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(gateway.getInput()).isEqualTo("/setup gateway");
    }

    @Test
    void shouldPreserveTopLevelSetupGatewayArguments() {
        CliMode mode =
                CliModeParser.parse(
                        new String[] {
                            "setup",
                            "gateway",
                            "feishu",
                            "--app-id",
                            "cli_test_app",
                            "--app-secret",
                            "Sk-Test-FeishuSecret123",
                            "--enabled",
                            "true"
                        });

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.getInput())
                .isEqualTo(
                        "/setup gateway feishu --app-id cli_test_app --app-secret "
                                + "Sk-Test-FeishuSecret123 --enabled true");
    }

    @Test
    void shouldParseGatewaySetupAliasAsTerminalSetupCommand() {
        CliMode mode = CliModeParser.parse(new String[] {"gateway", "setup"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.getInput()).isEqualTo("/setup gateway");
    }

    @Test
    void shouldParseConfigSubcommandsAsTerminalConfigCommands() {
        CliMode path = CliModeParser.parse(new String[] {"config", "path"});
        CliMode check = CliModeParser.parse(new String[] {"config", "check"});

        assertThat(path.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(path.getInput()).isEqualTo("/config path");
        assertThat(check.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(check.getInput()).isEqualTo("/config check");
    }

    @Test
    void shouldParseTopLevelDoctorAsTerminalDoctorCommand() {
        CliMode mode = CliModeParser.parse(new String[] {"doctor"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.isConsoleMode()).isTrue();
        assertThat(mode.getInput()).isEqualTo("/doctor");
    }

    @Test
    void shouldPreserveTopLevelDoctorArguments() {
        CliMode fix = CliModeParser.parse(new String[] {"doctor", "--fix"});
        CliMode ack = CliModeParser.parse(new String[] {"doctor", "--ack", "SC-2026-001"});

        assertThat(fix.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(fix.getInput()).isEqualTo("/doctor --fix");
        assertThat(ack.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(ack.getInput()).isEqualTo("/doctor --ack SC-2026-001");
    }

    @Test
    void shouldParseTopLevelSystemCommandsAsLocalTerminalCommands() {
        CliMode status = CliModeParser.parse(new String[] {"status"});
        CliMode version = CliModeParser.parse(new String[] {"version"});
        CliMode versionFlag = CliModeParser.parse(new String[] {"--version"});
        CliMode shortVersionFlag = CliModeParser.parse(new String[] {"-V"});
        CliMode logout = CliModeParser.parse(new String[] {"logout"});

        assertThat(status.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(status.getInput()).isEqualTo("/status");
        assertThat(version.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(version.getInput()).isEqualTo("/version");
        assertThat(versionFlag.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(versionFlag.getInput()).isEqualTo("/version");
        assertThat(shortVersionFlag.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(shortVersionFlag.getInput()).isEqualTo("/version");
        assertThat(logout.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(logout.getInput()).isEqualTo("/logout");
    }

    @Test
    void shouldParseTopLevelGatewayOperationsAsLocalTerminalCommands() {
        CliMode defaultStatus = CliModeParser.parse(new String[] {"gateway"});
        CliMode status = CliModeParser.parse(new String[] {"gateway", "status", "--deep"});
        CliMode list = CliModeParser.parse(new String[] {"gateway", "list"});
        CliMode run = CliModeParser.parse(new String[] {"gateway", "run"});
        CliMode start = CliModeParser.parse(new String[] {"gateway", "start"});
        CliMode stop = CliModeParser.parse(new String[] {"gateway", "stop"});
        CliMode restart = CliModeParser.parse(new String[] {"gateway", "restart"});
        CliMode install = CliModeParser.parse(new String[] {"gateway", "install", "--force"});
        CliMode uninstall = CliModeParser.parse(new String[] {"gateway", "uninstall"});

        assertThat(defaultStatus.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(defaultStatus.getInput()).isEqualTo("/gateway status");
        assertThat(status.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(status.getInput()).isEqualTo("/gateway status --deep");
        assertThat(list.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(list.getInput()).isEqualTo("/gateway list");
        assertThat(run.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(run.getInput()).isEqualTo("/gateway run");
        assertThat(start.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(start.getInput()).isEqualTo("/gateway start");
        assertThat(stop.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(stop.getInput()).isEqualTo("/gateway stop");
        assertThat(restart.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(restart.getInput()).isEqualTo("/gateway restart");
        assertThat(install.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(install.getInput()).isEqualTo("/gateway install --force");
        assertThat(uninstall.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(uninstall.getInput()).isEqualTo("/gateway uninstall");
    }

    @Test
    void shouldParseTopLevelPairingCommandsAsLocalSlashCommands() {
        CliMode list = CliModeParser.parse(new String[] {"pairing", "list"});
        CliMode approve = CliModeParser.parse(new String[] {"pairing", "approve", "feishu", "123456"});
        CliMode revoke = CliModeParser.parse(new String[] {"pairing", "revoke", "feishu", "ou_user"});
        CliMode clearPending = CliModeParser.parse(new String[] {"pairing", "clear-pending"});

        assertThat(list.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(list.shouldStartNetworkListeners()).isFalse();
        assertThat(list.getInput()).isEqualTo("/pairing list");
        assertThat(approve.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(approve.getInput()).isEqualTo("/pairing approve feishu 123456");
        assertThat(revoke.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(revoke.getInput()).isEqualTo("/pairing revoke feishu ou_user");
        assertThat(clearPending.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(clearPending.getInput()).isEqualTo("/pairing clear-pending");
    }

    @Test
    void shouldParseUnsupportedReferenceManagementCommandsAsLocalGuidance() {
        String[][] commands =
                new String[][] {
                    {"postinstall"},
                    {"login", "--provider", "openai"},
                    {"auth", "list"},
                    {"fallback", "list"},
                    {"secrets", "bitwarden"},
                    {"proxy", "status"},
                    {"send", "feishu", "hello"},
                    {"hooks", "list"},
                    {"dump", "--json"},
                    {"backup", "create"},
                    {"checkpoints", "list"},
                    {"import", "sessions", "file.json"},
                    {"bundles", "list"},
                    {"memory", "status"},
                    {"dashboard", "start"},
                    {"logs", "--tail", "20"},
                    {"prompt-size", "hello", "world"}
                };

        for (String[] command : commands) {
            CliMode mode = CliModeParser.parse(command);
            assertThat(mode.getKind()).describedAs(command[0]).isEqualTo(CliMode.Kind.CLI);
            assertThat(mode.shouldStartNetworkListeners()).describedAs(command[0]).isFalse();
            assertThat(mode.getInput()).describedAs(command[0]).startsWith("/" + command[0]);
        }
    }

    @Test
    void shouldParseRegisteredSlashCommandsAsTopLevelLocalCommands() {
        String[][] commands =
                new String[][] {
                    {"cron", "list"},
                    {"security", "status"},
                    {"skills", "list"},
                    {"plugins"},
                    {"tools", "list"},
                    {"reload-mcp", "now"},
                    {"browser", "status"},
                    {"curator", "status"},
                    {"insights"},
                    {"update"}
                };

        for (String[] command : commands) {
            CliMode mode = CliModeParser.parse(command);
            assertThat(mode.getKind()).describedAs(command[0]).isEqualTo(CliMode.Kind.CLI);
            assertThat(mode.shouldStartNetworkListeners()).describedAs(command[0]).isFalse();
            assertThat(mode.getInput()).describedAs(command[0]).isEqualTo("/" + String.join(" ", command));
        }
    }

    @Test
    void shouldParseTopLevelTerminalPickerAliasesAsLocalCommands() {
        String[][] commands =
                new String[][] {
                    {"models"},
                    {"session", "pick", "1"},
                    {"session", "show", "abc123"},
                    {"session", "inspect", "abc123"},
                    {"sessions", "browse"},
                    {"sessions", "list"},
                    {"sessions", "ls", "周报"}
                };
        String[] expected =
                new String[] {
                    "/models",
                    "/session pick 1",
                    "/session show abc123",
                    "/session inspect abc123",
                    "/sessions",
                    "/sessions",
                    "/sessions 周报"
                };

        for (int i = 0; i < commands.length; i++) {
            CliMode mode = CliModeParser.parse(commands[i]);
            assertThat(mode.getKind()).describedAs(String.join(" ", commands[i])).isEqualTo(CliMode.Kind.CLI);
            assertThat(mode.shouldStartNetworkListeners()).describedAs(String.join(" ", commands[i])).isFalse();
            assertThat(mode.getInput()).describedAs(String.join(" ", commands[i])).isEqualTo(expected[i]);
        }
    }

    @Test
    void shouldPreserveTopLevelSessionManagementSubcommands() {
        String[][] commands =
                new String[][] {
                    {"sessions", "stats"},
                    {"sessions", "export", "-", "--session-id", "abc123"},
                    {"sessions", "delete", "abc123", "--yes"},
                    {"sessions", "prune", "--older-than", "7", "--yes"},
                    {"sessions", "rename", "abc123", "新", "标题"}
                };
        String[] expected =
                new String[] {
                    "/sessions stats",
                    "/sessions export - --session-id abc123",
                    "/sessions delete abc123 --yes",
                    "/sessions prune --older-than 7 --yes",
                    "/sessions rename abc123 新 标题"
                };

        for (int i = 0; i < commands.length; i++) {
            CliMode mode = CliModeParser.parse(commands[i]);
            assertThat(mode.getKind()).describedAs(String.join(" ", commands[i])).isEqualTo(CliMode.Kind.CLI);
            assertThat(mode.shouldStartNetworkListeners()).describedAs(String.join(" ", commands[i])).isFalse();
            assertThat(mode.getInput()).describedAs(String.join(" ", commands[i])).isEqualTo(expected[i]);
        }
    }

    @Test
    void shouldParseTopLevelMcpCommandsAsLocalGuidance() {
        CliMode list = CliModeParser.parse(new String[] {"mcp", "list"});
        CliMode test = CliModeParser.parse(new String[] {"mcp", "test", "browser"});
        CliMode add =
                CliModeParser.parse(
                        new String[] {
                            "mcp",
                            "add",
                            "browser",
                            "--url",
                            "http://127.0.0.1:18080/mcp"
                        });

        assertThat(list.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(list.shouldStartNetworkListeners()).isFalse();
        assertThat(list.getInput()).isEqualTo("/mcp list");
        assertThat(test.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(test.getInput()).isEqualTo("/mcp test browser");
        assertThat(add.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(add.getInput()).isEqualTo("/mcp add browser --url http://127.0.0.1:18080/mcp");
    }

    @Test
    void shouldParseSingleArgumentCompositeManagementCommandAsLocalTerminalCommand() {
        CliMode gatewayStatus = CliModeParser.parse(new String[] {"gateway status"});
        CliMode pairingList = CliModeParser.parse(new String[] {"pairing list"});

        assertThat(gatewayStatus.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(gatewayStatus.shouldStartNetworkListeners()).isFalse();
        assertThat(gatewayStatus.getInput()).isEqualTo("/gateway status");
        assertThat(CliModeParser.parse(new String[] {"model --refresh"}).getKind())
                .isEqualTo(CliMode.Kind.SERVER);
        assertThat(pairingList.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(pairingList.getInput()).isEqualTo("/pairing list");
    }

    @Test
    void shouldGenerateShellCompletionScripts() throws Exception {
        ShellCompletionGenerator generator = new ShellCompletionGenerator();

        String bash = completion(generator, "bash");
        String zsh = completion(generator, "zsh");
        String fish = completion(generator, "fish");

        assertThat(bash)
                .contains("_solon_claw_completion()")
                .contains("complete -F _solon_claw_completion solonclaw")
                .contains("completion|--completion")
                .contains("bash zsh fish")
                .contains("--session --ask -p")
                .contains("/reload-mcp");
        assertThat(zsh)
                .contains("#compdef solonclaw")
                .contains("completion:Print shell completion script")
                .contains("shells=(bash zsh fish)")
                .contains("_describe 'shell' shells")
                .contains("Send one prompt or local terminal command")
                .contains("/reload-mcp")
                .doesNotContain("'[Send one prompt]:prompt:'");
        assertThat(fish)
                .contains("complete -c solonclaw -f")
                .contains("__fish_seen_subcommand_from completion")
                .contains("bash zsh fish")
                .contains("__fish_seen_argument -s p -l ask")
                .contains("/reload-mcp");
        for (String command : TerminalCommandCatalog.SLASH_COMMANDS) {
            assertThat(bash).contains(command);
            assertThat(zsh).contains(command);
            assertThat(fish).contains(command);
        }
    }

    @Test
    void shouldSanitizeLeakedTerminalResponsesBeforeRoutingCliInput() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime = new CliRuntime(env.commandService, env.conversationOrchestrator);

        GatewayReply reply =
                runtime.send(
                        "sanitize",
                        "hello\u001B[53;1Rworld<65;1;49M",
                        ConversationEventSink.noop());

        assertThat(reply.getContent()).isEqualTo("echo:helloworld");
    }

    private String completion(ShellCompletionGenerator generator, String shell) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code =
                generator.write(
                        shell,
                        new PrintStream(out, true, StandardCharsets.UTF_8.name()),
                        new PrintStream(err, true, StandardCharsets.UTF_8.name()));

        assertThat(code).isEqualTo(0);
        assertThat(err.toString(StandardCharsets.UTF_8.name())).isEmpty();
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
