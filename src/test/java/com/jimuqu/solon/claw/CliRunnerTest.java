package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliRunner;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证本地 CLI runner 能执行真实后端 slash 命令，而不是错误要求安装外部 TUI。 */
class CliRunnerTest {
    @Test
    void cliPromptRunsBackendSlashCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService);
        CliRunner runner =
                new CliRunner(
                        runtime,
                        env.sessionRepository,
                        env.appConfig,
                        null,
                        new LlmProviderService(env.appConfig));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));

            int exitCode = runner.run(new CliMode(CliMode.Kind.CLI, "/help", "cli-runner-help"));

            assertThat(exitCode).isEqualTo(0);
            assertThat(out.toString(StandardCharsets.UTF_8.name()))
                    .contains("/help - 显示帮助信息")
                    .doesNotContain("npm install -g");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void cliPromptHandlesLocalTerminalCommandsWithoutLlm() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService);
        CliRunner runner =
                new CliRunner(
                        runtime,
                        env.sessionRepository,
                        env.appConfig,
                        null,
                        new LlmProviderService(env.appConfig));

        assertLocalOutput(runner, "/models", "default:");
        assertLocalOutput(runner, "/security status", "guardrailMode");
        assertLocalOutput(runner, "/voice status", "Voice Mode Status");
        assertLocalOutput(runner, "/tips", "/queue");
        assertLocalOutput(runner, "/skin", "classic");
        Path attachment = Files.createTempFile("cli-runner-attachment", ".txt");
        Files.write(attachment, "hello".getBytes(StandardCharsets.UTF_8));
        assertLocalOutput(runner, "/attachments " + attachment, "");
        assertLocalOutput(runner, "/tasks", "CLI");
    }

    @Test
    void bareJavaTuiModeGuidesUsersToNodeTuiEntry() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService);
        CliRunner runner =
                new CliRunner(
                        runtime,
                        env.sessionRepository,
                        env.appConfig,
                        null,
                        new LlmProviderService(env.appConfig));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));
            System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8.name()));

            int exitCode = runner.run(new CliMode(CliMode.Kind.TUI, null, "cli-runner-tui"));

            assertThat(exitCode).isEqualTo(0);
            assertThat(out.toString(StandardCharsets.UTF_8.name()))
                    .contains("请使用 solonclaw 启动本地 TUI")
                    .contains("node_tui_entry=solonclaw")
                    .doesNotContain("缺少输入内容");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** 断言本地命令成功输出指定文本。 */
    private static void assertLocalOutput(CliRunner runner, String input, String expected)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));

            int exitCode = runner.run(new CliMode(CliMode.Kind.CLI, input, "cli-runner-local"));

            assertThat(exitCode).isEqualTo(0);
            String output = out.toString(StandardCharsets.UTF_8.name());
            assertThat(output).isNotBlank().doesNotContain("npm install -g");
            if (!expected.isEmpty()) {
                assertThat(output).contains(expected);
            }
        } finally {
            System.setOut(originalOut);
        }
    }
}
