package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import java.io.File;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 进程工具暴露与运行行为测试，从工具注册测试中拆出以控制单文件行数。 */
class ToolRegistryProcessToolsTest {
    /** 断言工具结果为当前成功状态，避免测试重新依赖已删除的 success 布尔字段。 */
    private static void assertToolSuccess(ONode result) {
        assertThat(result.get("status").getString()).as(result.toJson()).isNotEqualTo("error");
    }

    /** 断言工具结果为当前错误状态，避免测试重新依赖已删除的 success 布尔字段。 */
    private static void assertToolError(ONode result) {
        assertThat(result.get("status").getString()).isEqualTo("error");
    }

    @Test
    void shouldManageJimuquStyleBackgroundProcesses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "echo jimuqu-process-ok",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(started);
        String sessionId = started.get("session_id").getString();
        assertThat(sessionId).startsWith("proc_");

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        assertToolSuccess(waited);
        assertThat(waited.get("process_status").getString()).isEqualTo("exited");
        assertThat(waited.get("exited").getBoolean()).isTrue();
        assertThat(waited.get("output").getString()).contains("jimuqu-process-ok");

        ONode polled =
                ONode.ofJson(tools.process("poll", null, sessionId, null, null, null, null, null));
        assertThat(polled.get("process_status").getString()).isEqualTo("exited");
        assertThat(polled.get("output_preview").getString()).contains("jimuqu-process-ok");
        assertThat(polled.get("uptime_seconds").getLong()).isGreaterThanOrEqualTo(0L);

        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        assertThat(listed.get("count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(String.valueOf(listed.get("processes")))
                .contains("output_preview")
                .contains("uptime_seconds");
    }

    @Test
    void shouldRedactSecretsFromProcessToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode unsupported =
                ONode.ofJson(
                        tools.process(
                                "inspect --token=ghp_processaction12345",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        ONode missing =
                ONode.ofJson(
                        tools.process(
                                "poll",
                                null,
                                "proc_token=ghp_processsession12345",
                                null,
                                null,
                                null,
                                null,
                                null));

        assertThat(unsupported.get("error").getString())
                .contains("token=***")
                .doesNotContain("ghp_processaction12345");
        assertThat(missing.get("error").getString())
                .contains("token=***")
                .doesNotContain("ghp_processsession12345");
    }

    @Test
    void shouldExposeTerminalNotificationMetadataThroughProcessTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry.ManagedProcess managed =
                env.processRegistry.start(
                        javaSleepCommand(), new File(env.appConfig.getRuntime().getHome()));
        managed.setNotifyOnComplete(true);
        managed.setWatchPatterns(java.util.Collections.singletonList("ready"));
        ProcessTools tools = processTools(env);

        ONode polled =
                ONode.ofJson(
                        tools.process("poll", null, managed.getId(), null, null, null, null, null));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));

        assertThat(polled.get("notify_on_complete").getBoolean()).isTrue();
        assertThat(polled.get("watch_patterns").get(0).getString()).isEqualTo("ready");
        assertThat(String.valueOf(listed.get("processes")))
                .contains("notify_on_complete")
                .contains("watch_patterns")
                .contains("ready");

