package com.jimuqu.solon.claw.tui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server event pushed to an Agent terminal client. */
public class TuiEvent {
    private final String type;
    private final String sessionId;
    private final long seq;
    private final long transportSeq;
    private final Map<String, Object> payload;

    public TuiEvent(String type, String sessionId, long seq, Map<String, Object> payload) {
        this(type, sessionId, seq, seq, payload);
    }

    public TuiEvent(
            String type, String sessionId, long seq, long transportSeq, Map<String, Object> payload) {
        this.type = type;
        this.sessionId = sessionId;
        this.seq = seq;
        this.transportSeq = transportSeq;
        this.payload = payload == null ? new LinkedHashMap<String, Object>() : payload;
    }

    public String getType() {
        return type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getSeq() {
        return seq;
    }

    public long getTransportSeq() {
        return transportSeq;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("sessionId", sessionId);
        map.put("seq", Long.valueOf(seq));
        map.put("payload", payload);
        return map;
    }
}
