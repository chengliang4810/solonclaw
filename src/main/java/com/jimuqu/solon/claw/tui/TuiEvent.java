package com.jimuqu.solon.claw.tui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server event pushed to an Agent terminal client. */
public class TuiEvent {
    private final String type;
    private final String sessionId;
    private final long seq;
    private final Map<String, Object> payload;

    public TuiEvent(String type, String sessionId, long seq, Map<String, Object> payload) {
        this.type = type;
        this.sessionId = sessionId;
        this.seq = seq;
        this.payload = payload == null ? new LinkedHashMap<String, Object>() : payload;
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
