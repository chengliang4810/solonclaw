package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.ShellCompletionGenerator;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class CliModeParserTest {
    @Test
    void shouldDefaultToServerMode() {
        CliMode mode = CliModeParser.parse(new String[0]);

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.SERVER);
        assertThat(mode.isConsoleMode()).isFalse();
    }

    @Test
    void shouldParseCliPromptAndSession() {
        CliMode mode =
                CliModeParser.parse(
                        new String[] {"--cli", "--session", "work", "-p", "/status"});

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
    void shouldParseAcpModeAsConsoleMode() {
        CliMode mode = CliModeParser.parse(new String[] {"acp"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.ACP);
        assertThat(mode.isConsoleMode()).isTrue();
    }

    @Test
    void shouldParseCompletionModeAsConsoleMode() {
        CliMode mode = CliModeParser.parse(new String[] {"completion", "zsh"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.COMPLETION);
        assertThat(mode.getInput()).isEqualTo("zsh");
        assertThat(mode.isConsoleMode()).isTrue();
    }

    @Test
    void shouldGenerateShellCompletionScripts() throws Exception {
        ShellCompletionGenerator generator = new ShellCompletionGenerator();

        String bash = completion(generator, "bash");
        String zsh = completion(generator, "zsh");
        String fish = completion(generator, "fish");

        assertThat(bash)
                .contains("_jimuqu_agent_completion()")
                .contains("complete -F _jimuqu_agent_completion jimuqu-agent")
                .contains("completion|--completion")
                .contains("bash zsh fish")
                .contains(
                        "--session --ask -p /help /models /sessions /session /history /title /events /tasks /attachments /transcript /tips /queue /steer /acp /skin /copy /exit /quit /exit! /quit!");
        assertThat(zsh)
                .contains("#compdef jimuqu-agent")
                .contains("completion:Print shell completion script")
                .contains("shells=(bash zsh fish)")
                .contains("_describe 'shell' shells")
                .contains("Send one prompt or local terminal command")
                .contains("/help /models /sessions /session /history /title /events /tasks /attachments /transcript /tips /queue /steer /acp /skin /copy /exit /quit /exit! /quit!")
                .doesNotContain("'[Send one prompt]:prompt:'");
        assertThat(fish)
                .contains("complete -c jimuqu-agent -f")
                .contains("__fish_seen_subcommand_from completion")
                .contains("bash zsh fish")
                .contains("__fish_seen_argument -s p -l ask")
                .contains("/help /models /sessions /session /history /title /events /tasks /attachments /transcript /tips /queue /steer /acp /skin /copy /exit /quit /exit! /quit!");
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
