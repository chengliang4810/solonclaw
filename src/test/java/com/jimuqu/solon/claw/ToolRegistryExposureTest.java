package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.HermesCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.HermesFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.HermesWebTools;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

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
                        "file_read",
                        "file_write",
                        "file_list",
                        "file_delete",
                        "patch",
                        "execute_shell",
                        "terminal",
                        "process",
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
        assertThat(joined).contains("HermesFileReadWriteSkill");
        assertThat(joined).contains("HermesPatchTools");
        assertThat(joined).contains("ShellSkill");
        assertThat(joined).contains("ProcessTools");
        assertThat(joined).contains("SafePythonSkill");
        assertThat(joined).contains("SafeNodejsSkill");
        assertThat(joined).contains("SystemClockSkill");
        assertThat(joined).contains("TodoTools");
        assertThat(joined).contains("KanbanTools");
        assertThat(joined).contains("AgentTools");
        assertThat(joined).contains("SkillsListTool");
        assertThat(joined).contains("ConfigRefreshTool");
        assertThat(joined).doesNotContain("ToolGatewaySkill");
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

        HermesWebTools.SafeWebfetchTool webfetch = new HermesWebTools.SafeWebfetchTool(policy);
        HermesWebTools.SafeWebsearchTool websearch = new HermesWebTools.SafeWebsearchTool(policy);
        HermesWebTools.SafeCodeSearchTool codesearch = new HermesWebTools.SafeCodeSearchTool(policy);

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
    }

    @Test
    void shouldGuardCodeExecutionToolsBeforeDelegatingToSolonAiSkills() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);

        HermesCodeExecutionSkills.SafePythonSkill python =
                new HermesCodeExecutionSkills.SafePythonSkill(
                        env.appConfig.getRuntime().getHome(), "python", policy);
        HermesCodeExecutionSkills.SafeNodejsSkill nodejs =
                new HermesCodeExecutionSkills.SafeNodejsSkill(
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
    void shouldGuardFileToolsBeforeDelegatingToSolonAiSkill() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        HermesFileReadWriteSkill fileSkill =
                new HermesFileReadWriteSkill(env.appConfig.getRuntime().getHome(), policy);

        assertThatThrownBy(() -> fileSkill.read(".env"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining(".env");
        assertThatThrownBy(() -> fileSkill.write("../outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径遍历");
        assertThatThrownBy(() -> fileSkill.delete("~/.ssh/id_rsa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感");
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
        HermesFileReadWriteSkill fileSkill =
                new HermesFileReadWriteSkill(
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

    private String multiLineEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo line-1 & echo line-2 & echo line-3 & echo line-4";
        }
        return "printf 'line-1\\nline-2\\nline-3\\nline-4\\n'";
    }
}
