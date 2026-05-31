package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Process lifecycle event tracking.
 * Records start/complete/fail events for managed background processes.
 */
public class ProcessLifecycleTracker {
    public enum EventType { STARTED, COMPLETED, FAILED, KILLED }

    private final LinkedList<ProcessEvent> events = new LinkedList<ProcessEvent>();
    private final int maxEvents;

    public ProcessLifecycleTracker() {
        this(200);
    }

    public ProcessLifecycleTracker(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public synchronized void recordStarted(String processId, String command, String workDir) {
        record(new ProcessEvent(EventType.STARTED, processId, command, workDir, 0, null));
    }

    public synchronized void recordCompleted(String processId, String command, int exitCode) {
        record(new ProcessEvent(EventType.COMPLETED, processId, command, null, exitCode, null));
    }

    public synchronized void recordFailed(String processId, String command, String error) {
        record(new ProcessEvent(EventType.FAILED, processId, command, null, -1, error));
    }

    public synchronized void recordFailed(
            String processId, String command, int exitCode, String error) {
        record(new ProcessEvent(EventType.FAILED, processId, command, null, exitCode, error));
    }

    public synchronized void recordKilled(String processId, String command) {
        record(new ProcessEvent(EventType.KILLED, processId, command, null, -1, "killed by user"));
    }

    public synchronized List<ProcessEvent> recentEvents(int limit) {
        int size = events.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<ProcessEvent>(events.subList(from, size));
    }

    public synchronized List<Map<String, Object>> recentEventsAsMap(int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ProcessEvent event : recentEvents(limit)) {
            result.add(event.toMap());
        }
        return result;
    }

    public synchronized List<Map<String, Object>> eventsForProcessAsMap(
            String processId, int limit) {
        if (StrUtil.isBlank(processId)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ProcessEvent event : recentEvents(maxEvents)) {
            if (processId.equals(event.getProcessId())) {
                result.add(event.toMap());
            }
        }
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<Map<String, Object>>(
                    result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    public synchronized Map<String, Object> lastEventForProcessAsMap(String processId) {
        if (StrUtil.isBlank(processId)) {
            return Collections.emptyMap();
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            ProcessEvent event = events.get(i);
            if (processId.equals(event.getProcessId())) {
                return event.toMap();
            }
        }
        return Collections.emptyMap();
    }

    public synchronized int totalEvents() {
        return events.size();
    }

    private void record(ProcessEvent event) {
        events.addLast(event);
        while (events.size() > maxEvents) {
            events.removeFirst();
        }
    }

    public static class ProcessEvent {
        private final EventType type;
        private final String processId;
        private final String command;
        private final String workDir;
        private final int exitCode;
        private final String error;
        private final long timestamp;

        public ProcessEvent(EventType type, String processId, String command,
                String workDir, int exitCode, String error) {
            this.type = type;
            this.processId = processId;
            this.command = safeText(command);
            this.workDir = safePath(workDir);
            this.exitCode = exitCode;
            this.error = safeOptionalText(error);
            this.timestamp = System.currentTimeMillis();
        }

        public EventType getType() { return type; }
        public String getProcessId() { return processId; }
        public String getCommand() { return command; }
        public String getWorkDir() { return workDir; }
        public int getExitCode() { return exitCode; }
        public String getError() { return error; }
        public long getTimestamp() { return timestamp; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("type", type.name().toLowerCase());
            map.put("processId", processId);
            map.put("command", command);
            if (workDir != null) {
                map.put("workDir", workDir);
            }
            if (type == EventType.COMPLETED || type == EventType.FAILED) {
                map.put("exitCode", Integer.valueOf(exitCode));
            }
            if (error != null) {
                map.put("error", error);
            }
            map.put("timestamp", Long.valueOf(timestamp));
            return map;
        }

        private static String safeText(String text) {
            return StrUtil.isBlank(text) ? "" : SecretRedactor.redact(text);
        }

        private static String safeOptionalText(String text) {
            return StrUtil.isBlank(text) ? null : SecretRedactor.redact(text);
        }

        private static String safePath(String path) {
            if (StrUtil.isBlank(path)) {
                return null;
            }
            String name = new java.io.File(path).getName();
            if (StrUtil.isBlank(name)) {
                name = "workspace";
            }
            return "path://" + SecretRedactor.redact(name, 200);
        }
    }
}
