package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.HermesCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.HermesFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.HermesWebTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
