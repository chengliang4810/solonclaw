package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.web.DashboardChatService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;

public class DashboardChatServiceEventSinkTest {
    private static final String SECRET = "sk-1234567890abcdef";

    /** Dashboard 必须在创建异步运行和写入会话前拒绝未登记模型。 */
    @Test
    void shouldRejectUnregisteredModelBeforeStartingDashboardRun() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("main-model");
        provider.setModels(Collections.singletonList("main-model"));
        config.getProviders().put("default", provider);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("main-model");
        DashboardChatService service =
                new DashboardChatService(null, null, null, null, new LlmProviderService(config));
        try {
            assertThatThrownBy(
                            () ->
                                    service.startRun(
                                            org.noear.snack4.ONode.ofJson(
                                                    "{\"input\":\"hello\",\"session_id\":\"invalid-model-session\",\"provider\":\"default\",\"model\":\"missing-model\"}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未在 Provider default 中登记");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldRedactSecretsFromDashboardRunEvents() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("command", "curl https://example.test?api_key=" + SECRET);
        LlmResult result = new LlmResult();
        result.setReasoningText("reasoning bearer " + SECRET);

        sink.onReasoningDelta("token=" + SECRET);
        sink.onProgressUpdate("正在读取配置 token=" + SECRET);
        sink.onToolStarted("terminal", args);
        sink.onToolCompleted("terminal", "bearer " + SECRET, 12L);
        sink.onAttemptStarted("agent-" + SECRET, 1, "provider?api_key=" + SECRET, "model");
        sink.onAttemptCompleted("agent-" + SECRET, 1, "failed", "api_key=" + SECRET);
        sink.onCompressionDecision("agent-" + SECRET, true, "token=" + SECRET, 10, 8);
        sink.onRecoveryStarted("agent-" + SECRET, "retry?token=" + SECRET);
        sink.onFallback(
                "agent-" + SECRET,
                "primary?api_key=" + SECRET,
                "fallback",
                "fallback-model",
                "bearer " + SECRET);
        sink.onRunCompleted("session-1", "done", result);

        String events = drainEvents(state).toString();
        assertThat(events)
                .contains("token=***")
                .contains("api_key=***")
                .contains("bearer ***")
                .doesNotContain(SECRET);
    }

    /** 阶段说明必须使用独立 SSE 事件，不能伪装成最终正文增量。 */
    @Test
    void shouldEmitProgressUpdateSeparatelyFromAssistantDelta() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("run-progress", "session-progress");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onProgressUpdate("正在核对配置");
        sink.onAssistantDelta("最终答复");

        List<Object> emitted = drainEventList(state);
        assertThat(eventNames(emitted)).containsExactly("progress.update", "message.delta");
        assertThat(eventData(emitted.get(0))).containsEntry("text", "正在核对配置");
        assertThat(eventData(emitted.get(1))).containsEntry("delta", "最终答复");
    }

    @Test
    void shouldKeepAssistantDeltaUnredactedAsAssistantContent() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onAssistantDelta("assistant says " + SECRET);

        assertThat(drainEvents(state).toString()).contains("assistant says " + SECRET);
    }

    @Test
    void shouldExposeExplicitToolFailureState() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onToolCompleted("mcp_lookup", "MCP call timed out", "MCP call timed out", 12L);

