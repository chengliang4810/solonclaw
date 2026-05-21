package com.jimuqu.solon.claw.tool.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process lifecycle event tracking.
 * Records start/complete/fail events for managed background processes.
 */
public class ProcessLifecycleTracker {
    public enum EventType { STARTED, COMPLETED, FAILED, KILLED }

    private final List<ProcessEvent> events = new CopyOnWriteArrayList<ProcessEvent>();
    private final int maxEvents;

    public ProcessLifecycleTracker() {
        this(200);
    }

    public ProcessLifecycleTracker(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public void recordStarted(String processId, String command, String workDir) {
        record(new ProcessEvent(EventType.STARTED, processId, command, workDir, 0, null));
    }

    public void recordCompleted(String processId, String command, int exitCode) {
        record(new ProcessEvent(EventType.COMPLETED, processId, command, null, exitCode, null));
    }

    public void recordFailed(String processId, String command, String error) {
        record(new ProcessEvent(EventType.FAILED, processId, command, null, -1, error));
    }

    public void recordKilled(String processId, String command) {
        record(new ProcessEvent(EventType.KILLED, processId, command, null, -1, "killed by user"));
    }

    public List<ProcessEvent> recentEvents(int limit) {
        int size = events.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<ProcessEvent>(events.subList(from, size));
    }

    public List<Map<String, Object>> recentEventsAsMap(int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ProcessEvent event : recentEvents(limit)) {
            result.add(event.toMap());
        }
        return result;
    }

    public int totalEvents() {
        return events.size();
    }

    private void record(ProcessEvent event) {
        events.add(event);
        while (events.size() > maxEvents) {
            events.remove(0);
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
            this.command = command;
            this.workDir = workDir;
            this.exitCode = exitCode;
            this.error = error;
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
    }
}
