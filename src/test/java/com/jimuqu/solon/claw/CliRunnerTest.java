package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliRunner;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
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
    void cliPromptGuidesTuiOnlyCommandsWithoutFailure() throws Exception {
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

        assertLocalOutput(runner, "/background summarize", "该命令仅在交互式 TUI 中可用");
        assertLocalOutput(runner, "/statusbar", "该命令仅在交互式 TUI 中可用");
        assertLocalOutput(runner, "/image /tmp/nope.png", "该命令仅在交互式 TUI 中可用");
    }

    @Test
    void cliPromptRejectsUnknownSlashCommandsWithoutCallingAgent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CountingOrchestrator orchestrator = new CountingOrchestrator();
        CliRuntime runtime =
                new CliRuntime(env.commandService, orchestrator, env.agentRunControlService);
        CliRunner runner =
                new CliRunner(
                        runtime,
                        env.sessionRepository,
                        env.appConfig,
                        null,
                        new LlmProviderService(env.appConfig));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8.name()));

            int exitCode =
                    runner.run(
                            new CliMode(
                                    CliMode.Kind.CLI, "/not-a-real-command", "cli-runner-unknown"));

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8.name()))
                    .contains("unknown command: /not-a-real-command")
                    .contains("/help");
            assertThat(orchestrator.calls.get()).isZero();
        } finally {
            System.setErr(originalErr);
        }
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

    /** 统计未知 slash 是否误入 Agent 对话主循环。 */
    private static class CountingOrchestrator implements ConversationOrchestrator {
        /** 记录普通对话入口被调用的次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public GatewayReply handleIncoming(GatewayMessage message) {
            calls.incrementAndGet();
            return GatewayReply.ok("agent called");
        }

        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            return GatewayReply.ok("scheduled");
        }

        @Override
        public GatewayReply resumePending(String sourceKey) {
            return GatewayReply.ok("pending");
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
