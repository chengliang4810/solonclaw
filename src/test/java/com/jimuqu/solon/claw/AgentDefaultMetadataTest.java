package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.agent.AgentDefaultMetadata;
import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentProfileRepository;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.FixedSessionRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.UnsupportedAgentRunRepository;
import com.jimuqu.solon.claw.web.DashboardAgentService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证内置默认 Agent 的用户可见元数据由统一来源驱动，避免各入口文案漂移。 */
class AgentDefaultMetadataTest {
    @Test
    void defaultScopeUsesSharedMetadata() throws Exception {
        AgentRuntimeService runtimeService = new AgentRuntimeService(appConfig(), new EmptyAgentProfileRepository());

        AgentRuntimeScope scope = runtimeService.defaultScope();

        assertThat(scope.getAgentName()).isEqualTo(AgentRuntimeScope.DEFAULT_AGENT);
        assertThat(scope.getDisplayName()).isEqualTo(AgentDefaultMetadata.displayName());
        assertThat(scope.getDescription()).isEqualTo(AgentDefaultMetadata.description());
    }

    @Test
    void dashboardDefaultAgentUsesSharedMetadata() throws Exception {
        AgentRuntimeService runtimeService = new AgentRuntimeService(appConfig(), new EmptyAgentProfileRepository());
        DashboardAgentService dashboardService =
                new DashboardAgentService(
                        new AgentProfileService(new EmptyAgentProfileRepository(), runtimeService),
                        runtimeService,
                        new FixedSessionRepository(),
                        new EmptyAgentRunRepository());

        Map<String, Object> agent = dashboardService.get(AgentRuntimeScope.DEFAULT_AGENT, "");

        assertThat(agent)
                .containsEntry("display_name", AgentDefaultMetadata.displayName())
                .containsEntry("description", AgentDefaultMetadata.description());
    }

    @Test
    void slashShowDefaultUsesSharedMetadata() throws Exception {
        AgentProfileService service =
                new AgentProfileService(
                        new EmptyAgentProfileRepository(),
                        new AgentRuntimeService(appConfig(), new EmptyAgentProfileRepository()));

        String output = service.handleCommand("show default");

        assertThat(output)
                .contains("显示名: " + AgentDefaultMetadata.displayName())
                .contains("说明: " + AgentDefaultMetadata.description());
    }

    @Test
    void runtimePromptUsesSharedMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getGateway().setAllowAllUsers(true);
        env.conversationOrchestrator.handleIncoming(env.message("chat-a", "user-a", "你好"));

        FakeLlmGateway fake = (FakeLlmGateway) env.llmGateway;
        assertThat(fake.lastSystemPrompt)
                .contains("agent_display_name=" + AgentDefaultMetadata.displayName());
    }

    private static AppConfig appConfig() {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome("/tmp/solonclaw-runtime");
        config.getRuntime().setSkillsDir("/tmp/solonclaw-runtime/skills");
        config.getRuntime().setCacheDir("/tmp/solonclaw-runtime/cache");
        config.getWorkspace().setDir("/tmp/solonclaw-runtime/workspace");
        return config;
    }

    /** 空 Agent 仓储，默认 Agent 测试不应触发命名 Agent 查询结果。 */
    private static class EmptyAgentProfileRepository implements AgentProfileRepository {
        @Override
        public AgentProfile save(AgentProfile profile) {
            return profile;
        }

        @Override
        public AgentProfile findByName(String agentName) {
            return null;
        }

        @Override
        public List<AgentProfile> listAll() {
            return Collections.emptyList();
        }

        @Override
        public void deleteByName(String agentName) {}
    }

    /** 空运行仓储，默认 Agent 详情测试不需要读取运行列表。 */
    private static class EmptyAgentRunRepository extends UnsupportedAgentRunRepository {
        @Override
        public List<com.jimuqu.solon.claw.core.model.AgentRunRecord> listBySession(
                String sessionId, int limit) {
            return Collections.emptyList();
        }
    }
}
