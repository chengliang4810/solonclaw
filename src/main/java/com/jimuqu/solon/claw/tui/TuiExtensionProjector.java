package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.acp.AcpStdioServer;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.web.DashboardCronService;
import com.jimuqu.solon.claw.web.DashboardKanbanService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects automation and integration state into the terminal gateway stream. */
public class TuiExtensionProjector {
    private static final int DEFAULT_LIMIT = 6;

    private final DashboardCronService cronService;
    private final DashboardKanbanService kanbanService;
    private final DashboardMcpService mcpService;
    private final CliRuntime cliRuntime;
    private final SessionRepository sessionRepository;
    private final DangerousCommandApprovalService approvalService;
    private final AppConfig appConfig;

    public TuiExtensionProjector(
            DashboardCronService cronService,
            DashboardKanbanService kanbanService,
            DashboardMcpService mcpService,
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService approvalService,
            AppConfig appConfig) {
        this.cronService = cronService;
        this.kanbanService = kanbanService;
        this.mcpService = mcpService;
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.approvalService = approvalService;
        this.appConfig = appConfig;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("generated_at", Long.valueOf(System.currentTimeMillis()));
        result.put("cron", cronSnapshot());
        result.put("kanban", kanbanSnapshot());
        result.put("mcp", mcpSnapshot());
        result.put("acp", acpSnapshot());
        return result;
    }

    public Map<String, Object> snapshot(String kind) {
        String normalized = StrUtil.blankToDefault(kind, "").trim().toLowerCase(java.util.Locale.ROOT);
        if ("cron".equals(normalized)) {
            return cronSnapshot();
        }
        if ("kanban".equals(normalized)) {
            return kanbanSnapshot();
        }
        if ("mcp".equals(normalized)) {
            return mcpSnapshot();
        }
        if ("acp".equals(normalized)) {
            return acpSnapshot();
        }
        throw new IllegalArgumentException("Unsupported integration snapshot: " + safe(kind, 80));
    }

    public List<TuiEvent> snapshotEvents(String sessionId) {
        List<TuiEvent> events = new ArrayList<TuiEvent>();
        events.add(snapshotEvent(sessionId, "cron"));
        events.add(snapshotEvent(sessionId, "kanban"));
        events.add(snapshotEvent(sessionId, "mcp"));
        events.add(snapshotEvent(sessionId, "acp"));
        return events;
    }

    public TuiEvent snapshotEvent(String sessionId, String kind) {
        String normalized = StrUtil.blankToDefault(kind, "").trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> payload = snapshot(normalized);
        long seq = System.currentTimeMillis() * 1000L + offset(normalized);
        payload.put("event_seq", Long.valueOf(seq));
        return new TuiEvent(normalized + ".snapshot", sessionId, seq, payload);
    }

    private Map<String, Object> cronSnapshot() {
        Map<String, Object> payload = base("cron", "定时任务");
        if (cronService == null) {
            return unavailable(payload, "定时任务服务未启用");
        }
        try {
            Map<String, Object> status = cronService.status(true, DEFAULT_LIMIT);
            Map<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("total", intValue(status.get("total")));
            metrics.put("active", intValue(status.get("active")));
            metrics.put("paused", intValue(status.get("paused")));
            metrics.put("completed", intValue(status.get("completed")));
            metrics.put("due", intValue(status.get("due")));
            int active = intValue(status.get("active"));
            int due = intValue(status.get("due"));
            List<Map<String, Object>> next = mapList(status.get("next"));
            List<Map<String, Object>> failures = mapList(status.get("recent_failures"));
            payload.put("available", Boolean.TRUE);
            payload.put("status", due > 0 ? "due" : active > 0 ? "active" : "idle");
            payload.put(
                    "summary",
                    "活跃 "
                            + active
                            + "，待触发 "
                            + due
                            + "，暂停 "
                            + intValue(status.get("paused")));
            payload.put("metrics", metrics);
            payload.put("items", cronItems(next));
            payload.put("next", next);
            payload.put("recent_failures", failures);
            if (!failures.isEmpty()) {
                payload.put("status", "attention");
            }
            return payload;
        } catch (Exception e) {
            return failed(payload, e);
        }
    }

