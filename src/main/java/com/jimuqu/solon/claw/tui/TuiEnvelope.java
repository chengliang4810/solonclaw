package com.jimuqu.solon.claw.tui;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** JSON envelope used by the browser Agent terminal gateway. */
public class TuiEnvelope {
    private String id;
    private String method;
    private String sessionId;
    private Map<String, Object> params = new LinkedHashMap<String, Object>();

    public static TuiEnvelope parse(String raw) {
        ONode node = ONode.ofJson(raw);
        TuiEnvelope envelope = new TuiEnvelope();
        envelope.setId(node.get("id").getString());
        envelope.setMethod(node.get("method").getString());
        envelope.setSessionId(firstText(node, "sessionId", "session_id"));
        Object params = node.get("params").toData();
        if (params instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) params;
            envelope.setParams(new LinkedHashMap<String, Object>(parsed));
        }
        return envelope;
    }

    private static String firstText(ONode node, String first, String second) {
        String value = node.get(first).getString();
        if (value != null && value.trim().length() > 0) {
            return value;
        }
        return node.get(second).getString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params == null ? new LinkedHashMap<String, Object>() : params;
    }
}
