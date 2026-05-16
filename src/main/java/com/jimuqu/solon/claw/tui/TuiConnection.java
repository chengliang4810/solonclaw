package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.noear.snack4.ONode;
import org.noear.solon.net.websocket.WebSocket;

/** A single browser terminal transport. */
public class TuiConnection {
    private final WebSocket socket;
    private final AtomicLong seq = new AtomicLong();
    private volatile String activeSessionId;

    public TuiConnection(WebSocket socket) {
        this.socket = socket;
    }

    public String id() {
        return socket.id();
    }

    public String getActiveSessionId() {
        return activeSessionId;
    }

    public void setActiveSessionId(String activeSessionId) {
        if (StrUtil.isNotBlank(activeSessionId)) {
            this.activeSessionId = activeSessionId;
        }
    }

    public void sendEvent(TuiEvent event) {
        socket.send(ONode.serialize(event.toMap()));
        seq.updateAndGet(
                current -> Math.max(current, event == null ? current : event.getTransportSeq()));
    }

    public void sendEvent(String type, String sessionId, Map<String, Object> payload) {
        long next = seq.incrementAndGet();
        socket.send(ONode.serialize(new TuiEvent(type, sessionId, next, next, payload).toMap()));
    }

    public void sendResult(String id, String sessionId, Object result) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("type", "rpc.result");
        map.put("id", id);
        map.put("sessionId", sessionId);
        map.put("seq", Long.valueOf(seq.incrementAndGet()));
        map.put("payload", result == null ? new LinkedHashMap<String, Object>() : result);
        socket.send(ONode.serialize(map));
    }

    public void sendError(String id, String sessionId, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("code", code);
        payload.put("message", message);

        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("type", "rpc.error");
        map.put("id", id);
        map.put("sessionId", sessionId);
        map.put("seq", Long.valueOf(seq.incrementAndGet()));
        map.put("payload", payload);
        socket.send(ONode.serialize(map));
    }
}
