package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.engine.DefaultDelegationService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.ConversationOrchestratorHolder;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DelegateTools;
import com.jimuqu.solon.claw.tool.runtime.TodoTools;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

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

        DeliveryRequest childTarget =
                SourceKeySupport.toDeliveryRequest(result.getSourceKey(), "child reply");
        assertThat(childTarget.getPlatform()).isEqualTo(PlatformType.MEMORY);
        assertThat(childTarget.getChatId()).isEqualTo("room-a");
        assertThat(childTarget.getUserId()).isEqualTo("user-a");
        assertThat(childTarget.getThreadId())
                .startsWith("delegate-")
                .contains(result.getSubagentId());

        TodoTools parentTodo = new TodoTools(env.appConfig, "MEMORY:room-a:user-a");
        TodoTools childTodo = new TodoTools(env.appConfig, result.getSourceKey());
        parentTodo.todo(Arrays.asList(item("parent", "parent task", "pending")), false);
        childTodo.todo(Arrays.asList(item("child", "child task", "in_progress")), false);

        ONode parentList = ONode.ofJson(parentTodo.todo(null, null));
        ONode childList = ONode.ofJson(childTodo.todo(null, null));
        assertThat(parentList.get("todos").size()).isEqualTo(1);
        assertThat(parentList.get("todos").get(0).get("id").getString()).isEqualTo("parent");
        assertThat(childList.get("todos").size()).isEqualTo(1);
        assertThat(childList.get("todos").get(0).get("id").getString()).isEqualTo("child");
    }

    @Test
    void shouldCreateThreadScopedChildSourceKeyForThreadedParents() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String parentSourceKey = "MEMORY:room-a:thread-1:user-a";
        SessionRecord parent = env.sessionRepository.bindNewSession(parentSourceKey);

        DelegationResult result =
                env.delegationService.delegateSingle(parentSourceKey, "sub task", "ctx");

        DeliveryRequest childTarget =
                SourceKeySupport.toDeliveryRequest(result.getSourceKey(), "child reply");
        assertThat(childTarget.getPlatform()).isEqualTo(PlatformType.MEMORY);
        assertThat(childTarget.getChatId()).isEqualTo("room-a");
        assertThat(childTarget.getUserId()).isEqualTo("user-a");
        assertThat(childTarget.getThreadId())
                .startsWith("thread-1-delegate-")
                .contains(result.getSubagentId());
        assertThat(env.sessionRepository.findById(result.getSessionId()).getParentSessionId())
                .isEqualTo(parent.getSessionId());
    }

    /** 命名 Profile 的子 Agent 来源键必须保留 Profile 路由边界。 */
    @Test
    void shouldKeepProfilePrefixInChildSourceKey() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String parentSourceKey = "profile:worker:MEMORY:room-a:thread-1:user-a";
        env.sessionRepository.bindNewSession(parentSourceKey);

        DelegationResult result =
                env.delegationService.delegateSingle(parentSourceKey, "sub task", "ctx");

        assertThat(result.getSourceKey()).startsWith("profile:worker:MEMORY:room-a:");
        DeliveryRequest childTarget =
                SourceKeySupport.toDeliveryRequest(result.getSourceKey(), "child reply");
        assertThat(childTarget.getProfile()).isEqualTo("worker");
        assertThat(childTarget.getThreadId())
                .startsWith("thread-1-delegate-")
                .contains(result.getSubagentId());
    }

    /** 验证子代理继承父会话当前生效的模型、推理和快速模式覆盖。 */
    @Test
    void shouldInheritParentModelRequestOverrides() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String parentSourceKey = "MEMORY:room-a:user-a";
        SessionRecord parent = env.sessionRepository.bindNewSession(parentSourceKey);
        parent.setModelOverride("gpt-5.4");
        parent.setServiceTierOverride("priority");
        parent.setReasoningEffortOverride("high");
        env.sessionRepository.save(parent);

        DelegationResult result =
                env.delegationService.delegateSingle(parentSourceKey, "sub task", "ctx");

        SessionRecord child = env.sessionRepository.findById(result.getSessionId());
        assertThat(child.getParentSessionId()).isEqualTo(parent.getSessionId());
        assertThat(child.getModelOverride()).isEqualTo("gpt-5.4");
        assertThat(child.getServiceTierOverride()).isEqualTo("priority");
        assertThat(child.getReasoningEffortOverride()).isEqualTo("high");
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

    /** 深层 orchestrator 必须沿父运行链累计深度，不能把三层以后继续视为第二层。 */
    @Test
    void shouldRejectDelegationBeyondRecursiveParentDepth() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setSubagentMaxDepth(3);
        env.agentRunRepository.saveRun(run("root", "conversation", null));
        env.agentRunRepository.saveRun(run("child-1", "subagent", "root"));
        env.agentRunRepository.saveRun(run("child-2", "subagent", "child-1"));
        env.agentRunRepository.saveRun(run("child-3", "subagent", "child-2"));
        DefaultDelegationService service =
                new DefaultDelegationService(
                        new ConversationOrchestratorHolder(),
                        null,
                        env.sessionRepository,
                        env.agentRunRepository,
                        env.appConfig,
                        null);
        AgentRunContext context =
                new AgentRunContext(null, "child-3", "child-session", "MEMORY:room-a:user-a");
        context.setRunKind("subagent");
        DelegationTask task = new DelegationTask();
        task.setPrompt("must be rejected");

        AgentRunContext.setCurrent(context);
        DelegationResult result;
        try {
            result = service.delegateSingle("MEMORY:room-a:user-a", task);
        } finally {
            AgentRunContext.setCurrent(null);
        }

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("depth limit exceeded");
    }

    /** 启动时只能收敛遗留的活动记录，不能改写已正常结束的子 Agent。 */
    @Test
    void shouldReconcileStaleActiveSubagentsAsInterrupted() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SubagentRunRecord stale = subagent("stale", "parent", "running", true, 0L);
        SubagentRunRecord completed = subagent("completed", "parent", "success", false, 123L);
        env.agentRunRepository.saveSubagentRun(stale);
        env.agentRunRepository.saveSubagentRun(completed);
        DefaultDelegationService service =
                new DefaultDelegationService(
                        new ConversationOrchestratorHolder(),
                        null,
                        env.sessionRepository,
                        env.agentRunRepository,
                        env.appConfig,
                        null);
        long before = System.currentTimeMillis();

        int reconciled = service.reconcileStaleSubagents();

        assertThat(reconciled).isEqualTo(1);
        List<SubagentRunRecord> records = env.agentRunRepository.listSubagents("parent");
        SubagentRunRecord reconciledRecord =
                records.stream()
                        .filter(record -> "stale".equals(record.getSubagentId()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        SubagentRunRecord completedRecord =
                records.stream()
                        .filter(record -> "completed".equals(record.getSubagentId()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        assertThat(reconciledRecord.getStatus()).isEqualTo("interrupted");
        assertThat(reconciledRecord.isActive()).isFalse();
        assertThat(reconciledRecord.isInterruptRequested()).isTrue();
        assertThat(reconciledRecord.getFinishedAt()).isGreaterThanOrEqualTo(before);
        assertThat(reconciledRecord.getHeartbeatAt()).isEqualTo(reconciledRecord.getFinishedAt());
        assertThat(completedRecord.getStatus()).isEqualTo("success");
        assertThat(completedRecord.getFinishedAt()).isEqualTo(123L);
    }

    /** batch 等待线程被取消后必须保留中断标记，并停止等待其余子任务。 */
    @Test
    void shouldRestoreInterruptWhenBatchWaitIsInterrupted() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        DefaultDelegationService service =
                new DefaultDelegationService(new ConversationOrchestratorHolder(), null, null) {
                    @Override
                    public DelegationResult delegateSingle(String sourceKey, DelegationTask task)
                            throws Exception {
                        childStarted.countDown();
                        try {
                            Thread.sleep(30000L);
                        } catch (InterruptedException e) {
                            childInterrupted.countDown();
                            throw e;
                        }
                        return new DelegationResult();
                    }
                };
        DelegationTask task = new DelegationTask();
        task.setPrompt("slow child");
        Thread batch =
                new Thread(
                        () -> {
                            try {
                                service.delegateBatch("MEMORY:room-a:user-a", Arrays.asList(task));
                            } catch (Exception ignored) {
                                // 测试只关心调用返回后的中断状态。
                            }
                            interruptRestored.set(Thread.currentThread().isInterrupted());
                        });

        batch.start();
        assertThat(childStarted.await(2, TimeUnit.SECONDS)).isTrue();
        batch.interrupt();
        batch.join(2000L);

        assertThat(batch.isAlive()).isFalse();
        assertThat(interruptRestored).isTrue();
        assertThat(childInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
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
        assertThat(gateway.lastToolObjects).hasSize(3);
        assertThat(gateway.lastToolObjects.toString())
                .contains("SafeCodeSearchTool", "SafeWebsearchTool", "SafeWebExtractTool");
        assertThat(env.toolRegistry.resolveEnabledToolNames(result.getSourceKey()))
                .containsExactly("codesearch", "websearch", "web_extract");
    }

    @Test
    void delegateToolShouldMapSingleAndBatchTasks() throws Exception {
        RecordingDelegationService service = new RecordingDelegationService();
        DelegateTools tools = new DelegateTools(service, "MEMORY:room-a:user-a");
        DelegateTools.DelegateTaskInput first =
                delegationInput("batch goal", "batch context", "orchestrator");
        DelegateTools.DelegateTaskInput second = delegationInput("default role", null, null);

        tools.delegateTask("single goal", "single context", null, "leaf", Boolean.FALSE);
        tools.delegateTask(null, "shared context", Arrays.asList(first, second), "leaf", null);

        assertThat(service.singleTask.getPrompt()).isEqualTo("single goal");
        assertThat(service.singleTask.getContext()).isEqualTo("single context");
        assertThat(service.singleTask.getRole()).isEqualTo("leaf");
        assertThat(service.batchTasks.get(0).getContext()).isEqualTo("batch context");
        assertThat(service.batchTasks.get(0).getRole()).isEqualTo("orchestrator");
        assertThat(service.batchTasks.get(1).getContext()).isEqualTo("shared context");
        assertThat(service.batchTasks.get(1).getRole()).isEqualTo("leaf");
    }

    /** 顶层 delegate_task 必须立即返回后台句柄，并在子任务完成后回流父会话。 */
    @Test
    void delegateToolShouldReturnHandleAndReenterParentConversation() throws Exception {
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch completionDelivered = new CountDownLatch(1);
        CountDownLatch channelDelivered = new CountDownLatch(1);
        AtomicReference<String> completionText = new AtomicReference<String>();
        AtomicReference<DeliveryRequest> channelDelivery = new AtomicReference<DeliveryRequest>();
        ConversationOrchestratorHolder holder = new ConversationOrchestratorHolder();
        holder.set(
                new ConversationOrchestrator() {
                    @Override
                    public com.jimuqu.solon.claw.core.model.GatewayReply handleIncoming(
                            com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        if (message.getText().startsWith("[后台委派完成]")) {
                            completionText.set(message.getText());
                            completionDelivered.countDown();
                        }
                        return com.jimuqu.solon.claw.core.model.GatewayReply.ok("continued");
                    }

                    @Override
                    public com.jimuqu.solon.claw.core.model.GatewayReply resumePending(
                            String sourceKey) {
                        return com.jimuqu.solon.claw.core.model.GatewayReply.ok("resumed");
                    }

                    @Override
                    public com.jimuqu.solon.claw.core.model.GatewayReply runScheduled(
                            com.jimuqu.solon.claw.core.model.GatewayMessage syntheticMessage) {
                        return com.jimuqu.solon.claw.core.model.GatewayReply.ok("scheduled");
                    }
                });
        DefaultDelegationService service =
                new DefaultDelegationService(
                        holder,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new com.jimuqu.solon.claw.core.service.DeliveryService() {
                            @Override
                            public void deliver(DeliveryRequest request) {
                                channelDelivery.set(request);
                                channelDelivered.countDown();
                            }

                            @Override
                            public List<com.jimuqu.solon.claw.core.model.ChannelStatus> statuses() {
                                return java.util.Collections.emptyList();
                            }
                        }) {
                    @Override
                    public List<DelegationResult> delegateBatch(
                            String sourceKey, List<DelegationTask> tasks) throws Exception {
                        releaseChild.await(5, TimeUnit.SECONDS);
                        DelegationResult result = new DelegationResult();
                        result.setContent("child summary");
                        return Arrays.asList(result);
                    }
                };
        String sourceKey = "profile:worker:FEISHU:room-a:thread-a:user-a";
        DelegateTools tools = new DelegateTools(service, sourceKey);
        AgentRunContext parent =
                new AgentRunContext(null, "parent-run", "parent-session", sourceKey);
        parent.setRunKind("conversation");

        AgentRunContext.setCurrent(parent);
        String handle;
        try {
            handle = tools.delegateTask("slow goal", null, null, null, Boolean.FALSE);
        } finally {
            AgentRunContext.setCurrent(null);
        }

        assertThat(handle)
                .contains("\"status\":\"dispatched\"")
                .contains("\"mode\":\"background\"");
        assertThat(completionDelivered.getCount()).isEqualTo(1L);
        releaseChild.countDown();
        assertThat(completionDelivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(channelDelivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(completionText.get()).contains("[后台委派完成]").contains("child summary");
        assertThat(channelDelivery.get()).isNotNull();
        assertThat(channelDelivery.get().getProfile()).isEqualTo("worker");
        assertThat(channelDelivery.get().getPlatform()).isEqualTo(PlatformType.FEISHU);
        assertThat(channelDelivery.get().getChatId()).isEqualTo("room-a");
        assertThat(channelDelivery.get().getThreadId()).isEqualTo("thread-a");
        assertThat(channelDelivery.get().getUserId()).isEqualTo("user-a");
    }

    /** 后台委派完成前父来源键切到新会话时，旧结果必须拒绝回流。 */
    @Test
    void backgroundDelegationShouldNotReenterAfterNewSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-a:user-a";
        SessionRecord parent = env.sessionRepository.bindNewSession(sourceKey);
        CountDownLatch batchStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch batchReturned = new CountDownLatch(1);
        CountDownLatch completionDelivered = new CountDownLatch(1);
        ConversationOrchestratorHolder holder = new ConversationOrchestratorHolder();
        holder.set(
                new ConversationOrchestrator() {
                    @Override
                    public com.jimuqu.solon.claw.core.model.GatewayReply handleIncoming(
                            com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        completionDelivered.countDown();
                        return com.jimuqu.solon.claw.core.model.GatewayReply.ok("continued");
                    }

                    @Override
                    public com.jimuqu.solon.claw.core.model.GatewayReply resumePending(String key) {
                        return com.jimuqu.solon.claw.core.model.GatewayReply.ok("resumed");
                    }

                    @Override
                    public com.jimuqu.solon.claw.core.model.GatewayReply runScheduled(
                            com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        return com.jimuqu.solon.claw.core.model.GatewayReply.ok("scheduled");
                    }
                });
        DefaultDelegationService service =
                new DefaultDelegationService(holder, null, env.sessionRepository) {
                    @Override
                    public List<DelegationResult> delegateBatch(
                            String key, List<DelegationTask> tasks) throws Exception {
                        batchStarted.countDown();
                        releaseChild.await(5, TimeUnit.SECONDS);
                        batchReturned.countDown();
                        return java.util.Collections.emptyList();
                    }
                };
        AgentRunContext context =
                new AgentRunContext(null, "parent-run", parent.getSessionId(), sourceKey);
        context.setRunKind("conversation");
        DelegationTask task = new DelegationTask();
        task.setPrompt("slow child");

        AgentRunContext.setCurrent(context);
        try {
            service.delegateInBackground(sourceKey, Arrays.asList(task));
        } finally {
            AgentRunContext.setCurrent(null);
        }
        assertThat(batchStarted.await(2, TimeUnit.SECONDS)).isTrue();
        SessionRecord replacement = env.sessionRepository.bindNewSession(sourceKey);
        assertThat(replacement.getSessionId()).isNotEqualTo(parent.getSessionId());
        releaseChild.countDown();

        assertThat(batchReturned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(completionDelivered.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    /** 子 Agent 内的 orchestrator 委派必须同步返回，便于在本轮汇总 worker 结果。 */
    @Test
    void delegateToolShouldRemainSynchronousInsideSubagent() throws Exception {
        CountDownLatch batchCalled = new CountDownLatch(1);
        DefaultDelegationService service =
                new DefaultDelegationService(new ConversationOrchestratorHolder(), null, null) {
                    @Override
                    public List<DelegationResult> delegateBatch(
                            String sourceKey, List<DelegationTask> tasks) {
                        batchCalled.countDown();
                        DelegationResult result = new DelegationResult();
                        result.setContent("nested result");
                        return Arrays.asList(result);
                    }
                };
        DelegateTools tools = new DelegateTools(service, "MEMORY:room-a:delegate-1:user-a");
        AgentRunContext child =
                new AgentRunContext(
                        null, "child-run", "child-session", "MEMORY:room-a:delegate-1:user-a");
        child.setRunKind("subagent");

        AgentRunContext.setCurrent(child);
        String result;
        try {
            DelegateTools.DelegateTaskInput input = delegationInput("worker", null, "leaf");
            result = tools.delegateTask(null, null, Arrays.asList(input), "orchestrator", true);
        } finally {
            AgentRunContext.setCurrent(null);
        }

        assertThat(batchCalled.getCount()).isZero();
        assertThat(result).contains("nested result").doesNotContain("\"status\":\"dispatched\"");
    }

    @Test
    void shouldCarryProfileScopeIntoParallelDelegationWorkers() throws Exception {
        List<String> observations = java.util.Collections.synchronizedList(new ArrayList<String>());
        DefaultDelegationService service =
                new DefaultDelegationService(new ConversationOrchestratorHolder(), null, null) {
                    @Override
                    public DelegationResult delegateSingle(String sourceKey, DelegationTask task) {
                        ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
                        observations.add(
                                (current == null ? "default" : current.getProfile())
                                        + ":"
                                        + ProfileRuntimeScope.environmentValue(
                                                "PROFILE_ASYNC_MARKER"));
                        DelegationResult result = new DelegationResult();
                        result.setContent("done");
                        return result;
                    }
                };
        DelegationTask task = new DelegationTask();
        task.setName("worker");
        task.setPrompt("check scope");

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a",
                        java.nio.file.Files.createTempDirectory("delegate-profile-a"),
                        java.util.Collections.singletonMap("PROFILE_ASYNC_MARKER", "env-a"),
                        null)) {
            service.delegateBatch("MEMORY:a-room:a-user", Arrays.asList(task));
        }
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "b",
                        java.nio.file.Files.createTempDirectory("delegate-profile-b"),
                        java.util.Collections.singletonMap("PROFILE_ASYNC_MARKER", "env-b"),
                        null)) {
            service.delegateBatch("MEMORY:b-room:b-user", Arrays.asList(task));
        }

        assertThat(observations).containsExactly("a:env-a", "b:env-b");
    }

    @Test
    void shouldRestoreProfileScopeWhenBackgroundDelegationPoolIsReused() throws Exception {
        com.jimuqu.solon.claw.config.AppConfig config =
                new com.jimuqu.solon.claw.config.AppConfig();
        config.getTask().setSubagentMaxConcurrency(1);
        LinkedBlockingQueue<String> observations = new LinkedBlockingQueue<String>();
        DefaultDelegationService service =
                new DefaultDelegationService(
                        new ConversationOrchestratorHolder(),
                        null,
                        null,
                        null,
                        config,
                        null,
                        null) {
                    @Override
                    public List<DelegationResult> delegateBatch(
                            String sourceKey, List<DelegationTask> tasks) {
                        ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
                        observations.add(
                                (current == null ? "default" : current.getProfile())
                                        + ":"
                                        + ProfileRuntimeScope.environmentValue(
                                                "PROFILE_ASYNC_MARKER"));
                        DelegationResult result = new DelegationResult();
                        result.setContent("done");
                        return Arrays.asList(result);
                    }
                };
        DelegationTask task = new DelegationTask();
        task.setName("worker");
        task.setPrompt("background scope");

        dispatchBackground(service, task, "a", "env-a");
        assertThat(observations.poll(2, TimeUnit.SECONDS)).isEqualTo("a:env-a");
        dispatchBackground(service, task, "b", "env-b");
        assertThat(observations.poll(2, TimeUnit.SECONDS)).isEqualTo("b:env-b");
    }

    @Test
    void delegateToolShouldRedactErrors() throws Exception {
        DelegateTools missingService = new DelegateTools(null, "MEMORY:room-a:user-a");
        String notReady =
                missingService.delegateTask("ghp_1234567890abcdef", null, null, null, null);
        assertThat(notReady)
                .contains("\"status\":\"error\"")
                .doesNotContain("ghp_1234567890abcdef");

        DelegateTools failing =
                new DelegateTools(new FailingDelegationService(), "MEMORY:room-a:user-a");
        String failed = failing.delegateTask("prompt-ghp_1234567890abcdef", null, null, null, null);

        assertThat(failed)
                .contains("\"status\":\"error\"")
                .contains("prompt-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void delegateToolShouldRedactSuccessResultsOnly() throws Exception {
        RecordingDelegationService service = new RecordingDelegationService();
        service.singleContent = "single Authorization: Bearer ghp_delegatesingle12345";
        service.batchContent = "batch token=ghp_delegatebatch12345";
        DelegateTools tools = new DelegateTools(service, "MEMORY:room-a:user-a");

        String single =
                tools.delegateTask("prompt token=ghp_delegateprompt12345", null, null, null, null);
        DelegateTools.DelegateTaskInput batchInput =
                delegationInput("batch token=ghp_delegateprompt12345", null, null);
        String batch = tools.delegateTask(null, null, Arrays.asList(batchInput), null, null);

        assertThat(single)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_delegatesingle12345");
        assertThat(batch).contains("batch token=***").doesNotContain("ghp_delegatebatch12345");
        assertThat(service.singleTask.getPrompt()).contains("ghp_delegateprompt12345");
        assertThat(service.batchTasks.get(0).getPrompt()).contains("ghp_delegateprompt12345");
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

    private static TodoTools.TodoItem item(String id, String content, String status) {
        TodoTools.TodoItem item = new TodoTools.TodoItem();
        item.setId(id);
        item.setContent(content);
        item.setStatus(status);
        return item;
    }

    /** 创建委派深度测试使用的最小运行记录。 */
    private static AgentRunRecord run(String runId, String runKind, String parentRunId) {
        AgentRunRecord record = new AgentRunRecord();
        record.setRunId(runId);
        record.setSessionId(runId + "-session");
        record.setSourceKey("MEMORY:room-a:user-a");
        record.setRunKind(runKind);
        record.setParentRunId(parentRunId);
        record.setStatus("success");
        return record;
    }

    /** 构造持久化子 Agent 记录，供启动收敛测试复用。 */
    private static SubagentRunRecord subagent(
            String subagentId, String parentRunId, String status, boolean active, long finishedAt) {
        SubagentRunRecord record = new SubagentRunRecord();
        record.setSubagentId(subagentId);
        record.setParentRunId(parentRunId);
        record.setStatus(status);
        record.setActive(active);
        record.setStartedAt(1L);
        record.setFinishedAt(finishedAt);
        record.setHeartbeatAt(1L);
        return record;
    }

    /**
     * 创建结构化委派任务测试输入。
     *
     * @param goal 子任务目标。
     * @param context 子任务上下文。
     * @param role 子任务角色。
     * @return 返回委派工具输入。
     */
    private static DelegateTools.DelegateTaskInput delegationInput(
            String goal, String context, String role) {
        DelegateTools.DelegateTaskInput input = new DelegateTools.DelegateTaskInput();
        input.setGoal(goal);
        input.setContext(context);
        input.setRole(role);
        return input;
    }

    /** 在指定 Profile 下向固定大小为一的后台池提交任务，强制覆盖线程复用路径。 */
    private void dispatchBackground(
            DefaultDelegationService service, DelegationTask task, String profile, String marker)
            throws Exception {
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        profile,
                        java.nio.file.Files.createTempDirectory("delegate-background-" + profile),
                        java.util.Collections.singletonMap("PROFILE_ASYNC_MARKER", marker),
                        null)) {
            service.delegateInBackground(
                    "MEMORY:" + profile + "-room:" + profile + "-user", Arrays.asList(task));
        }
    }

    private static class RecordingDelegationService implements DelegationService {
        private DelegationTask singleTask;
        private List<DelegationTask> batchTasks = new ArrayList<DelegationTask>();
        private String singleContent = "ok";
        private String batchContent = "ok";

        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context) {
            DelegationResult result = new DelegationResult();
            result.setContent(singleContent);
            return result;
        }

        @Override
        public DelegationResult delegateSingle(String sourceKey, DelegationTask task) {
            this.singleTask = task;
            DelegationResult result = new DelegationResult();
            result.setContent(singleContent);
            return result;
        }

        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks) {
            this.batchTasks = tasks;
            DelegationResult result = new DelegationResult();
            result.setContent(batchContent);
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
