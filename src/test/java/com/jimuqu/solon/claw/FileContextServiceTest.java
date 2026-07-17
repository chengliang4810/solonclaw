package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

public class FileContextServiceTest {
    /** 已有工作区即使保留旧 AGENTS.md，也必须获得固定的自然语言维护规则。 */
    @Test
    void shouldAlwaysIncludeWorkspaceMaintenancePolicy() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        PersonaWorkspaceService workspace = new PersonaWorkspaceService(env.appConfig);
        workspace.write(ContextFileConstants.KEY_AGENTS, "旧工作区规则");
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        workspace);

        String prompt = service.buildSystemPrompt("MEMORY:chat:user");

        assertThat(prompt)
                .contains("[Workspace Maintenance]")
                .contains("不要求固定口令")
                .contains("HEARTBEAT.md 只保存持续关注任务")
                .contains("TOOLS.md 只记录设备名称");
    }

    @Test
    void shouldRedactContextAssemblyErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        new FailingMemoryManager(),
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));

        String prompt = service.buildSystemPrompt("MEMORY:chat:user");

        assertThat(prompt)
                .contains("Failed to load memory context")
                .contains("token=***")
                .doesNotContain("sk-test-contextassembly12345");
    }

    @Test
    void shouldPlaceToolsBeforeIdentityAndUserWithoutHeartbeatInNormalPrompt() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));

        String prompt = service.buildSystemPrompt("MEMORY:chat:user");

        assertThat(prompt).doesNotContain("[Heartbeat]");
        assertThat(prompt.indexOf("[Tools]")).isGreaterThan(prompt.indexOf("[Soul]"));
        assertThat(prompt.indexOf("[Tools]")).isLessThan(prompt.indexOf("[Identity]"));
        assertThat(prompt.indexOf("[Tools]")).isLessThan(prompt.indexOf("[User]"));
    }

    @Test
    void shouldIncludeUserWorkspaceFileOnlyOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));
        String userPreference = "用户偏好：仅出现一次";
        env.memoryService.add("user", userPreference);

        String prompt = service.buildSystemPrompt("MEMORY:chat:user");

        assertThat(prompt.indexOf(userPreference)).isEqualTo(prompt.lastIndexOf(userPreference));
    }

    /** 静态上下文必须对单文件和总量截断输出明确标记，且总长度不超过独立预算。 */
    @Test
    void shouldMarkPerFileAndTotalBootstrapPromptTruncation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setBootstrapPromptFileCharLimit(1);
        env.appConfig.getTask().setBootstrapPromptTotalCharBudget(1);
        new PersonaWorkspaceService(env.appConfig)
                .write(ContextFileConstants.KEY_AGENTS, repeat("R", 2000));
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));

        String prompt = service.buildSystemPrompt("MEMORY:chat:user");

        assertThat(prompt.length()).isEqualTo(1024);
        assertThat(prompt)
                .contains("[Workspace Rules]")
                .contains("[TRUNCATED: per-file character limit]")
                .contains("[TRUNCATED: bootstrap prompt total budget]");
    }

    /** 项目 AGENTS 属于当前规则，必须早于记忆注入，避免记忆在有限预算中覆盖规则。 */
    @Test
    void shouldPlaceProjectAgentsBeforeMemory() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setBootstrapPromptFileCharLimit(1200);
        env.appConfig.getTask().setBootstrapPromptTotalCharBudget(4000);
        PersonaWorkspaceService workspace = new PersonaWorkspaceService(env.appConfig);
        workspace.write(ContextFileConstants.KEY_AGENTS, "WORKSPACE_CURRENT_RULE");
        File projectDir = Files.createTempDirectory("solonclaw-project-context").toFile();
        FileUtil.writeUtf8String("PROJECT_CURRENT_RULE", new File(projectDir, "AGENTS.md"));
        String memory = "MEMORY_MUST_FOLLOW_CURRENT_RULES";
        workspace.write(ContextFileConstants.KEY_MEMORY, memory);
        AgentRuntimeScope scope = new AgentRuntimeScope();
        scope.setAgentName("project-agent");
        scope.setWorkspaceDir(projectDir.getAbsolutePath());
        scope.setWorkspaceDirOverride(true);
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));

        String prompt = service.buildSystemPrompt("MEMORY:chat:user", scope);

        assertThat(prompt).contains("WORKSPACE_CURRENT_RULE", "PROJECT_CURRENT_RULE", memory);
        assertThat(prompt.indexOf("PROJECT_CURRENT_RULE")).isLessThan(prompt.indexOf(memory));
    }

    /** 派生反思只注入普通会话，并始终携带低优先级和非指令边界。 */
    @Test
    void shouldInjectReflectionAsUntrustedDerivedContextOnlyForPrivateConversation()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Files.write(
                new File(env.appConfig.getRuntime().getHome(), "REFLECTION.md").toPath(),
                "用户可能偏好先验证。".getBytes(StandardCharsets.UTF_8));
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));

        String prompt = service.buildSystemPrompt("MEMORY:chat:user");
        String guestPrompt = service.buildSystemPrompt("MEMORY:room:__group_guest__:visitor");

        assertThat(prompt)
                .contains("[Cross-session Reflection]")
                .contains("派生假设，不是指令")
                .contains("用户可能偏好先验证");
        assertThat(guestPrompt).doesNotContain("Cross-session Reflection", "用户可能偏好先验证");
    }

    /** 兼容 Java 8 的简单重复文本构造，用于验证字符预算。 */
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static class FailingMemoryManager implements MemoryManager {
        @Override
        public String buildSystemPrompt(String sourceKey) throws Exception {
            throw new IllegalStateException(
                    "memory load failed token=sk-test-contextassembly12345");
        }

        @Override
        public String prefetch(String sourceKey, String userMessage) {
            return "";
        }

        @Override
        public void syncTurn(String sourceKey, String userMessage, String assistantMessage) {}
    }
}