        assertThat(env.processRegistry.stop(managed.getId())).isTrue();
    }

    @Test
    void shouldRedactSensitiveWatchPatternsThroughProcessToolWithCanonicalConfig()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry.ManagedProcess managed =
                env.processRegistry.start(
                        javaSleepCommand(), new File(env.appConfig.getRuntime().getHome()));
        managed.setWatchPatterns(java.util.Collections.singletonList("token=secret123"));
        ProcessTools tools = processTools(env);

        ONode polled =
                ONode.ofJson(
                        tools.process("poll", null, managed.getId(), null, null, null, null, null));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));

        assertThat(polled.get("watch_patterns").get(0).getString()).isEqualTo("token=***");
        assertThat(polled.toJson()).doesNotContain("secret123");
        assertThat(listed.toJson()).contains("token=***").doesNotContain("secret123");
        assertThat(managed.getWatchPatterns()).containsExactly("token=secret123");

        assertThat(env.processRegistry.stop(managed.getId())).isTrue();
    }

    @Test
    void shouldExposeManagedProcessEventsThroughProcessTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessRegistry.ManagedProcess managed =
                registry.start(
                        shortEchoCommand(),
                        new File(env.appConfig.getRuntime().getHome()),
                        true,
                        java.util.Collections.<String>emptyList());
        ProcessTools tools = processTools(registry, env);

        assertThat(registry.waitFor(managed.getId(), 5000L)).isTrue();
        ONode events =
                ONode.ofJson(
                        tools.process(
                                "events", null, null, null, null, null, null, Integer.valueOf(10)));

        assertToolSuccess(events);
        assertThat(events.get("count").getInt()).isEqualTo(1);
        assertThat(events.get("events").get(0).get("type").getString()).isEqualTo("completion");
        assertThat(events.get("events").get(0).get("session_id").getString())
                .isEqualTo(managed.getId());
    }

    @Test
    void shouldExposeManagedProcessLifecycleSnapshotsThroughProcessTool() throws Exception {
        assumeTrue(
                !System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessTools tools = processTools(registry, env);

        ONode completedStart =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "printf 'ok token=secret123\\n'",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String completedId = completedStart.get("session_id").getString();
        assertThat(registry.waitFor(completedId, 5000L)).isTrue();

        ONode failedStart =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "printf 'bad token=secret123\\n'; exit 7",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String failedId = failedStart.get("session_id").getString();
        assertThat(registry.waitFor(failedId, 5000L)).isTrue();

        ONode lifecycle =
                ONode.ofJson(
                        tools.process(
                                "lifecycle",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Integer.valueOf(10)));
        ONode lifecycleAgain =
                ONode.ofJson(
                        tools.process(
                                "lifecycle",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Integer.valueOf(10)));
        ONode polled =
                ONode.ofJson(tools.process("detail", null, failedId, null, null, null, null, null));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        String lifecycleJson = lifecycle.toJson();

        assertToolSuccess(lifecycle);
        assertThat(lifecycle.get("count").getInt()).isEqualTo(4);
        assertThat(lifecycleJson)
                .contains("\"type\":\"started\"")
                .contains("\"type\":\"completed\"")
                .contains("\"type\":\"failed\"")
                .contains("\"exitCode\":7")
                .contains(completedId)
                .contains(failedId)
                .contains("token=***")
                .doesNotContain("secret123");
        assertThat(lifecycleAgain.get("count").getInt()).isEqualTo(4);
        assertThat(polled.get("lifecycle").toJson())
                .contains("\"type\":\"started\"")
                .contains("\"type\":\"failed\"");
        assertThat(listed.toJson()).contains("lifecycle_last_event");
    }

    @Test
    void shouldExposeKilledManagedProcessLifecycleSnapshot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessTools tools = processTools(registry, env);

        ONode started = startSleepingProcess(tools, env);
        String sessionId = started.get("session_id").getString();
        ONode killed =
                ONode.ofJson(
                        tools.process(
                                "kill",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        ONode detail =
                ONode.ofJson(
                        tools.process("status", null, sessionId, null, null, null, null, null));

        assertThat(killed.get("process_status").getString()).isEqualTo("killed");
        assertThat(detail.get("lifecycle").toJson())
                .contains("\"type\":\"started\"")
                .contains("\"type\":\"killed\"")
                .doesNotContain("\"type\":\"failed\"");
    }

    @Test
    void shouldAttachJimuquExitCodeMeaningToManagedProcessResults() throws Exception {
        assumeTrue(
                !System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "test -f /definitely-not-a-jimuqu-file",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        assertThat(waited.get("exit_code").getInt()).isEqualTo(1);
        assertThat(waited.get("exit_code_meaning").getString())
                .isEqualTo("Condition evaluated to false (expected, not an error)");

        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        assertThat(String.valueOf(listed.get("processes")))
                .contains("exit_code_meaning")
                .contains("Condition evaluated to false");
    }

    @Test
    void shouldRedactSecretsFromManagedProcessOutputsAndMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setUrlGuardrailMode("bypass");
        ProcessTools tools = processTools(env);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                secretEchoCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(started.get("status").getString())
                .as("process start result: %s", started.toString())
                .isNotEqualTo("error");
        String sessionId = started.get("session_id").getString();
        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        ONode polled =
                ONode.ofJson(tools.process("poll", null, sessionId, null, null, null, null, null));
        ONode logged =
                ONode.ofJson(
                        tools.process(
                                "log",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                Integer.valueOf(0),
                                Integer.valueOf(10)));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        String combined =
                waited.toString() + polled.toString() + logged.toString() + listed.toString();

        assertThat(combined)
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123");
    }

    @Test
    void shouldRedactSecretsFromManagedProcessEvents() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessRegistry.ManagedProcess managed =
                registry.start(
                        secretEventEchoCommand(),
                        new File(env.appConfig.getRuntime().getHome()),
                        true,
                        java.util.Collections.<String>emptyList());
        ProcessTools tools = processTools(registry, env);

        assertThat(registry.waitFor(managed.getId(), 5000L)).isTrue();
        ONode events =
                ONode.ofJson(
                        tools.process(
                                "events", null, null, null, null, null, null, Integer.valueOf(10)));
        String json = events.toJson();

        assertToolSuccess(events);
        assertThat(events.get("events").get(0).get("type").getString()).isEqualTo("completion");
        assertThat(json)
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123");
    }

    @Test
    void shouldReturnCleanErrorsForInvalidTerminalCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(),
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        env.processRegistry);

        ONode executeNull = ONode.ofJson(shell.execute(null, Integer.valueOf(1000)));
        ONode terminalNull =
                ONode.ofJson(
                        shell.terminal(
                                null,
                                Boolean.FALSE,
                                Integer.valueOf(1),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.FALSE));
        ONode backgroundNull =
                ONode.ofJson(
                        shell.terminal(
                                null,
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.TRUE));

        assertToolError(executeNull);
        assertThat(executeNull.get("status").getString()).isEqualTo("error");
        assertThat(executeNull.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(executeNull.get("error").getString())
                .contains("expected string")
                .contains("null");
        assertToolError(terminalNull);
        assertThat(terminalNull.get("error").getString())
                .contains("expected string")
                .contains("null");
        assertToolError(backgroundNull);
        assertThat(backgroundNull.get("background").getBoolean()).isTrue();
        assertThat(env.processRegistry.runningCount()).isZero();
    }

    @Test
    void shouldRejectForegroundTerminalTimeoutsAboveConfiguredCap() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(),
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        env.processRegistry);

        ONode executeTooLong =
                ONode.ofJson(shell.execute(shortEchoCommand(), Integer.valueOf(600001)));
        ONode terminalTooLong =
                ONode.ofJson(
                        shell.terminal(
                                shortEchoCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(601),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.FALSE));
        ONode backgroundLong =
                ONode.ofJson(
                        shell.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(9999),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.FALSE));
        String backgroundSessionId = backgroundLong.get("session_id").getString();

        assertToolError(executeTooLong);
        assertThat(executeTooLong.get("status").getString()).isEqualTo("error");
        assertThat(executeTooLong.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(executeTooLong.get("error").getString())
                .contains("600001ms")
                .contains("600000ms")
                .contains("background=true");

        assertToolError(terminalTooLong);
        assertThat(terminalTooLong.get("status").getString()).isEqualTo("error");
        assertThat(terminalTooLong.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(terminalTooLong.get("error").getString())
                .contains("601000ms")
                .contains("600000ms")
                .contains("background=true");

        assertToolSuccess(backgroundLong);
        assertThat(backgroundLong.get("background").getBoolean()).isTrue();
        assertThat(backgroundSessionId).isNotBlank();
        env.processRegistry.stop(backgroundSessionId);
    }

    @Test
    void shouldPageManagedProcessLogs() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                multiLineEchoCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();
        tools.process("wait", null, sessionId, null, null, Integer.valueOf(5), null, null);

        ONode lastTwo =
                ONode.ofJson(
                        tools.process(
                                "log",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                Integer.valueOf(0),
                                Integer.valueOf(2)));
        assertToolSuccess(lastTwo);
        assertThat(lastTwo.get("total_lines").getInt()).isGreaterThanOrEqualTo(4);
        assertThat(lastTwo.get("showing").getString()).isEqualTo("2 lines");
        assertThat(lastTwo.get("output").getString()).contains("line-3").contains("line-4");
        assertThat(lastTwo.get("output").getString()).doesNotContain("line-1");

        ONode middle =
                ONode.ofJson(
                        tools.process(
                                "log",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                Integer.valueOf(1),
                                Integer.valueOf(2)));
        assertThat(middle.get("output").getString()).contains("line-2").contains("line-3");
        assertThat(middle.get("output").getString()).doesNotContain("line-4");
    }

    @Test
    void shouldStopManagedBackgroundProcessesThroughStopCommandRegistry() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started = startSleepingProcess(tools, env);

        assertThat(env.processRegistry.runningCount()).isEqualTo(1);
        assertThat(env.processRegistry.stop(started.get("session_id").getString())).isTrue();
        assertThat(env.processRegistry.runningCount()).isZero();
    }

    @Test
    void shouldReturnJimuquKillStatusesForManagedProcesses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started = startSleepingProcess(tools, env);
        String sessionId = started.get("session_id").getString();

        ONode killed =
                ONode.ofJson(
                        tools.process(
                                "kill",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(killed);
        assertThat(killed.get("process_status").getString()).isEqualTo("killed");
        assertThat(killed.get("stopped").getBoolean()).isTrue();

        ONode killedAgain =
                ONode.ofJson(
                        tools.process(
                                "kill",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(killedAgain.get("process_status").getString()).isEqualTo("already_exited");
        assertThat(killedAgain.get("exit_code").isNull()).isFalse();
    }

    @Test
    void shouldReturnTimeoutWhenWaitingForRunningManagedProcess() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started = startSleepingProcess(tools, env);
        String sessionId = started.get("session_id").getString();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(0),
                                null,
                                null));

        assertToolSuccess(waited);
        assertThat(waited.get("process_status").getString()).isEqualTo("timeout");
        assertThat(waited.get("running").getBoolean()).isTrue();
        assertThat(waited.get("timeout_note").getString()).contains("still running");

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldClampManagedProcessWaitTimeoutToConfiguredLimit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setProcessWaitTimeoutSeconds(1);
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode started = startSleepingProcess(tools, env);
        String sessionId = started.get("session_id").getString();
        long startedAt = System.currentTimeMillis();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(60),
                                null,
                                null));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertToolSuccess(waited);
        assertThat(waited.get("process_status").getString()).isEqualTo("timeout");
        assertThat(waited.get("timeout_note").getString())
                .contains("Requested wait of 60s was clamped to configured limit of 1s");
        assertThat(elapsed).isLessThan(5000L);

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldWriteSubmitAndCloseManagedProcessStdin() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                stdinEchoCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode write =
                ONode.ofJson(
                        tools.process(
                                "write",
                                null,
                                sessionId,
                                null,
                                "alpha",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(write);

        ONode submit =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "-beta",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(submit);

        ONode close =
                ONode.ofJson(
                        tools.process(
                                "close",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(close);
        assertThat(close.get("stdin_closed").getBoolean()).isTrue();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        assertToolSuccess(waited);
        assertThat(waited.get("output").getString()).contains("alpha-beta");

        ONode writeAfterExit =
                ONode.ofJson(
                        tools.process(
                                "write",
                                null,
                                sessionId,
                                null,
                                "late",
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(writeAfterExit.get("process_status").getString()).isEqualTo("already_exited");
    }

    @Test
    void shouldApplyTerminalGuardrailsToManagedProcessStdinForShells() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("approval");
        ProcessTools tools = processTools(env);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                interactiveShellCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode blocked =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "curl http://169.254.169.254/latest/meta-data/?token=secret",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolError(blocked);
        assertThat(blocked.get("error").getString())
                .contains("process stdin")
                .contains("169.254.169.254")
                .doesNotContain("token=secret");

        ONode dangerous =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "rm -rf workspace/cache",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolError(dangerous);
        assertThat(dangerous.get("error").getString())
                .contains("process stdin")
                .contains("危险命令安全规则");

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldRecognizeWrappedManagedProcessStdinInterpretersForGuardrails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);

        assertThat(resolveStdinExecutionToolName(tools, "TOKEN=1 python3"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "/usr/bin/env FOO=bar python3 -i"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "env -i -u TOKEN node"))
                .isEqualTo("execute_js");
        assertThat(resolveStdinExecutionToolName(tools, "sudo -S -p '' python3"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "sudo --user root -- bash"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "doas python3"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "pkexec node")).isEqualTo("execute_js");
        assertThat(resolveStdinExecutionToolName(tools, "runas /user:Administrator powershell"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "command -p sh"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "exec /bin/bash"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "nohup node")).isEqualTo("execute_js");
        assertThat(resolveStdinExecutionToolName(tools, "cat")).isEqualTo("");
    }

    @Test
    void shouldRedactManagedProcessInvalidCwdErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);
        File workspaceHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File missing =
                new File(workspaceHome.getParentFile(), "process-token=ghp_processcwd12345-missing");

        ONode result =
                ONode.ofJson(
                        tools.process(
                                "start",
                                shortEchoCommand(),
                                null,
                                missing.getAbsolutePath(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));

        assertToolError(result);
        assertThat(result.get("error").getString()).contains("cwd is not a directory");
        assertThat(result.get("error").getString()).doesNotContain(workspaceHome.getParent());
        assertThat(result.get("error").getString()).doesNotContain("ghp_processcwd12345");
    }

    @Test
    void shouldRejectManagedProcessCredentialCwd() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools = processTools(env);
        File workspaceHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File credentialDir = new File(workspaceHome, ".ssh");
        assertThat(credentialDir.mkdirs() || credentialDir.isDirectory()).isTrue();

        ONode result =
                ONode.ofJson(
                        tools.process(
                                "start",
                                shortEchoCommand(),
                                null,
                                credentialDir.getAbsolutePath(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));

        assertToolError(result);
        assertThat(result.get("error").getString())
                .contains("workdir path")
                .contains("敏感系统/凭据文件")
                .doesNotContain(".ssh")
                .doesNotContain(workspaceHome.getAbsolutePath());
    }

    /** 构造使用测试环境默认注册表和安全策略的进程工具。 */
    private ProcessTools processTools(TestEnvironment env) {
        return processTools(env.processRegistry, env);
    }

    /** 构造使用指定注册表和测试环境安全策略的进程工具。 */
    private ProcessTools processTools(ProcessRegistry registry, TestEnvironment env) {
        return new ProcessTools(
                registry,
                env.appConfig.getRuntime().getHome(),
                new SecurityPolicyService(env.appConfig));
    }

    /** 启动测试用长驻后台进程并返回工具响应。 */
    private ONode startSleepingProcess(ProcessTools tools, TestEnvironment env) {
        return ONode.ofJson(
                tools.process(
                        "start",
                        javaSleepCommand(),
                        null,
                        env.appConfig.getRuntime().getHome(),
                        null,
                        Integer.valueOf(1),
                        null,
                        null));
    }

    private String javaSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "ping -n 30 127.0.0.1 > nul";
        }
        return "sleep 30";
    }

    private String stdinEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "findstr /n .*";
        }
        return "cat";
    }

    private String interactiveShellCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "cmd";
        }
        return "sh";
    }

    private String multiLineEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo line-1 & echo line-2 & echo line-3 & echo line-4";
        }
        return "printf 'line-1\\nline-2\\nline-3\\nline-4\\n'";
    }

    private String shortEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo ok";
        }
        return "printf 'ok\\n'";
    }

    private String secretEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo api_key=sk-test-secret token=secret123 https://example.com/public";
        }
        return "printf '%s\\n' 'api_key=sk-test-secret token=secret123 https://example.com/public'";
    }

    private String secretEventEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo api_key=sk-test-secret token=secret123 credentials.json";
        }
        return "printf '%s\\n' 'api_key=sk-test-secret token=secret123 credentials.json'";
    }

    private String resolveStdinExecutionToolName(ProcessTools tools, String command)
            throws Exception {
        Method method =
                ProcessTools.class.getDeclaredMethod("stdinExecutionToolName", String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(tools, command));
    }

}