    private Map<String, Object> kanbanSnapshot() {
        Map<String, Object> payload = base("kanban", "看板");
        if (kanbanService == null) {
            return unavailable(payload, "看板服务未启用");
        }
        try {
            Map<String, Object> stats = kanbanService.stats();
            Map<String, Object> board = kanbanService.currentBoard();
            Map<String, Object> byStatus = asMap(stats.get("by_status"));
            List<Map<String, Object>> running = kanbanService.tasks(null, "running", false);
            List<Map<String, Object>> blocked = kanbanService.tasks(null, "blocked", false);
            List<Map<String, Object>> ready = kanbanService.tasks(null, "ready", false);
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            items.addAll(taskItems(running, "running", 2));
            items.addAll(taskItems(blocked, "blocked", 2));
            items.addAll(taskItems(ready, "ready", 2));

            Map<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("total", intValue(stats.get("total")));
            metrics.put("ready", intValue(byStatus.get("ready")));
            metrics.put("running", intValue(byStatus.get("running")));
            metrics.put("blocked", intValue(byStatus.get("blocked")));
            metrics.put("done", intValue(byStatus.get("done")));

            int runningCount = intValue(byStatus.get("running"));
            int blockedCount = intValue(byStatus.get("blocked"));
            int readyCount = intValue(byStatus.get("ready"));
            payload.put("available", Boolean.TRUE);
            payload.put("status", blockedCount > 0 ? "attention" : runningCount > 0 ? "running" : readyCount > 0 ? "ready" : "idle");
            payload.put(
                    "summary",
                    safe(String.valueOf(board.get("name")), 120)
                            + "：运行 "
                            + runningCount
                            + "，就绪 "
                            + readyCount
                            + "，阻塞 "
                            + blockedCount);
            payload.put("metrics", metrics);
            payload.put("board", board);
            payload.put("stats", stats);
            payload.put("items", items);
            payload.put("delivery", kanbanService.notifyDeliveryStatus());
            return payload;
        } catch (Exception e) {
            return failed(payload, e);
        }
    }

    private Map<String, Object> mcpSnapshot() {
        Map<String, Object> payload = base("mcp", "MCP");
        if (mcpService == null) {
            return unavailable(payload, "MCP 服务未启用");
        }
        try {
            Map<String, Object> state = mcpService.list();
            List<Map<String, Object>> servers = mapList(state.get("servers"));
            int enabledServers = 0;
            int ready = 0;
            int blocked = 0;
            int error = 0;
            int toolCount = 0;
            Map<String, Object> byStatus = new LinkedHashMap<String, Object>();
            for (Map<String, Object> server : servers) {
                if (Boolean.TRUE.equals(server.get("enabled"))) {
                    enabledServers++;
                }
                String status = StrUtil.blankToDefault(String.valueOf(server.get("status")), "unknown");
                byStatus.put(status, Integer.valueOf(intValue(byStatus.get(status)) + 1));
                if ("ready".equalsIgnoreCase(status) || "configured".equalsIgnoreCase(status)) {
                    ready++;
                } else if ("blocked".equalsIgnoreCase(status)) {
                    blocked++;
                } else if ("error".equalsIgnoreCase(status)) {
                    error++;
                }
                toolCount += listSize(server.get("tools"));
            }
            Map<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("servers", servers.size());
            metrics.put("enabled_servers", enabledServers);
            metrics.put("ready", ready);
            metrics.put("blocked", blocked);
            metrics.put("error", error);
            metrics.put("tool_count", toolCount);
            boolean runtimeEnabled = Boolean.TRUE.equals(state.get("enabled"));
            payload.put("available", Boolean.TRUE);
            payload.put("status", runtimeEnabled ? (blocked + error > 0 ? "attention" : "ready") : "disabled");
            payload.put(
                    "summary",
                    (runtimeEnabled ? "运行时已启用" : "运行时未启用")
                            + "，服务 "
                            + servers.size()
                            + "，工具 "
                            + toolCount);
            payload.put("runtime_enabled", Boolean.valueOf(runtimeEnabled));
            payload.put("metrics", metrics);
            payload.put("by_status", byStatus);
            payload.put("items", mcpItems(servers));
            payload.put("servers", servers);
            return payload;
        } catch (Exception e) {
            return failed(payload, e);
        }
    }

    private Map<String, Object> acpSnapshot() {
        Map<String, Object> payload = base("acp", "ACP");
        try {
            AcpStdioServer server =
                    new AcpStdioServer(
                            cliRuntime,
                            sessionRepository,
                            mcpService,
                            approvalService,
                            appConfig);
            Map<String, Object> status = server.status();
            Map<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("method_count", listSize(status.get("methods")));
            metrics.put("command_count", listSize(status.get("commands")));
            payload.put("available", Boolean.TRUE);
            payload.put("status", "ready");
            payload.put(
                    "summary",
                    "transport="
                            + safe(String.valueOf(status.get("transport")), 80)
                            + "，方法 "
                            + listSize(status.get("methods")));
            payload.put("metrics", metrics);
            payload.put("protocol_version", status.get("protocol_version"));
            payload.put("capabilities", status.get("capabilities"));
            payload.put("agent_capabilities", status.get("agent_capabilities"));
            payload.put("items", acpItems(status.get("methods")));
            payload.put("methods", status.get("methods"));
            payload.put("commands", status.get("commands"));
            return payload;
        } catch (Exception e) {
            return failed(payload, e);
        }
    }

