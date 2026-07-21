package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.model.MemoryPromptSection;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
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

        assertThat(prompt.length()).isLessThanOrEqualTo(1024);
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

    /** 增长中的长期和当天记忆不能挤掉规则、人格和用户资料。 */
    @Test
    void shouldKeepRulesAndPersonaBeforeGrowingMemoryAtTightBudget() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setBootstrapPromptFileCharLimit(12000);
        env.appConfig.getTask().setBootstrapPromptTotalCharBudget(4000);
        PersonaWorkspaceService workspace = new PersonaWorkspaceService(env.appConfig);
        workspace.write(ContextFileConstants.KEY_AGENTS, "RULES_KEEP");
        workspace.write(ContextFileConstants.KEY_SOUL, "SOUL_KEEP");
        workspace.write(ContextFileConstants.KEY_TOOLS, "TOOLS_KEEP");
        workspace.write(ContextFileConstants.KEY_IDENTITY, "IDENTITY_KEEP");
        workspace.write(ContextFileConstants.KEY_USER, "USER_KEEP");
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        new SectionMemoryManager(
                                repeat("LONG_MEMORY", 1200), repeat("RECENT_MEMORY", 1200)),
                        env.globalSettingRepository,
                        workspace);

        String prompt = service.buildSystemPrompt("MEMORY:budget:user");

        assertThat(prompt)
                .contains("RULES_KEEP", "SOUL_KEEP", "TOOLS_KEEP", "IDENTITY_KEEP", "USER_KEEP")
                .contains("[TRUNCATED: bootstrap prompt total budget]");
        assertThat(prompt.indexOf("USER_KEEP")).isLessThan(prompt.indexOf("LONG_MEMORY"));
        assertThat(prompt).doesNotContain("RECENT_MEMORY");
        assertThat(prompt.length()).isLessThanOrEqualTo(4000);
    }

    /** 高优先级内容较短时，剩余预算必须确定性地借给长期记忆，而不是留空。 */
    @Test
    void shouldBorrowUnusedPriorityBudgetDeterministically() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setBootstrapPromptFileCharLimit(12000);
        env.appConfig.getTask().setBootstrapPromptTotalCharBudget(3000);
        PersonaWorkspaceService workspace = new PersonaWorkspaceService(env.appConfig);
        workspace.write(ContextFileConstants.KEY_AGENTS, "SHORT_RULE");
        workspace.write(ContextFileConstants.KEY_SOUL, "SHORT_SOUL");
        workspace.write(ContextFileConstants.KEY_TOOLS, "SHORT_TOOLS");
        workspace.write(ContextFileConstants.KEY_IDENTITY, "SHORT_IDENTITY");
        workspace.write(ContextFileConstants.KEY_USER, "SHORT_USER");
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        new SectionMemoryManager(repeat("BORROWED_MEMORY", 800), ""),
                        env.globalSettingRepository,
                        workspace);

        String first = service.buildSystemPrompt("MEMORY:borrow:user");
        String second = service.buildSystemPrompt("MEMORY:borrow:user");

        assertThat(first)
                .isEqualTo(second)
                .contains("BORROWED_MEMORY")
                .contains("[TRUNCATED: content-type budget]")
                .hasSizeLessThanOrEqualTo(3000);
    }

    /** 字符预算截断不能把 emoji 的 UTF-16 代理对切成非法字符串。 */
    @Test
    void shouldNotSplitUnicodeSurrogatePairWhenTruncated() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setBootstrapPromptFileCharLimit(257);
        env.appConfig.getTask().setBootstrapPromptTotalCharBudget(1600);
        PersonaWorkspaceService workspace = new PersonaWorkspaceService(env.appConfig);
        workspace.write(
                ContextFileConstants.KEY_AGENTS, repeat("A", 218) + "😀" + repeat("B", 400));
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        env.memoryManager,
                        env.globalSettingRepository,
                        workspace);

        String prompt = service.buildSystemPrompt("MEMORY:unicode:user");

        assertThat(hasUnpairedSurrogate(prompt)).isFalse();
        assertThat(prompt.length()).isLessThanOrEqualTo(1600);
    }

    /** 记忆块即使按内容类型预算截断，也必须保留完整且成对的安全 fence。 */
    @Test
    void shouldKeepMemoryContextFenceBalancedAfterTruncation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setBootstrapPromptFileCharLimit(12000);
        env.appConfig.getTask().setBootstrapPromptTotalCharBudget(2800);
        FileContextService service =
                new FileContextService(
                        env.appConfig,
                        env.localSkillService,
                        new SectionMemoryManager(repeat("FENCED_MEMORY", 900), ""),
                        env.globalSettingRepository,
                        new PersonaWorkspaceService(env.appConfig));

        String prompt = service.buildSystemPrompt("MEMORY:fence-budget:user");

        assertThat(count(prompt, MemoryContextBoundary.OPEN_TAG))
                .isEqualTo(count(prompt, MemoryContextBoundary.CLOSE_TAG));
        assertThat(prompt).contains("[TRUNCATED: content-type budget]");
        assertThat(prompt.length()).isLessThanOrEqualTo(2800);
    }

    /** 兼容 Java 8 的简单重复文本构造，用于验证字符预算。 */
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    /** 判断文本是否包含未配对的 UTF-16 代理字符。 */
    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    /** 统计指定非空片段在文本中的出现次数。 */
    private static int count(String value, String needle) {
        int total = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            total++;
            offset += needle.length();
        }
        return total;
    }

    /** 提供可控长期与近期内容的结构化记忆管理器。 */
    private static final class SectionMemoryManager implements MemoryManager {
        /** 长期记忆正文。 */
        private final String longTerm;

        /** 当天记忆正文。 */
        private final String recent;

        /**
         * 创建测试记忆管理器。
         *
         * @param longTerm 长期记忆正文。
         * @param recent 当天记忆正文。
         */
        private SectionMemoryManager(String longTerm, String recent) {
            this.longTerm = longTerm;
            this.recent = recent;
        }

        /**
         * @return 兼容旧接口的记忆文本。
         */
        @Override
        public String buildSystemPrompt(String sourceKey) {
            return longTerm + "\n" + recent;
        }

        /**
         * @return 独立参与预算分配的长期与近期记忆段。
         */
        @Override
        public List<MemoryPromptSection> buildSystemPromptSections(String sourceKey) {
            return Arrays.asList(
                    new MemoryPromptSection(MemoryPromptSection.Type.LONG_TERM, "Memory", longTerm),
                    new MemoryPromptSection(
                            MemoryPromptSection.Type.RECENT, "Today Memory", recent));
        }

        /**
         * @return 空预取上下文。
         */
        @Override
        public String prefetch(String sourceKey, String userMessage) {
            return "";
        }

        /** 测试实现不持久化完成轮次。 */
        @Override
        public void syncTurn(String sourceKey, String userMessage, String assistantMessage) {}
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