        Map<String, Object> payload = drainEvents(state).get("tool.completed");
        assertThat(payload)
                .containsEntry("status", "error")
                .containsEntry("error", "MCP call timed out");
    }

    @Test
    void shouldEmitRunStartedOnlyOnceForDashboardRun() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("run-1", "session-initial");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onRunStarted("session-initial");
        sink.onRunStarted("session-orchestrator");

        BlockingQueue<Object> queue = events(state);
        assertThat(queue).hasSize(1);
        Object event = queue.peek();
        assertThat(eventName(event)).isEqualTo("run.started");
        assertThat(eventData(event).get("session_id")).isEqualTo("session-initial");
        assertThat(sessionId(state)).isEqualTo("session-orchestrator");
    }

    @Test
    void shouldCarryAgentRunIdOnTerminalDashboardEvents() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("web-run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onAttemptStarted("agent-run-1", 1, "provider", "model");
        sink.onRunCompleted("session-1", "done", null);

        Map<String, Map<String, Object>> events = drainEvents(state);
        assertThat(events.get("attempt.started").get("run_id")).isEqualTo("web-run-1");
        assertThat(events.get("attempt.started").get("agent_run_id")).isEqualTo("agent-run-1");
        assertThat(events.get("run.completed").get("run_id")).isEqualTo("web-run-1");
        assertThat(events.get("run.completed").get("agent_run_id")).isEqualTo("agent-run-1");
    }

    @Test
    void shouldEmitFinalReplyWhenRunCompletesWithoutAssistantDelta() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("web-run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onReasoningDelta("thinking");
        sink.onRunCompleted("session-1", "approval required", null);

        List<Object> emitted = drainEventList(state);
        assertThat(eventNames(emitted))
                .containsExactly("reasoning.delta", "message.delta", "run.completed");
        assertThat(eventData(emitted.get(1)).get("delta")).isEqualTo("approval required");
    }

    @Test
    void shouldNotDuplicateFinalReplyAfterAssistantDelta() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("web-run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onAssistantDelta("done");
        sink.onRunCompleted("session-1", "done", null);

        assertThat(eventNames(drainEventList(state)))
                .containsExactly("message.delta", "run.completed");
    }

    /** 候选切换时必须先撤销已推送正文，再发送备用模型的完整答复。 */
    @Test
    void shouldEmitAssistantResetBeforeFallbackReply() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);
        Object state = newState("web-run-reset", "session-reset");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onAssistantDelta("主模型部分答复");
        sink.onAssistantReset("content_filter");
        sink.onAssistantDelta("备用模型完整答复");
        sink.onRunCompleted("session-reset", "备用模型完整答复", null);

        List<Object> emitted = drainEventList(state);
        assertThat(eventNames(emitted))
                .containsExactly(
                        "message.delta", "message.reset", "message.delta", "run.completed");
        assertThat(eventData(emitted.get(1))).containsEntry("reason", "content_filter");
        assertThat(eventData(emitted.get(2))).containsEntry("delta", "备用模型完整答复");
    }

    @Test
    void shouldClassifySseClientDisconnects() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null, null);

        assertThat(isClientDisconnected("writeBuffer has closed")).isTrue();
        assertThat(isClientDisconnected("OutputStream has closed")).isTrue();
        assertThat(isClientDisconnected("connection reset by peer")).isTrue();
        assertThat(isClientDisconnected("disk write failed")).isFalse();
    }

    private Object newState(String runId, String sessionId) throws Exception {
        Class<?> stateClass =
                Class.forName("com.jimuqu.solon.claw.web.DashboardChatService$ChatRunState");
        Constructor<?> constructor = stateClass.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(runId, sessionId);
    }

    private ConversationEventSink newEventSink(DashboardChatService service, Object state)
            throws Exception {
        Class<?> sinkClass =
                Class.forName(
                        "com.jimuqu.solon.claw.web.DashboardChatService$DashboardRunEventSink");
        Constructor<?> constructor =
                sinkClass.getDeclaredConstructor(DashboardChatService.class, state.getClass());
        constructor.setAccessible(true);
        return (ConversationEventSink) constructor.newInstance(service, state);
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Object> events(Object state) throws Exception {
        Field field = state.getClass().getDeclaredField("events");
        field.setAccessible(true);
        return (BlockingQueue<Object>) field.get(state);
    }

    private Map<String, Map<String, Object>> drainEvents(Object state) throws Exception {
        Map<String, Map<String, Object>> snapshots =
                new LinkedHashMap<String, Map<String, Object>>();
        Object event;
        while ((event = events(state).poll()) != null) {
            snapshots.put(eventName(event), eventData(event));
        }
        return snapshots.isEmpty()
                ? Collections.<String, Map<String, Object>>emptyMap()
                : snapshots;
    }

    /** 依次取出事件，供需要验证同名事件数量与顺序的测试使用。 */
    private List<Object> drainEventList(Object state) throws Exception {
        List<Object> snapshots = new ArrayList<Object>();
        Object event;
        while ((event = events(state).poll()) != null) {
            snapshots.add(event);
        }
        return snapshots;
    }

    /** 提取事件名称列表，避免测试依赖事件对象的私有实现细节。 */
    private List<String> eventNames(List<Object> emitted) throws Exception {
        List<String> names = new ArrayList<String>();
        for (Object event : emitted) {
            names.add(eventName(event));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventData(Object event) throws Exception {
        Field field = event.getClass().getDeclaredField("data");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(event);
    }

    private String eventName(Object event) throws Exception {
        Field field = event.getClass().getDeclaredField("name");
        field.setAccessible(true);
        return (String) field.get(event);
    }

    private String sessionId(Object state) throws Exception {
        Field field = state.getClass().getDeclaredField("sessionId");
        field.setAccessible(true);
        return (String) field.get(state);
    }

    private boolean isClientDisconnected(String message) throws Exception {
        Class<?> disconnectsClass =
                Class.forName("com.jimuqu.solon.claw.web.DashboardClientDisconnects");
        Method method = disconnectsClass.getDeclaredMethod("isClientDisconnected", Throwable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, new RuntimeException(message));
    }
}
