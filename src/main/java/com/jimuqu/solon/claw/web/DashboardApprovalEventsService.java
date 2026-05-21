package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Dashboard approval events service for tracking tool approval history. */
public class DashboardApprovalEventsService {
    private final AppConfig appConfig;
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<Map<String, Object>>();
    private static final int MAX_EVENTS = 500;

    public DashboardApprovalEventsService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public void recordApprovalEvent(String toolName, String decision, String sourceKey,
            String summary, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("timestamp", Long.valueOf(System.currentTimeMillis()));
        event.put("toolName", toolName);
        event.put("decision", decision);
        event.put("sourceKey", sourceKey);
        event.put("summary", StrUtil.nullToEmpty(summary));
        if (details != null && !details.isEmpty()) {
            event.put("details", details);
        }
        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    public List<Map<String, Object>> recentEvents(int limit) {
        int size = events.size();
        int from = Math.max(0, size - Math.min(limit, MAX_EVENTS));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = size - 1; i >= from; i--) {
            result.add(events.get(i));
        }
        return result;
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("totalEvents", Integer.valueOf(events.size()));
        int approved = 0;
        int blocked = 0;
        int pending = 0;
        for (Map<String, Object> event : events) {
            String decision = (String) event.get("decision");
            if ("allow".equals(decision) || "approved".equals(decision)) {
                approved++;
            } else if ("block".equals(decision) || "denied".equals(decision)) {
                blocked++;
            } else {
                pending++;
            }
        }
        result.put("approved", Integer.valueOf(approved));
        result.put("blocked", Integer.valueOf(blocked));
        result.put("pending", Integer.valueOf(pending));
        return result;
    }

    public void clear() {
        events.clear();
    }
}
