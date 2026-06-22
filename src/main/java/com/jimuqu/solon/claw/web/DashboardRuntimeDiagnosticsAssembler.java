package com.jimuqu.solon.claw.web;

import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.diagnosticFailureSummary;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.safeAuditPreview;
import static com.jimuqu.solon.claw.web.DashboardDiagnosticTextFormatter.safeObjectText;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 运行时诊断组装器，集中处理基础运行状态、进程快照和可恢复任务摘要。 */
final class DashboardRuntimeDiagnosticsAssembler {
    /** 记录路径规范化等非致命诊断异常，日志内容必须保持脱敏。 */
    private static final Logger log =
            LoggerFactory.getLogger(DashboardRuntimeDiagnosticsAssembler.class);

    /** 可恢复运行条目最多展示数量，避免 Dashboard 响应过大。 */
    private static final int RECOVERABLE_RUN_ITEM_LIMIT = 5;

    /** 可恢复运行扫描上限，用于判断是否存在截断。 */
    private static final int RECOVERABLE_RUN_SCAN_LIMIT = 100;

    /** 进程快照最多展示数量，防止长时间运行实例撑大诊断响应。 */
    private static final int PROCESS_SNAPSHOT_LIMIT = 5;

    /** 进程生命周期事件最多展示数量，保留最近状态变化但不泄露过多历史。 */
    private static final int PROCESS_LIFECYCLE_EVENT_LIMIT = 10;

    /** 应用配置，用于解析运行时路径和运行目录。 */
    private final AppConfig appConfig;

    /** 关闭取证服务，用于读取最近一次退出摘要。 */
    private final ShutdownForensicsService shutdownForensicsService;

    /** 运行时记忆监控服务，用于展示记忆后台任务状态。 */
    private final RuntimeMemoryMonitorService runtimeMemoryMonitorService;

    /** Agent 运行仓储，用于展示可恢复运行摘要。 */
    private final AgentRunRepository agentRunRepository;

    /** 托管进程注册表，用于展示后台进程状态。 */
    private final ProcessRegistry processRegistry;

    /** 网关运行时刷新服务，用于展示最近一次配置刷新失败摘要。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /**
     * 创建 Dashboard 运行时诊断组装器。
     *
     * @param appConfig 应用配置。
     * @param shutdownForensicsService 关闭取证服务。
     * @param runtimeMemoryMonitorService 运行时记忆监控服务。
     * @param agentRunRepository Agent 运行仓储。
     * @param processRegistry 托管进程注册表。
     * @param gatewayRuntimeRefreshService 网关运行时刷新服务。
     */
    DashboardRuntimeDiagnosticsAssembler(
            AppConfig appConfig,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService,
            AgentRunRepository agentRunRepository,
            ProcessRegistry processRegistry,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        this.appConfig = appConfig;
        this.shutdownForensicsService = shutdownForensicsService;
        this.runtimeMemoryMonitorService = runtimeMemoryMonitorService;
        this.agentRunRepository = agentRunRepository;
        this.processRegistry = processRegistry;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
    }

