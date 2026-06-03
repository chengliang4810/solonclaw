package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class FileContextServiceTest {
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
    void shouldPlaceToolsBeforeIdentityAndUserWithoutHeartbeatInNormalPrompt()
            throws Exception {
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
