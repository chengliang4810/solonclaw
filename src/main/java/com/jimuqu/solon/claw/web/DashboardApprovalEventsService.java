package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** Dashboard approval events service for tracking tool approval history. */
public class DashboardApprovalEventsService {
    private final AppConfig appConfig;
    private final LinkedList<Map<String, Object>> events = new LinkedList<Map<String, Object>>();
    private static final int MAX_EVENTS = 500;

    public DashboardApprovalEventsService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public synchronized void recordApprovalEvent(
            String toolName,
            String decision,
            String sourceKey,
            String summary,
            Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("timestamp", Long.valueOf(System.currentTimeMillis()));
        event.put("toolName", toolName);
        event.put("decision", decision);
        event.put("sourceKey", sourceKey);
        event.put("summary", StrUtil.nullToEmpty(summary));
        if (details != null && !details.isEmpty()) {
            event.put("details", details);
        }
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    public synchronized List<Map<String, Object>> recentEvents(int limit) {
        int size = events.size();
        int from = Math.max(0, size - Math.min(limit, MAX_EVENTS));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = size - 1; i >= from; i--) {
            result.add(events.get(i));
        }
        return result;
    }

    public synchronized Map<String, Object> stats() {
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

    public synchronized void clear() {
        events.clear();
    }
}