    /**
     * 组装 Dashboard runtime 区块，保持原有字段顺序和脱敏契约。
     *
     * @return 返回运行时诊断 Map。
     */
    Map<String, Object> runtime() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("home", runtimeReference(appConfig.getRuntime().getHome()));
        map.put("state_db", runtimeReference(appConfig.getRuntime().getStateDb()));
        map.put("cache_dir", runtimeReference(appConfig.getRuntime().getCacheDir()));
        map.put("logs_dir", runtimeReference(appConfig.getRuntime().getLogsDir()));
        map.put("home_exists", new File(appConfig.getRuntime().getHome()).exists());
        map.put("state_parent_writable", canWriteParent(appConfig.getRuntime().getStateDb()));
        map.put("last_shutdown", shutdownSummary());
        map.put("memory_monitor", memoryMonitorSummary());
        map.put("managed_processes", managedProcessSummary());
        map.put("config_refresh", configRefreshSummary());
        return map;
    }

    /**
     * 组装可恢复 Agent 运行摘要，供 Dashboard 判断是否存在可继续的后台任务。
     *
     * @return 返回 runs 诊断 Map。
     */
    Map<String, Object> runs() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.valueOf(agentRunRepository != null));
        summary.put("limit", Integer.valueOf(RECOVERABLE_RUN_ITEM_LIMIT));
        summary.put("recoverable_count", Integer.valueOf(0));
        summary.put("truncated", Boolean.FALSE);
        summary.put("recoverable_items", Collections.emptyList());
        if (agentRunRepository == null) {
            return summary;
        }
        try {
            List<AgentRunRecord> records =
                    agentRunRepository.listRecoverable(RECOVERABLE_RUN_SCAN_LIMIT);
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            int count = records == null ? 0 : records.size();
            if (records != null) {
                int end = Math.min(records.size(), RECOVERABLE_RUN_ITEM_LIMIT);
                for (int i = 0; i < end; i++) {
                    items.add(recoverableRunItem(records.get(i)));
                }
            }
            summary.put("recoverable_count", Integer.valueOf(count));
            summary.put("truncated", Boolean.valueOf(count > RECOVERABLE_RUN_ITEM_LIMIT));
            summary.put("recoverable_items", items);
        } catch (Exception e) {
            summary.put("available", Boolean.FALSE);
            summary.put("error", safeObjectText(e.getMessage(), 300));
        }
        return summary;
    }

    /**
     * 组装配置刷新摘要，只展示最近一次失败的脱敏快照。
     *
     * @return 返回配置刷新诊断 Map。
     */
    private Map<String, Object> configRefreshSummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put(
                "last_failure",
                gatewayRuntimeRefreshService == null
                        ? null
                        : gatewayRuntimeRefreshService.lastFailureSnapshot());
        return summary;
    }

    /**
     * 组装托管进程摘要，异常时降级为不可用状态而不影响整体诊断接口。
     *
     * @return 返回托管进程诊断 Map。
     */
    private Map<String, Object> managedProcessSummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.valueOf(processRegistry != null));
        summary.put("running_count", Integer.valueOf(0));
        summary.put("snapshot_limit", Integer.valueOf(PROCESS_SNAPSHOT_LIMIT));
        summary.put("lifecycle_event_limit", Integer.valueOf(PROCESS_LIFECYCLE_EVENT_LIMIT));
        summary.put("snapshots", Collections.emptyList());
        summary.put("recent_lifecycle_events", Collections.emptyList());
        summary.put("truncated", Boolean.FALSE);
        if (processRegistry == null) {
            return summary;
        }
        try {
            Map<String, ProcessRegistry.ManagedProcess> snapshot = processRegistry.snapshot();
            List<Map<String, Object>> snapshots = new ArrayList<Map<String, Object>>();
            for (ProcessRegistry.ManagedProcess managed : snapshot.values()) {
                if (snapshots.size() >= PROCESS_SNAPSHOT_LIMIT) {
                    break;
                }
                snapshots.add(managedProcessSnapshot(managed));
            }
            summary.put("running_count", Integer.valueOf(processRegistry.runningCount()));
            summary.put("snapshots", snapshots);
            summary.put(
                    "recent_lifecycle_events",
                    processRegistry.recentLifecycleEvents(PROCESS_LIFECYCLE_EVENT_LIMIT));
            summary.put("truncated", Boolean.valueOf(snapshot.size() > PROCESS_SNAPSHOT_LIMIT));
        } catch (Exception e) {
            summary.put("available", Boolean.FALSE);
            summary.put("error", safeObjectText(e.getMessage(), 300));
        }
        return summary;
    }

    /**
     * 从托管进程原始脱敏 Map 中挑选 Dashboard 契约允许暴露的字段。
     *
     * @param managed 托管进程记录。
     * @return 返回单个进程快照。
     */
    private Map<String, Object> managedProcessSnapshot(ProcessRegistry.ManagedProcess managed) {
        Map<String, Object> source = managed.toRedactedMap();
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        copyIfPresent(source, snapshot, "session_id");
        copyIfPresent(source, snapshot, "id");
        copyIfPresent(source, snapshot, "command");
        copyIfPresent(source, snapshot, "cwd");
        copyIfPresent(source, snapshot, "pid");
        copyIfPresent(source, snapshot, "started_at");
        copyIfPresent(source, snapshot, "started_at_iso");
        copyIfPresent(source, snapshot, "uptime_seconds");
        copyIfPresent(source, snapshot, "status");
        copyIfPresent(source, snapshot, "exited");
        copyIfPresent(source, snapshot, "running");
        copyIfPresent(source, snapshot, "exit_code");
        copyIfPresent(source, snapshot, "exit_code_meaning");
        copyIfPresent(source, snapshot, "notify_on_complete");
        copyIfPresent(source, snapshot, "watch_patterns");
        copyIfPresent(source, snapshot, "watch_hits");
        copyIfPresent(source, snapshot, "watch_suppressed");
        copyIfPresent(source, snapshot, "watch_disabled");
        copyIfPresent(source, snapshot, "output_preview");
        copyIfPresent(source, snapshot, "truncated");
        copyIfPresent(source, snapshot, "stdin_closed");
        copyIfPresent(source, snapshot, "lifecycle_last_event");
        return snapshot;
    }

    /**
     * 复制存在的诊断字段，避免输出 null 占位改变原有 JSON 契约。
     *
     * @param source 来源 Map。
     * @param target 目标 Map。
     * @param key 字段名。
     */
    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    /**
     * 组装记忆监控摘要，服务未启用时保持原有 disabled 结构。
     *
     * @return 返回记忆监控诊断 Map。
     */
    private Map<String, Object> memoryMonitorSummary() {
        if (runtimeMemoryMonitorService == null) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("enabled", Boolean.FALSE);
            summary.put("running", Boolean.FALSE);
            return summary;
        }
        return runtimeMemoryMonitorService.status();
    }

    /**
     * 组装可恢复运行条目，所有文本字段都先做脱敏和长度限制。
     *
     * @param record Agent 运行记录。
     * @return 返回可恢复运行条目 Map。
     */
    private Map<String, Object> recoverableRunItem(AgentRunRecord record) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("run_id", safeAuditPreview(record == null ? null : record.getRunId(), 200));
        item.put(
                "session_id", safeAuditPreview(record == null ? null : record.getSessionId(), 200));
        item.put(
                "source_key", safeAuditPreview(record == null ? null : record.getSourceKey(), 300));
        item.put("status", safeAuditPreview(record == null ? null : record.getStatus(), 80));
        item.put("phase", safeAuditPreview(record == null ? null : record.getPhase(), 80));
        item.put("backgrounded", Boolean.valueOf(record != null && record.isBackgrounded()));
        item.put(
                "exit_reason",
                safeAuditPreview(record == null ? null : record.getExitReason(), 160));
        item.put(
                "last_activity_at", Long.valueOf(record == null ? 0L : record.getLastActivityAt()));
        item.put(
                "recovery_hint",
                safeAuditPreview(record == null ? null : record.getRecoveryHint(), 500));
        return item;
    }

    /**
     * 组装最近一次关闭取证摘要，文件路径只以 runtime:// 或 path:// 形式展示。
     *
     * @return 返回关闭取证诊断 Map。
     */
    private Map<String, Object> shutdownSummary() {
        if (shutdownForensicsService == null) {
            return unavailableShutdownSummary();
        }
        Map<String, Object> record = shutdownForensicsService.lastShutdownRecord();
        File file = shutdownForensicsService.lastShutdownRecordFile();
        if (record == null || file == null) {
            return unavailableShutdownSummary();
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.TRUE);
        summary.put("record", runtimeReference(file.getAbsolutePath()));
        summary.put("timestamp", record.get("timestamp"));
        summary.put("timestamp_iso", safeObjectText(record.get("timestampIso"), 80));
        summary.put("reason", safeObjectText(record.get("reason"), 200));
        summary.put("uptime_ms", record.get("uptimeMs"));
        summary.put("pid", safeObjectText(record.get("pid"), 80));
        summary.put("memory", record.get("memory"));
        summary.put("threads", record.get("threads"));
        return summary;
    }

    /**
     * 生成关闭取证不可用摘要，保持 Dashboard 字段契约稳定。
     *
     * @return 返回不可用摘要。
     */
    private Map<String, Object> unavailableShutdownSummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.FALSE);
        return summary;
    }

    /**
     * 判断状态库父目录是否可写，用于基础运行环境体检。
     *
     * @param path 状态库路径。
     * @return 父目录存在且可写时返回 true。
     */
    private boolean canWriteParent(String path) {
        if (path == null) {
            return false;
        }
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent != null && parent.exists() && parent.canWrite();
    }

    /**
     * 将本机路径转换为 Dashboard 可展示的 runtime:// 或 path:// 引用。
     *
     * @param value 原始路径文本。
     * @return 返回脱敏后的路径引用。
     */
    private String runtimeReference(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (StrUtil.isBlank(text)) {
            return text;
        }
        File runtimeHome = new File(appConfig.getRuntime().getHome()).getAbsoluteFile();
        File file = new File(text).getAbsoluteFile();
        try {
            runtimeHome = runtimeHome.getCanonicalFile();
            file = file.getCanonicalFile();
        } catch (Exception e) {
            log.debug(
                    "Dashboard runtime path canonicalization failed; falling back to absolute path: {}",
                    diagnosticFailureSummary(e));
        }
        String homePath = normalized(runtimeHome);
        String filePath = normalized(file);
        if (filePath.equals(homePath)) {
            return "runtime://";
        }
        if (filePath.startsWith(homePath + File.separator)) {
            String relative = filePath.substring(homePath.length() + 1).replace('\\', '/');
            return "runtime://" + relative;
        }
        return externalPathReference(text);
    }

    /**
     * 将 runtime 外部路径压缩成仅含文件名的 path:// 引用，避免泄露宿主目录结构。
     *
     * @param value 原始外部路径。
     * @return 返回外部路径引用。
     */
    private String externalPathReference(String value) {
        String name = new File(StrUtil.nullToEmpty(value)).getName();
        if (StrUtil.isBlank(name)) {
            name = "external";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    /**
     * 规范化路径比较文本，Windows 下使用小写比较以匹配文件系统语义。
     *
     * @param file 文件路径。
     * @return 返回比较用路径文本。
     */
    private String normalized(File file) {
        String path = file.getAbsolutePath();
        if (File.separatorChar == '\\') {
            return path.toLowerCase(Locale.ROOT);
        }
        return path;
    }
}
