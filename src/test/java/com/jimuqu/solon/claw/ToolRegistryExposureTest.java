package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.SolonClawCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileStateTracker;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawWebTools;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import java.io.File;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;

public class ToolRegistryExposureTest {
    @Test
    void shouldExposeBuiltinSearchTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> names =
                env.gatewayService == null ? java.util.Collections.<String>emptyList() : null;
        names = env.toolRegistry.listToolNames();

        assertThat(names)
                .contains(
                        "codesearch",
                        "websearch",
                        "webfetch",
                        "security_audit",
                        "file_read",
                        "file_write",
                        "file_list",
                        "file_delete",
                        "patch",
                        "execute_shell",
                        "terminal",
                        "process",
                        "execute_code",
                        "execute_python",
                        "execute_js",
                        "get_current_time",
                        "todo",
                        "kanban_show",
                        "kanban_complete",
                        "kanban_block",
                        "kanban_heartbeat",
                        "kanban_comment",
                        "kanban_create",
                        "kanban_link",
                        "kanban_unlink",
                        "agent_manage",
                        "skills_list",
                        "skill_view",
                        "skill_manage",
                        "skills_hub_search",
                        "skills_hub_install",
                        "skills_hub_tap",
                        "config_refresh");
        assertThat(names).contains("tool_gateway");
        assertThat(names)
                .doesNotContain(
                        "exists_cmd",
                        "list_files",
                        "read_file",
                        "write_file",
                        "search_files");

