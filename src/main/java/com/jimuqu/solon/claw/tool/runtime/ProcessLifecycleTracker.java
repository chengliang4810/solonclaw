package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** 承载进程生命周期Tracker相关状态和辅助逻辑。 */
public class ProcessLifecycleTracker {
    /** 枚举事件类型的可选值，保证状态表达在各模块间一致。 */
    public enum EventType {
        /** 表示STARTED枚举值。 */
        STARTED,
        /** 表示COMPLETED枚举值。 */
        COMPLETED,
        /** 表示FAILED枚举值。 */
        FAILED,
        /** 表示KILLED枚举值。 */
        KILLED
    }

    /** 保存events集合，维持调用顺序或去重语义。 */
    private final LinkedList<ProcessEvent> events = new LinkedList<ProcessEvent>();

    /** 记录进程生命周期Tracker中的maxEvents。 */
    private final int maxEvents;

    /** 创建进程生命周期Tracker实例。 */
    public ProcessLifecycleTracker() {
        this(200);
    }

    /**
     * 创建进程生命周期Tracker实例，并注入运行所需依赖。
     *
     * @param maxEvents maxEvents 参数。
     */
    public ProcessLifecycleTracker(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    /**
     * 记录Started。
     *
     * @param processId 进程标识。
     * @param command 待执行或解析的命令文本。
     * @param workDir 命令执行工作目录。
     */
    public synchronized void recordStarted(String processId, String command, String workDir) {
        record(new ProcessEvent(EventType.STARTED, processId, command, workDir, 0, null));
    }

    /**
     * 记录Completed。
     *
     * @param processId 进程标识。
     * @param command 待执行或解析的命令文本。
     * @param exitCode 命令退出码。
     */
    public synchronized void recordCompleted(String processId, String command, int exitCode) {
        record(new ProcessEvent(EventType.COMPLETED, processId, command, null, exitCode, null));
    }

    /**
     * 记录Failed。
     *
     * @param processId 进程标识。
     * @param command 待执行或解析的命令文本。
     * @param error 错误参数。
     */
    public synchronized void recordFailed(String processId, String command, String error) {
        record(new ProcessEvent(EventType.FAILED, processId, command, null, -1, error));
    }

    /**
     * 记录Failed。
     *
     * @param processId 进程标识。
     * @param command 待执行或解析的命令文本。
     * @param exitCode 命令退出码。
     * @param error 错误参数。
     */
    public synchronized void recordFailed(
            String processId, String command, int exitCode, String error) {
        record(new ProcessEvent(EventType.FAILED, processId, command, null, exitCode, error));
    }

    /**
     * 记录Killed。
     *
     * @param processId 进程标识。
     * @param command 待执行或解析的命令文本。
     */
    public synchronized void recordKilled(String processId, String command) {
        record(new ProcessEvent(EventType.KILLED, processId, command, null, -1, "killed by user"));
    }

    /**
     * 执行recentEvents相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回recent Events结果。
     */
    public synchronized List<ProcessEvent> recentEvents(int limit) {
        int size = events.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<ProcessEvent>(events.subList(from, size));
    }

    /**
     * 执行recentEventsAs映射相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回recent Events As Map结果。
     */
    public synchronized List<Map<String, Object>> recentEventsAsMap(int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ProcessEvent event : recentEvents(limit)) {
            result.add(event.toMap());
        }
        return result;
    }

    /**
     * 执行eventsFor进程As映射相关逻辑。
     *
     * @param processId 进程标识。
     * @param limit 最大返回数量。
     * @return 返回events For进程As Map结果。
     */
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

    /**
     * 执行last事件For进程As映射相关逻辑。
     *
     * @param processId 进程标识。
     * @return 返回last事件For进程As Map结果。
     */
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

    /**
     * 执行totalEvents相关逻辑。
     *
     * @return 返回total Events结果。
     */
    public synchronized int totalEvents() {
        return events.size();
    }

    /**
     * 执行记录相关逻辑。
     *
     * @param event 事件参数。
     */
    private void record(ProcessEvent event) {
        events.addLast(event);
        while (events.size() > maxEvents) {
            events.removeFirst();
        }
    }

    /** 承载进程事件相关状态和辅助逻辑。 */
    public static class ProcessEvent {
        /** 记录进程事件中的类型。 */
        private final EventType type;

        /** 记录进程事件中的进程标识。 */
        private final String processId;

        /** 记录进程事件中的命令。 */
        private final String command;

        /** 记录进程事件中的work目录。 */
        private final String workDir;

        /** 记录进程事件中的退出Code。 */
        private final int exitCode;

        /** 记录进程事件中的错误。 */
        private final String error;

        /** 记录进程事件中的时间戳。 */
        private final long timestamp;

        /**
         * 创建进程事件实例，并注入运行所需依赖。
         *
         * @param type 类型参数。
         * @param processId 进程标识。
         * @param command 待执行或解析的命令文本。
         * @param workDir 命令执行工作目录。
         * @param exitCode 命令退出码。
         * @param error 错误参数。
         */
        public ProcessEvent(
                EventType type,
                String processId,
                String command,
                String workDir,
                int exitCode,
                String error) {
            this.type = type;
            this.processId = processId;
            this.command = safeText(command);
            this.workDir = safePath(workDir);
            this.exitCode = exitCode;
            this.error = safeOptionalText(error);
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * 读取类型。
         *
         * @return 返回读取到的类型。
         */
        public EventType getType() {
            return type;
        }

        /**
         * 读取进程标识。
         *
         * @return 返回读取到的进程标识。
         */
        public String getProcessId() {
            return processId;
        }

        /**
         * 读取命令。
         *
         * @return 返回读取到的命令。
         */
        public String getCommand() {
            return command;
        }

        /**
         * 读取Work Dir。
         *
         * @return 返回读取到的Work Dir。
         */
        public String getWorkDir() {
            return workDir;
        }

        /**
         * 读取退出码。
         *
         * @return 返回读取到的退出码。
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        public String getError() {
            return error;
        }

        /**
         * 读取时间戳。
         *
         * @return 返回读取到的时间戳。
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
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

        /**
         * 生成安全展示用的文本。
         *
         * @param text 待处理文本。
         * @return 返回safe Text结果。
         */
        private static String safeText(String text) {
            return StrUtil.isBlank(text) ? "" : SecretRedactor.redact(text);
        }

        /**
         * 生成安全展示用的Optional文本。
         *
         * @param text 待处理文本。
         * @return 返回safe Optional Text结果。
         */
        private static String safeOptionalText(String text) {
            return StrUtil.isBlank(text) ? null : SecretRedactor.redact(text);
        }

        /**
         * 生成安全展示用的路径。
         *
         * @param path 文件或目录路径。
         * @return 返回safe路径。
         */
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