    private List<Map<String, Object>> cronItems(List<Map<String, Object>> jobs) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int count = Math.min(DEFAULT_LIMIT, jobs.size());
        for (int i = 0; i < count; i++) {
            Map<String, Object> job = jobs.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", safe(first(job, "job_id", "id"), 160));
            item.put("title", safe(StrUtil.blankToDefault(first(job, "name", "job_id"), "未命名任务"), 200));
            item.put("status", safe(first(job, "status", "last_status"), 80));
            item.put("meta", safe(first(job, "cron_expr", "schedule"), 200));
            item.put("time", job.get("next_run_at"));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> taskItems(List<Map<String, Object>> tasks, String status, int limit) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int count = Math.min(limit, tasks.size());
        for (int i = 0; i < count; i++) {
            Map<String, Object> task = tasks.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", safe(first(task, "task_id", "id"), 160));
            item.put("title", safe(StrUtil.blankToDefault(first(task, "title", "task_id"), "未命名任务"), 200));
            item.put("status", safe(StrUtil.blankToDefault(first(task, "status"), status), 80));
            item.put("meta", safe(first(task, "assignee", "board"), 200));
            item.put("time", task.get("updated_at"));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> mcpItems(List<Map<String, Object>> servers) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int count = Math.min(DEFAULT_LIMIT, servers.size());
        for (int i = 0; i < count; i++) {
            Map<String, Object> server = servers.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", safe(first(server, "server_id", "name"), 160));
            item.put("title", safe(StrUtil.blankToDefault(first(server, "name", "server_id"), "未命名服务"), 200));
            item.put("status", safe(first(server, "status"), 80));
            item.put("meta", safe(first(server, "transport", "endpoint"), 240));
            item.put("tool_count", Integer.valueOf(listSize(server.get("tools"))));
            item.put("enabled", Boolean.valueOf(Boolean.TRUE.equals(server.get("enabled"))));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> acpItems(Object methods) {
        if (!(methods instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<?> values = (List<?>) methods;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int count = Math.min(DEFAULT_LIMIT, values.size());
        for (int i = 0; i < count; i++) {
            String method = String.valueOf(values.get(i));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", safe(method, 160));
            item.put("title", safe(method, 160));
            item.put("status", "supported");
            item.put("meta", "JSON-RPC");
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> base(String kind, String title) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("kind", kind);
        payload.put("title", title);
        payload.put("status", "unknown");
        payload.put("available", Boolean.FALSE);
        payload.put("summary", "");
        payload.put("metrics", new LinkedHashMap<String, Object>());
        payload.put("items", new ArrayList<Map<String, Object>>());
        payload.put("updated_at", Long.valueOf(System.currentTimeMillis()));
        return payload;
    }

    private Map<String, Object> unavailable(Map<String, Object> payload, String reason) {
        payload.put("status", "unavailable");
        payload.put("summary", reason);
        payload.put("error", safe(reason, 800));
        return payload;
    }

    private Map<String, Object> failed(Map<String, Object> payload, Exception e) {
        String error = safe(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 1000);
        payload.put("status", "error");
        payload.put("available", Boolean.TRUE);
        payload.put("summary", error);
        payload.put("error", error);
        return payload;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map<?, ?>) {
                result.add(asMap(item));
            }
        }
        return result;
    }

    private Map<String, Object> asMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private int listSize(Object value) {
        if (value instanceof List<?>) {
            return ((List<?>) value).size();
        }
        return 0;
    }

    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private String first(Map<String, Object> map, String key) {
        return first(map, key, null);
    }

    private String first(Map<String, Object> map, String firstKey, String secondKey) {
        Object value = map.get(firstKey);
        if ((value == null || StrUtil.isBlank(String.valueOf(value))) && secondKey != null) {
            value = map.get(secondKey);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private long offset(String kind) {
        if ("cron".equals(kind)) {
            return 810L;
        }
        if ("kanban".equals(kind)) {
            return 820L;
        }
        if ("mcp".equals(kind)) {
            return 830L;
        }
        if ("acp".equals(kind)) {
            return 840L;
        }
        return 899L;
    }

    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