        List<Object> tools = env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1");
        String joined = tools.toString();
        assertThat(joined).contains("SafeCodeSearchTool");
        assertThat(joined).contains("SafeWebsearchTool");
        assertThat(joined).contains("SafeWebfetchTool");
        assertThat(joined).contains("SecurityAuditTools");
        assertThat(joined).contains("SolonClawFileReadWriteSkill");
        assertThat(joined).contains("SolonClawPatchTools");
        assertThat(joined).contains("ShellSkill");
        assertThat(joined).contains("ProcessTools");
        assertThat(joined).contains("SafeExecuteCodeTool");
        assertThat(joined).contains("SafePythonSkill");
        assertThat(joined).contains("SafeNodejsSkill");
        assertThat(joined).contains("SystemClockSkill");
        assertThat(joined).contains("TodoTools");
        assertThat(joined).doesNotContain("KanbanTools");
        assertThat(joined).contains("AgentTools");
        assertThat(joined).contains("SkillsListTool");
        assertThat(joined).contains("ConfigRefreshTool");
        assertThat(joined).doesNotContain("ToolGatewaySkill");
    }

    @Test
    void shouldExposeKanbanToolsOnlyInKanbanContexts() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        String defaultTools = env.toolRegistry.resolveEnabledTools(sourceKey).toString();
        List<String> defaultNames = env.toolRegistry.resolveEnabledToolNames(sourceKey);

        assertThat(defaultNames).doesNotContain("kanban_show", "kanban_complete", "kanban_unlink");
        assertThat(defaultTools).doesNotContain("KanbanTools");

        AgentRuntimeScope kanbanScope = new AgentRuntimeScope();
        kanbanScope.setAgentName("planner");
        kanbanScope.setDefaultAgent(false);
        kanbanScope.setAllowedToolsJson("[\"kanban\"]");

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey, kanbanScope))
                .containsExactly(
                        "kanban_show",
                        "kanban_complete",
                        "kanban_block",
                        "kanban_heartbeat",
                        "kanban_comment",
                        "kanban_create",
                        "kanban_link",
                        "kanban_unlink");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey, kanbanScope).toString())
                .contains("KanbanTools");

        assertThat(env.toolRegistry.resolveEnabledToolNames("MEMORY:kanban-task-1:worker"))
                .contains("kanban_show", "kanban_complete", "kanban_unlink");
        assertThat(env.toolRegistry.resolveEnabledTools("MEMORY:kanban-task-1:worker").toString())
                .contains("KanbanTools");
    }

    @Test
    void shouldAuditSecurityInputsWithoutExecutingThem() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null);

        ONode hardline =
                ONode.ofJson(
                        tools.audit(
                                "command",
                                "execute_shell",
                                "sudo reboot",
                                null,
                                null,
                                null,
                                null));
        ONode path =
                ONode.ofJson(
                        tools.audit(
                                "path",
                                null,
                                null,
                                null,
                                ".env",
                                Boolean.FALSE,
                                null));
        ONode toolArgs =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "file_write",
                                null,
                                null,
                                null,
                                null,
                                "{\"path\":\"../outside.txt\"}"));

        assertThat(hardline.get("success").getBoolean()).isTrue();
        assertThat(hardline.get("decision").getString()).isEqualTo("block");
        assertThat(hardline.get("commandPreview").getString()).contains("sudo reboot");
        assertThat(String.valueOf(hardline.get("findings"))).contains("hardline").contains("shutdown");
        assertThat(path.get("decision").getString()).isEqualTo("block");
        assertThat(String.valueOf(path.get("findings"))).contains("file_policy").contains("凭据");
        assertThat(toolArgs.get("decision").getString()).isEqualTo("block");
        assertThat(String.valueOf(toolArgs.get("findings"))).contains("路径遍历");
    }

    @Test
    void shouldManageHermesStyleBackgroundProcesses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

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
        assertThat(started.get("success").getBoolean()).isTrue();
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
        assertThat(waited.get("success").getBoolean()).isTrue();
        assertThat(waited.get("status").getString()).isEqualTo("exited");
        assertThat(waited.get("exited").getBoolean()).isTrue();
        assertThat(waited.get("output").getString()).contains("jimuqu-process-ok");

        ONode polled =
                ONode.ofJson(
                        tools.process(
                                "poll",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertThat(polled.get("status").getString()).isEqualTo("exited");
        assertThat(polled.get("output_preview").getString()).contains("jimuqu-process-ok");
        assertThat(polled.get("uptime_seconds").getLong()).isGreaterThanOrEqualTo(0L);

        ONode listed = ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        assertThat(listed.get("count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(String.valueOf(listed.get("processes"))).contains("output_preview").contains("uptime_seconds");
    }

    @Test
    void shouldExposeTerminalNotificationMetadataThroughProcessTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry.ManagedProcess managed =
                env.processRegistry.start(javaSleepCommand(), new File(env.appConfig.getRuntime().getHome()));
        managed.setNotifyOnComplete(true);
        managed.setWatchPatterns(java.util.Collections.singletonList("ready"));
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode polled =
                ONode.ofJson(
                        tools.process(
                                "poll",
                                null,
                                managed.getId(),
                                null,
                                null,
                                null,
                                null,
                                null));
        ONode listed = ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));

        assertThat(polled.get("notify_on_complete").getBoolean()).isTrue();
        assertThat(polled.get("watch_patterns").get(0).getString()).isEqualTo("ready");
        assertThat(String.valueOf(listed.get("processes")))
                .contains("notify_on_complete")
                .contains("watch_patterns")
                .contains("ready");

        assertThat(env.processRegistry.stop(managed.getId())).isTrue();
    }

    @Test
    void shouldAttachHermesExitCodeMeaningToManagedProcessResults() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

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

        ONode listed = ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        assertThat(String.valueOf(listed.get("processes")))
                .contains("exit_code_meaning")
                .contains("Condition evaluated to false");
    }

    @Test
    void shouldRedactSecretsFromManagedProcessOutputsAndMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

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
        assertThat(started.get("success").getBoolean())
                .as("process start result: %s", started.toString())
                .isTrue();
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
        ONode listed = ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        String combined = waited.toString() + polled.toString() + logged.toString() + listed.toString();

        assertThat(combined)
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

        assertThat(executeNull.get("success").getBoolean()).isFalse();
        assertThat(executeNull.get("status").getString()).isEqualTo("error");
        assertThat(executeNull.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(executeNull.get("error").getString()).contains("expected string").contains("null");
        assertThat(terminalNull.get("success").getBoolean()).isFalse();
        assertThat(terminalNull.get("error").getString()).contains("expected string").contains("null");
        assertThat(backgroundNull.get("success").getBoolean()).isFalse();
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

        assertThat(executeTooLong.get("success").getBoolean()).isFalse();
        assertThat(executeTooLong.get("status").getString()).isEqualTo("error");
        assertThat(executeTooLong.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(executeTooLong.get("error").getString())
                .contains("600001ms")
                .contains("600000ms")
                .contains("background=true");

        assertThat(terminalTooLong.get("success").getBoolean()).isFalse();
        assertThat(terminalTooLong.get("status").getString()).isEqualTo("error");
        assertThat(terminalTooLong.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(terminalTooLong.get("error").getString())
                .contains("601000ms")
                .contains("600000ms")
                .contains("background=true");

        assertThat(backgroundLong.get("success").getBoolean()).isTrue();
        assertThat(backgroundLong.get("background").getBoolean()).isTrue();
        assertThat(backgroundSessionId).isNotBlank();
        env.processRegistry.stop(backgroundSessionId);
    }

    @Test
    void shouldPageManagedProcessLogs() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

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
        assertThat(lastTwo.get("success").getBoolean()).isTrue();
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
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));

        assertThat(env.processRegistry.runningCount()).isEqualTo(1);
        assertThat(env.processRegistry.stop(started.get("session_id").getString())).isTrue();
        assertThat(env.processRegistry.runningCount()).isZero();
    }

    @Test
    void shouldReturnHermesKillStatusesForManagedProcesses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
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
        assertThat(killed.get("success").getBoolean()).isTrue();
        assertThat(killed.get("status").getString()).isEqualTo("killed");
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
        assertThat(killedAgain.get("status").getString()).isEqualTo("already_exited");
        assertThat(killedAgain.get("exit_code").isNull()).isFalse();
    }

    @Test
    void shouldReturnTimeoutWhenWaitingForRunningManagedProcess() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
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
                                Integer.valueOf(0),
                                null,
                                null));

        assertThat(waited.get("success").getBoolean()).isTrue();
        assertThat(waited.get("status").getString()).isEqualTo("timeout");
        assertThat(waited.get("running").getBoolean()).isTrue();
        assertThat(waited.get("timeout_note").getString()).contains("still running");

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldWriteSubmitAndCloseManagedProcessStdin() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

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
        assertThat(write.get("success").getBoolean()).isTrue();

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
        assertThat(submit.get("success").getBoolean()).isTrue();

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
        assertThat(close.get("success").getBoolean()).isTrue();
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
        assertThat(waited.get("success").getBoolean()).isTrue();
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
        assertThat(writeAfterExit.get("status").getString()).isEqualTo("already_exited");
    }

    @Test
    void shouldApplyTerminalGuardrailsToManagedProcessStdinForShells() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

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
        assertThat(blocked.get("success").getBoolean()).isFalse();
        assertThat(blocked.get("error").getString())
                .contains("process stdin")
                .contains("URL 安全策略")
                .contains("169.254.169.254")
                .contains("token=***");

        ONode dangerous =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "rm -rf runtime/cache",
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(dangerous.get("success").getBoolean()).isFalse();
        assertThat(dangerous.get("error").getString())
                .contains("process stdin")
                .contains("危险命令安全规则");

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldDropFileSkillWhenAllFileToolsAreDisabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.toolRegistry.disableTools(
                "MEMORY:room-1:user-1",
                java.util.Arrays.asList("file_read", "file_write", "file_list", "file_delete"));

        String joined = env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").toString();

        assertThat(joined).doesNotContain("FileReadWriteSkill");
    }

    @Test
    void shouldExposeManagedToolGatewayWhenExplicitlyEnabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        env.toolRegistry.enableTools(
                sourceKey, java.util.Collections.singletonList("tool_gateway"));

        String joined = env.toolRegistry.resolveEnabledTools(sourceKey).toString();

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("tool_gateway");
        assertThat(joined).contains("ToolGatewaySkill");
    }

    @Test
    void shouldGuardWebToolsBeforeDelegatingToSolonAiTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);

        SolonClawWebTools.SafeWebfetchTool webfetch = new SolonClawWebTools.SafeWebfetchTool(policy);
        SolonClawWebTools.SafeWebsearchTool websearch = new SolonClawWebTools.SafeWebsearchTool(policy);
        SolonClawWebTools.SafeCodeSearchTool codesearch = new SolonClawWebTools.SafeCodeSearchTool(policy);

        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "http://169.254.169.254/latest/meta-data/?token=secret123",
                                        "text",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("元数据")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "read https://blocked.example/docs?token=secret123",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                codesearch.codesearch(
                                        "read https://blocked.example/docs?token=secret123",
                                        Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://example.com/docs?next=sk-proj-abcdefghijklmnop",
                                        "text",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("疑似 API key")
                .hasMessageNotContaining("sk-proj-abcdefghijklmnop");
    }

    @Test
    void shouldGuardWebfetchFinalDocumentUrlAfterRedirect() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        policy,
                        new WebfetchTool() {
                            @Override
                            public Document webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return new Document("secret redirected content")
                                        .title("redirected")
                                        .url("https://blocked.example/final?token=secret123")
                                        .metadata(
                                                "sourceURL",
                                                "https://blocked.example/final?token=secret123");
                            }
                        });

        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://allowed.example/start", "markdown", Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardWebsearchReturnedDocumentUrlsAfterProviderResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        policy,
                        new WebsearchTool() {
                            @Override
                            public Document websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return new Document("secret search content")
                                        .title("result")
                                        .url("https://blocked.example/result?token=secret123")
                                        .metadata(
                                                "finalUrl",
                                                "https://blocked.example/result?token=secret123");
                            }
                        });

        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "allowed search",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardCodesearchReturnedDocumentUrlsInsideContainers() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTool() {
                            @Override
                            public Object handle(String query, Integer tokensNum) {
                                Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
                                result.put(
                                        "documents",
                                        Arrays.asList(
                                                new Document("secret code content")
                                                        .title("code")
                                                        .metadata(
                                                                "source_url",
                                                                "https://blocked.example/code?token=secret123")));
                                return result;
                            }
                        });

        assertThatThrownBy(
                        () -> codesearch.codesearch("allowed code query", Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardCodeExecutionToolsBeforeDelegatingToSolonAiSkills() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);

        SolonClawCodeExecutionSkills.SafePythonSkill python =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        env.appConfig.getRuntime().getHome(), "python", policy);
        SolonClawCodeExecutionSkills.SafeNodejsSkill nodejs =
                new SolonClawCodeExecutionSkills.SafeNodejsSkill(
                        env.appConfig.getRuntime().getHome(), policy);

        assertThatThrownBy(
                        () ->
                                python.execute(
                                        "open('.env').read()",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining(".env");
        assertThatThrownBy(
                        () ->
                                nodejs.execute(
                                        "fetch('http://169.254.169.254/latest/meta-data/?token=secret123')",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                nodejs.execute(
                                        "require('child_process').execSync('whoami')",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("危险命令安全规则")
                .hasMessageContaining("child");
    }

    @Test
    void shouldExposeHermesStyleExecuteCodeResultEnvelope() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputInlineLimit(200);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import json_parse, shell_quote\n"
                                        + "print('\\u001b[31mapi_key=sk-test-secret\\u001b[0m')\n"
                                        + "print(json_parse('{\"ok\": true}')['ok'])\n"
                                        + "print(shell_quote('a b'))\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(0);
        assertThat(result.get("duration_seconds").getDouble()).isGreaterThanOrEqualTo(0.0d);
        assertThat(result.get("output").getString())
                .contains("api_key=***")
                .contains("True")
                .contains("'a b'")
                .doesNotContain("sk-test-secret")
                .doesNotContain("\u001b");
    }

    @Test
    void shouldReturnHermesStyleExecuteCodeErrorsWithStderr() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import sys\nprint('before')\nsys.stderr.write('token=secret123\\n')\nraise RuntimeError('boom')\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("token=***").contains("RuntimeError");
        assertThat(result.get("output").getString())
                .contains("before")
                .contains("--- stderr ---")
                .contains("token=***")
                .doesNotContain("secret123");
    }

    @Test
    void shouldAllowExecuteCodeToCallHermesFileAndTerminalToolsThroughRpc() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(workspace.resolve("rpc-source.txt"), Arrays.asList("alpha", "needle"), StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        String terminalCommand =
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "echo rpc-terminal"
                        : "printf 'rpc-terminal\\n'";
        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file, write_file, patch, search_files, terminal\n"
                                        + "print(read_file('rpc-source.txt')['content'].splitlines()[0])\n"
                                        + "print(search_files('needle', path='.', limit=5)['matches'][0]['path'])\n"
                                        + "print(write_file('rpc-output.txt', 'before\\n')['output'])\n"
                                        + "print(patch(path='rpc-output.txt', old_string='before', new_string='after')['status'])\n"
                                        + "print(read_file('rpc-output.txt')['content'])\n"
                                        + "print(terminal(\"" + terminalCommand.replace("\\", "\\\\").replace("\"", "\\\"") + "\")['output'].strip())\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(6);
        assertThat(result.get("output").getString())
                .contains("alpha")
                .contains("rpc-source.txt")
                .contains("success")
                .contains("after")
                .contains("rpc-terminal");
        assertThat(new String(Files.readAllBytes(workspace.resolve("rpc-output.txt")), StandardCharsets.UTF_8))
                .contains("after");
    }

    @Test
    void shouldReturnExecuteCodeRpcToolErrorsWithoutBypassingSafety() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file\n"
                                        + "print(read_file('.env')['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(1);
        assertThat(result.get("output").getString()).contains("文件安全策略").contains(".env");
    }

    @Test
    void shouldGuardFileToolsBeforeDelegatingToSolonAiSkill() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(env.appConfig.getRuntime().getHome(), policy);

        assertThatThrownBy(() -> fileSkill.read(".env"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining(".env");
        assertThatThrownBy(() -> fileSkill.read("credentials.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("credentials.json");
        assertThatThrownBy(() -> fileSkill.write("credentials", "token=secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("credentials");
        assertThatThrownBy(() -> fileSkill.write("../outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径遍历");
        assertThatThrownBy(() -> fileSkill.delete("~/.ssh/id_rsa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感");
    }

    @Test
    void shouldRedactSecretsFromFileReadContent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("project-config.txt"),
                Arrays.asList(
                        "public=true",
                        "api_key=sk-test-secret",
                        "callback=https://user:pass@example.com/?token=secret123"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("project-config.txt"));
        String content = result.get("content").getString();

        assertThat(result.get("success").getBoolean()).isTrue();
        assertThat(content)
                .contains("public=true")
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123")
                .doesNotContain("user:pass");
    }

    @Test
    void shouldGuardFileToolsAgainstSymlinkEscapesBeforeDelegating() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path outside = Files.createTempDirectory("jimuqu-file-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, Arrays.asList("TOKEN=old"), StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode readResult = ONode.ofJson(fileSkill.read("linked/secret.txt"));
        assertThat(readResult.get("success").getBoolean()).isFalse();
        assertThat(readResult.get("error").getString()).contains("符号链接").contains("沙箱外部");
        assertThatThrownBy(() -> fileSkill.write("linked/secret.txt", "TOKEN=new"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("符号链接")
                .hasMessageContaining("沙箱外部");
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .contains("TOKEN=old");
    }

    @Test
    void shouldGuardPatchToolsAgainstSymlinkEscapesBeforeWriting() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path outside = Files.createTempDirectory("jimuqu-patch-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, Arrays.asList("TOKEN=old"), StandardCharsets.UTF_8);
        Files.write(workspace.resolve("inside.txt"), Arrays.asList("inside"), StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode replaceResult =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "linked/secret.txt",
                                "TOKEN=old",
                                "TOKEN=new",
                                Boolean.FALSE,
                                null));
        ONode updateResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Update File: linked/secret.txt\n"
                                        + "@@ TOKEN @@\n"
                                        + "-TOKEN=old\n"
                                        + "+TOKEN=new\n"
                                        + "*** End Patch"));
        ONode addResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Add File: linked/new.txt\n"
                                        + "+TOKEN=new\n"
                                        + "*** End Patch"));
        ONode moveResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Move File: inside.txt -> linked/moved.txt\n"
                                        + "*** End Patch"));

        assertPatchSymlinkEscapeBlocked(replaceResult);
        assertPatchSymlinkEscapeBlocked(updateResult);
        assertPatchSymlinkEscapeBlocked(addResult);
        assertPatchSymlinkEscapeBlocked(moveResult);
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .contains("TOKEN=old");
        assertThat(Files.exists(outside.resolve("new.txt"))).isFalse();
        assertThat(Files.exists(outside.resolve("moved.txt"))).isFalse();
        assertThat(new String(Files.readAllBytes(workspace.resolve("inside.txt")), StandardCharsets.UTF_8))
                .contains("inside");
    }

    @Test
    void shouldGuardFileAndPatchToolsAgainstConfiguredCredentialFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setCredentialFiles(Arrays.asList("credentials/oauth.json"));
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path credentialDir = workspace.resolve("credentials");
        Files.createDirectories(credentialDir);
        Path credentialFile = credentialDir.resolve("oauth.json");
        Files.write(credentialFile, Arrays.asList("{\"token\":\"old\"}"), StandardCharsets.UTF_8);
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(), securityPolicyService);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(), securityPolicyService);

        ONode patchResult =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "credentials/oauth.json",
                                "old",
                                "new",
                                Boolean.FALSE,
                                null));

        assertThatThrownBy(() -> fileSkill.read("credentials/oauth.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据");
        assertThatThrownBy(() -> fileSkill.write("credentials/oauth.json", "{\"token\":\"new\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据");
        assertThat(patchResult.get("success").getBoolean()).isFalse();
        assertThat(patchResult.get("error").getString()).contains("凭据");
        assertThat(new String(Files.readAllBytes(credentialFile), StandardCharsets.UTF_8))
                .contains("old")
                .doesNotContain("new");
    }

    @Test
    void shouldApplyHermesToolOutputLimitsToFileReads() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("long-lines.txt"),
                Arrays.asList(
                        "alpha",
                        "0123456789ABCDEFGHIJ",
                        "charlie",
                        "delta"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        2,
                        10);

        ONode firstPage = ONode.ofJson(fileSkill.read("long-lines.txt", 1, 99));

        assertThat(firstPage.get("success").getBoolean()).isTrue();
        assertThat(firstPage.get("limit").getInt()).isEqualTo(2);
        assertThat(firstPage.get("total_lines").getInt()).isEqualTo(4);
        assertThat(firstPage.get("truncated").getBoolean()).isTrue();
        assertThat(firstPage.get("hint").getString()).contains("offset=3");
        assertThat(firstPage.get("content").getString())
                .contains("     1|alpha")
                .contains("     2|0123456789... [truncated]")
                .doesNotContain("charlie");

        ONode secondPage = ONode.ofJson(fileSkill.read("long-lines.txt", 3, 2));

        assertThat(secondPage.get("truncated").getBoolean()).isFalse();
        assertThat(secondPage.get("content").getString())
                .contains("     3|charlie")
                .contains("     4|delta");
    }

    @Test
    void shouldDeduplicateUnchangedRepeatedFileReadsLikeHermes() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("repeat.txt"),
                Arrays.asList("alpha", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode first = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        ONode second = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        ONode third = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));

        assertThat(first.get("success").getBoolean()).isTrue();
        assertThat(first.get("content").getString()).contains("alpha").contains("bravo");
        assertThat(second.get("success").getBoolean()).isTrue();
        assertThat(second.get("dedup").getBoolean()).isTrue();
        assertThat(second.get("content_returned").getBoolean()).isFalse();
        assertThat(second.get("content").getString()).isNull();
        assertThat(third.get("success").getBoolean()).isFalse();
        assertThat(third.get("error").getString()).contains("BLOCKED").contains("重复");

        fileSkill.write("repeat.txt", "delta\n");
        ONode changed = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));

        assertThat(changed.get("success").getBoolean()).isTrue();
        assertThat(changed.get("content").getString()).contains("delta");
    }

    @Test
    void shouldWarnButNotBlockWhenWritingStaleReadFileLikeHermes() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-write.txt");
        Files.write(file, Arrays.asList("alpha"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read = ONode.ofJson(fileSkill.read("stale-write.txt", 1, 2));
        Files.write(file, Arrays.asList("external"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode staleWrite = ONode.ofJson(fileSkill.write("stale-write.txt", "agent\n"));
        String plainWrite = fileSkill.write("stale-write.txt", "agent2\n");

        assertThat(read.get("success").getBoolean()).isTrue();
        assertThat(staleWrite.get("success").getBoolean()).isTrue();
        assertThat(staleWrite.get("_warning").getString())
                .contains("was modified since you last read")
                .contains("stale-write.txt");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("agent2");
        assertThat(plainWrite).contains("文件保存成功").doesNotContain("_warning");
    }

    @Test
    void shouldWarnWhenPatchingStaleReadFileLikeHermes() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-patch.txt");
        Files.write(file, Arrays.asList("alpha", "bravo"), StandardCharsets.UTF_8);
        SolonClawFileStateTracker tracker = new SolonClawFileStateTracker();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        2000,
                        2000,
                        tracker);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        tracker);

        ONode read = ONode.ofJson(fileSkill.read("stale-patch.txt", 1, 2));
        Files.write(file, Arrays.asList("external", "bravo"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode patched =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "stale-patch.txt",
                                "external",
                                "agent",
                                Boolean.FALSE,
                                null));

        assertThat(read.get("success").getBoolean()).isTrue();
        assertThat(patched.get("success").getBoolean()).isTrue();
        assertThat(patched.get("_warning").getString())
                .contains("was modified since you last read")
                .contains("stale-patch.txt");
        assertThat(patched.get("warnings").size()).isEqualTo(1);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("agent");
    }

    @Test
    void shouldRefuseWritingInternalReadDedupStatusTextLikeHermes() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("dedup-source.txt"),
                Arrays.asList("alpha", "bravo"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        fileSkill.read("dedup-source.txt", 1, 2);
        ONode dedup = ONode.ofJson(fileSkill.read("dedup-source.txt", 1, 2));
        String status = dedup.get("summary").getString();

        assertThatThrownBy(() -> fileSkill.write("bad.txt", status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal read_file status");
        assertThatThrownBy(() -> fileSkill.write("bad.txt", "Note:\n" + status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal read_file status");

        StringBuilder longDoc = new StringBuilder();
        longDoc.append("This document quotes a tool status for tests:\n").append(status);
        while (longDoc.length() <= status.length() * 2) {
            longDoc.append("\nmore ordinary documentation");
        }
        fileSkill.write("quoted-status.txt", longDoc.toString());
        assertThat(Files.readAllBytes(workspace.resolve("quoted-status.txt")).length).isGreaterThan(0);
    }

    private void assertPatchSymlinkEscapeBlocked(ONode result) {
        assertThat(result.get("success").getBoolean()).isFalse();
        assertThat(result.get("error").getString()).contains("符号链接").contains("沙箱外部");
    }

    private String javaSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "ping -n 30 127.0.0.1 > nul";
        }
        return "sleep 30";
    }

    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                return false;
            }
            try {
                Process process =
                        new ProcessBuilder(
                                        "cmd",
                                        "/c",
                                        "mklink",
                                        "/J",
                                        link.toString(),
                                        target.toString())
                                .redirectErrorStream(true)
                                .start();
                return process.waitFor() == 0 && Files.exists(link);
            } catch (Exception ignoredAgain) {
                return false;
            }
        }
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

    private boolean commandExists(String command) {
        try {
            Process process =
                    new ProcessBuilder(command, "--version")
                            .redirectErrorStream(true)
                            .start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
