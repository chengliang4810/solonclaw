package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.web.DashboardChatService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;

public class DashboardChatServiceEventSinkTest {
    private static final String SECRET = "sk-1234567890abcdef";

    @Test
    void shouldRedactSecretsFromDashboardRunEvents() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null);
        Object state = newState("run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("command", "curl https://example.test?api_key=" + SECRET);
        LlmResult result = new LlmResult();
        result.setReasoningText("reasoning bearer " + SECRET);

        sink.onReasoningDelta("token=" + SECRET);
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
                "bearer " + SECRET);
        sink.onRunCompleted("session-1", "done", result);

        String events = drainEvents(state).toString();
        assertThat(events)
                .contains("token=***")
                .contains("api_key=***")
                .contains("bearer ***")
                .doesNotContain(SECRET);
    }

    @Test
    void shouldKeepAssistantDeltaUnredactedAsAssistantContent() throws Exception {
        DashboardChatService service = new DashboardChatService(null, null, null, null);
        Object state = newState("run-1", "session-1");
        ConversationEventSink sink = newEventSink(service, state);

        sink.onAssistantDelta("assistant says " + SECRET);

        assertThat(drainEvents(state).toString()).contains("assistant says " + SECRET);
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
                Class.forName("com.jimuqu.solon.claw.web.DashboardChatService$DashboardRunEventSink");
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
}
