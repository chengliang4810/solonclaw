package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DelegateTools;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DelegationServiceTest {
    @Test
    void shouldKeepParentBindingIsolatedWhenDelegating() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord parent = env.sessionRepository.bindNewSession("MEMORY:room-a:user-a");

        DelegationResult result =
                env.delegationService.delegateSingle("MEMORY:room-a:user-a", "sub task", "ctx");

        assertThat(result.getContent()).contains("echo:");
        assertThat(result.getSessionId()).isNotBlank();
        assertThat(env.sessionRepository.getBoundSession("MEMORY:room-a:user-a").getSessionId())
                .isEqualTo(parent.getSessionId());
        assertThat(env.sessionRepository.findById(result.getSessionId()).getParentSessionId())
                .isEqualTo(parent.getSessionId());
    }

    @Test
    void shouldSupportBatchDelegation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord parent = env.sessionRepository.bindNewSession("MEMORY:room-a:user-a");
        DelegationTask first = new DelegationTask();
        first.setName("one");
        first.setPrompt("task one");
        DelegationTask second = new DelegationTask();
        second.setName("two");
        second.setPrompt("task two");

        List<DelegationResult> results =
                env.delegationService.delegateBatch(
                        "MEMORY:room-a:user-a", Arrays.asList(first, second));
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getContent()).contains("echo:");
        assertThat(results.get(1).getContent()).contains("echo:");
        assertThat(
                        env.sessionRepository
                                .findById(results.get(0).getSessionId())
                                .getParentSessionId())
                .isEqualTo(parent.getSessionId());
        assertThat(
                        env.sessionRepository
                                .findById(results.get(1).getSessionId())
                                .getParentSessionId())
                .isEqualTo(parent.getSessionId());
    }

    @Test
    void shouldIsolateBatchFailuresWithoutBreakingSuccessfulChildren() throws Exception {
        LlmGateway failingGateway =
                new FakeLlmGateway() {
                    @Override
                    public LlmResult chat(
                            SessionRecord session,
                            String systemPrompt,
                            String userMessage,
                            List<Object> toolObjects)
                            throws Exception {
                        if (userMessage.contains("must fail")) {
                            throw new IllegalStateException(
                                    "simulated delegation failure ghp_1234567890abcdef");
                        }
                        return super.chat(session, systemPrompt, userMessage, toolObjects);
                    }
                };
        TestEnvironment env = TestEnvironment.withLlm(failingGateway);
        env.sessionRepository.bindNewSession("MEMORY:room-a:user-a");

        DelegationTask first = new DelegationTask();
        first.setName("ok");
        first.setPrompt("task ok");
        DelegationTask second = new DelegationTask();
        second.setName("fail");
        second.setPrompt("must fail");

        List<DelegationResult> results =
                env.delegationService.delegateBatch(
                        "MEMORY:room-a:user-a", Arrays.asList(first, second));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).isError()).isFalse();
        assertThat(results.get(0).getContent()).contains("echo:");
        assertThat(results.get(1).isError()).isTrue();
        assertThat(results.get(1).getContent())
                .contains("simulated delegation failure")
                .contains("***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldScopeDelegatedToolsetsToParentEnabledTools() throws Exception {
        RecordingToolGateway gateway = new RecordingToolGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        String parentSourceKey = "MEMORY:room-a:user-a";
        env.sessionRepository.bindNewSession(parentSourceKey);
        env.toolRegistry.disableTools(parentSourceKey, Arrays.asList("webfetch"));
        DelegationTask task = new DelegationTask();
        task.setName("web-child");
        task.setPrompt("use web tools");
        task.setToolsets(Arrays.asList("web"));

        DelegationResult result = env.delegationService.delegateSingle(parentSourceKey, task);

        assertThat(result.isError()).isFalse();
        assertThat(gateway.lastToolObjects).hasSize(2);
        assertThat(gateway.lastToolObjects.toString()).contains("SafeCodeSearchTool", "SafeWebsearchTool");
        assertThat(env.toolRegistry.resolveEnabledToolNames(result.getSourceKey()))
                .containsExactly("codesearch", "websearch");
    }

    @Test
    void delegateToolShouldParseToolsetsForSingleAndBatchTasks() throws Exception {
        RecordingDelegationService service = new RecordingDelegationService();
        DelegateTools tools = new DelegateTools(service, "MEMORY:room-a:user-a");

        tools.delegateTask("single", "single goal", null, null, null, "web, terminal", null, null);
        tools.delegateTask(
                "batch",
                null,
                "[{\"prompt\":\"batch goal\",\"toolsets\":[\"web\",\"file\"]},{\"prompt\":\"default tools\"}]",
                null,
                null,
                "ignored",
                null,
                null);

        assertThat(service.singleTask.getToolsets()).containsExactly("web", "terminal");
        assertThat(service.batchTasks.get(0).getToolsets()).containsExactly("web", "file");
        assertThat(service.batchTasks.get(1).getToolsets()).isEmpty();
        assertThat(service.batchTasks.get(1).getAllowedTools()).isEmpty();
    }

    @Test
    void delegateToolShouldRedactErrors() throws Exception {
        DelegateTools missingService = new DelegateTools(null, "MEMORY:room-a:user-a");
        String notReady = missingService.delegateTask("single", "ghp_1234567890abcdef", null, null, null, null, null, null);
        assertThat(notReady).contains("\"success\":false").doesNotContain("ghp_1234567890abcdef");

        DelegateTools failing =
                new DelegateTools(
                        new FailingDelegationService(),
                        "MEMORY:room-a:user-a");
        String failed =
                failing.delegateTask(
                        "single",
                        "prompt-ghp_1234567890abcdef",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(failed)
                .contains("\"success\":false")
                .contains("prompt-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    private static class RecordingToolGateway extends FakeLlmGateway {
        private List<Object> lastToolObjects = new ArrayList<Object>();

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            lastToolObjects = new ArrayList<Object>(toolObjects);
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }
    }

    private static class RecordingDelegationService implements DelegationService {
        private DelegationTask singleTask;
        private List<DelegationTask> batchTasks = new ArrayList<DelegationTask>();

        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context) {
            DelegationResult result = new DelegationResult();
            result.setContent("ok");
            return result;
        }

        @Override
        public DelegationResult delegateSingle(String sourceKey, DelegationTask task) {
            this.singleTask = task;
            DelegationResult result = new DelegationResult();
            result.setContent("ok");
            return result;
        }

        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks) {
            this.batchTasks = tasks;
            DelegationResult result = new DelegationResult();
            result.setContent("ok");
            return Arrays.asList(result);
        }

        @Override
        public List<Map<String, Object>> activeSubagents() {
            return java.util.Collections.emptyList();
        }
    }

    private static class FailingDelegationService implements DelegationService {
        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context)
                throws Exception {
            throw new IllegalArgumentException("delegate failed: " + prompt);
        }

        @Override
        public DelegationResult delegateSingle(String sourceKey, DelegationTask task)
                throws Exception {
            throw new IllegalArgumentException("delegate failed: " + task.getPrompt());
        }

        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks)
                throws Exception {
            throw new IllegalArgumentException("delegate batch failed: ghp_1234567890abcdef");
        }
    }
}
