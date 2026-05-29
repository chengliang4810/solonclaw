package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple publish-subscribe event bus for ACP tool call lifecycle events.
 * Events are fired synchronously on the calling thread.
 */
public class AcpEventBus {

    /** Supported ACP event types. */
    public enum EventType {
        TOOL_CALL_STARTED,
        TOOL_CALL_COMPLETED,
        TOOL_CALL_FAILED
    }

    /** Listener interface for ACP events. */
    public interface Listener {
        void onEvent(EventType type, String toolName, Map<String, Object> payload);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    /** Registers a listener. Safe to call from any thread. */
    public void subscribe(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Removes a previously registered listener. */
    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    /** Returns the number of currently registered listeners. */
    public int listenerCount() {
        return listeners.size();
    }

    /**
     * Publishes a {@link EventType#TOOL_CALL_STARTED} event.
     *
     * @param toolName  name of the tool being invoked
     * @param args      tool arguments (may be null)
     */
    public void publishToolStarted(String toolName, Map<String, Object> args) {
        publish(EventType.TOOL_CALL_STARTED, toolName, args);
    }

    /**
     * Publishes a {@link EventType#TOOL_CALL_COMPLETED} event.
     *
     * @param toolName    name of the tool that completed
     * @param durationMs  elapsed time in milliseconds
     * @param result      tool result payload (may be null)
     */
    public void publishToolCompleted(String toolName, long durationMs, Map<String, Object> result) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("duration_ms", Long.valueOf(durationMs));
        if (result != null) {
            payload.putAll(result);
        }
        publish(EventType.TOOL_CALL_COMPLETED, toolName, payload);
    }

    /**
     * Publishes a {@link EventType#TOOL_CALL_FAILED} event.
     *
     * @param toolName  name of the tool that failed
     * @param error     error message or exception description
     */
    public void publishToolFailed(String toolName, String error) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("error", StrUtil.nullToEmpty(error));
        publish(EventType.TOOL_CALL_FAILED, toolName, payload);
    }

    private void publish(EventType type, String toolName, Map<String, Object> payload) {
        String safeName = StrUtil.blankToDefault(toolName, "tool");
        Map<String, Object> safePayload =
                payload == null ? Collections.<String, Object>emptyMap() : payload;
        for (Listener listener : listeners) {
            try {
                listener.onEvent(type, safeName, safePayload);
            } catch (Exception ignored) {
                // Listeners must not crash the event bus.
            }
        }
    }
}
