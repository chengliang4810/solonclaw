package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供进程工具能力，供 Agent 运行时按安全策略调用。 */
public class ProcessTools {
    /** 记录进程中的进程注册表。 */
    private final ProcessRegistry processRegistry;

    /** 记录进程中的默认工作目录。 */
    private final String defaultWorkDir;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 注入应用配置，用于进程。 */
    private final AppConfig appConfig;

    /**
     * 创建进程工具实例，并注入运行所需依赖。
     *
     * @param processRegistry 进程注册表依赖组件。
     * @param defaultWorkDir 默认工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public ProcessTools(
            ProcessRegistry processRegistry,
            String defaultWorkDir,
            SecurityPolicyService securityPolicyService) {
        this(processRegistry, defaultWorkDir, securityPolicyService, null);
    }

    /**
     * 创建进程工具实例，并注入运行所需依赖。
     *
     * @param processRegistry 进程注册表依赖组件。
     * @param defaultWorkDir 默认工作目录。
     * @param securityPolicyService 安全策略服务依赖。
     * @param appConfig 应用运行配置。
     */
    public ProcessTools(
            ProcessRegistry processRegistry,
            String defaultWorkDir,
            SecurityPolicyService securityPolicyService,
            AppConfig appConfig) {
        this.processRegistry = processRegistry;
        this.defaultWorkDir = defaultWorkDir;
        this.securityPolicyService = securityPolicyService;
        this.appConfig = appConfig;
    }

    /**
     * 执行background进程策略摘要相关逻辑。
     *
     * @return 返回background进程策略Summary结果。
     */
    public Map<String, Object> backgroundProcessPolicySummary() {
        return backgroundProcessPolicySummary(appConfig);
    }

    /**
     * 执行background进程策略摘要相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回background进程策略Summary结果。
     */
    public static Map<String, Object> backgroundProcessPolicySummary(AppConfig appConfig) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put(
                "actions",
                Arrays.asList(
                        "start",
                        "list",
                        "status",
                        "detail",
                        "lifecycle",
                        "events",
                        "drain",
                        "poll",
                        "log",
                        "wait",
                        "kill",
                        "stop",
                        "write",
                        "submit",
                        "close"));
        summary.put("processRegistryBacked", Boolean.TRUE);
        summary.put("trackedSessionId", Boolean.TRUE);
        summary.put("pidExposed", Boolean.TRUE);
        summary.put("stdoutPreview", Boolean.TRUE);
        summary.put("outputRedacted", Boolean.TRUE);
        summary.put("completionEvents", Boolean.TRUE);
        summary.put("stopSupported", Boolean.TRUE);
        summary.put("stdinWriteSubmitCloseSupported", Boolean.TRUE);
        summary.put("startDangerousCommandChecked", Boolean.TRUE);
        summary.put("startHardlineBlocked", Boolean.TRUE);
        summary.put("stdinExecutionPayloadChecked", Boolean.TRUE);
        summary.put(
                "stdinExecutionTools",
                Arrays.asList(
                        ToolNameConstants.EXECUTE_SHELL,
                        ToolNameConstants.EXECUTE_PYTHON,
                        ToolNameConstants.EXECUTE_JS));
        summary.put("stdinPrivilegeWrapperDetection", Boolean.TRUE);
        summary.put("waitTimeoutClamped", Boolean.TRUE);
        summary.put(
                "processWaitTimeoutSeconds",
                Integer.valueOf(resolveProcessWaitTimeoutSeconds(appConfig)));
        summary.put("managedBackgroundRequiredForLongRunningCommands", Boolean.TRUE);
        return summary;
    }

    /**
     * 执行进程相关逻辑。
     *
     * @param action 操作参数。
     * @param command 待执行或解析的命令文本。
     * @param sessionId 当前会话标识。
     * @param cwd 工作目录参数。
     * @param data 数据参数。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @param offset 分页偏移量。
     * @param limit 最大返回数量。
     * @return 返回进程结果。
     */
    @ToolMapping(
            name = "process",
            description =
                    "Manage tracked background processes. Actions: start, list, status/detail,"
                            + " lifecycle, events/drain, poll/log, wait, kill/stop, write, submit,"
                            + " close. Use start for long-running commands instead of shell-level '&',"
                            + " nohup, disown, or watch processes in execute_shell.")
    public String process(
            @Param(
                            name = "action",
                            description =
                                    "start, list, status, detail, lifecycle, events, drain, poll,"
                                            + " log, wait, kill, stop, write, submit, close")
                    String action,
            @Param(name = "command", required = false, description = "Command for action=start")
                    String command,
            @Param(
                            name = "session_id",
                            required = false,
                            description = "Managed process id returned by start")
                    String sessionId,
            @Param(name = "cwd", required = false, description = "Optional working directory")
                    String cwd,
            @Param(
                            name = "data",
                            required = false,
                            description =
                                    "Text for action=write or submit. submit appends a newline.")
                    String data,
            @Param(
                            name = "timeout",
                            required = false,
                            defaultValue = "180",
                            description =
                                    "Wait timeout in seconds for action=wait. Values above the"
                                            + " configured process wait limit are clamped.")
                    Integer timeoutSeconds,
            @Param(
                            name = "offset",
                            required = false,
                            defaultValue = "0",
                            description =
                                    "Line offset for action=log. With offset=0, returns the last"
                                            + " limit lines.")
                    Integer offset,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "200",
                            description = "Max lines to return for action=log.")
                    Integer limit) {
        try {
            String normalized = StrUtil.blankToDefault(action, "list").trim().toLowerCase();
            if ("start".equals(normalized)) {
                return start(command, cwd);
            }
            if ("list".equals(normalized)) {
                return list();
            }
            if ("lifecycle".equals(normalized)) {
                return lifecycle(limit);
            }
            if ("events".equals(normalized) || "drain".equals(normalized)) {
                return events(limit);
            }
            if ("poll".equals(normalized)
                    || "status".equals(normalized)
                    || "detail".equals(normalized)) {
                return poll(sessionId);
            }
            if ("log".equals(normalized)) {
                return log(sessionId, offset, limit);
            }
            if ("wait".equals(normalized)) {
                return waitFor(sessionId, timeoutSeconds);
            }
            if ("kill".equals(normalized) || "stop".equals(normalized)) {
                return stop(sessionId);
            }
            if ("write".equals(normalized)) {
                return write(sessionId, data, false);
            }
            if ("submit".equals(normalized)) {
                return write(sessionId, data, true);
            }
            if ("close".equals(normalized)) {
                return close(sessionId);
            }
            return ToolResultEnvelope.error("Unsupported process action: " + safeText(action))
                    .toJson();
        } catch (Exception e) {
            String message = safeError(e);
            ToolResultEnvelope envelope = ToolResultEnvelope.error(message);
            if (message.startsWith("Unknown process session_id:")) {
                envelope.data("process_status", "not_found");
            }
            return envelope.toJson();
        }
    }

    /**
     * 启动当前组件并准备运行资源。
     *
     * @param command 待执行或解析的命令文本。
     * @param cwd 工作目录参数。
     * @return 返回start结果。
     */
    private String start(String command, String cwd) throws Exception {
        if (StrUtil.isBlank(command)) {
            return ToolResultEnvelope.error("command is required for action=start").toJson();
        }
        File workDir = resolveWorkDir(cwd);
        ProcessRegistry.ManagedProcess managed = processRegistry.start(command, workDir);
        return ToolResultEnvelope.ok("后台进程已启动：" + managed.getId())
                .data("session_id", managed.getId())
                .data("pid", managed.getPid())
                .data("command", SecretRedactor.redact(managed.getCommand()))
                .data("cwd", managed.displayCwd())
                .data("process_status", managed.isExited() ? "exited" : "running")
                .data("uptime_seconds", Long.valueOf(managed.uptimeSeconds()))
                .data("output_preview", cleanOutput(managed.outputPreview(1000)))
                .data("exited", Boolean.valueOf(managed.isExited()))
                .data("exit_code", managed.getExitCode())
                .dataIfNotNull("exit_code_meaning", exitCodeMeaning(managed))
                .data("lifecycle", processRegistry.lifecycleEventsForProcess(managed.getId(), 20))
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .preview("session_id=" + managed.getId() + "\npid=" + managed.getPid())
                .toJson();
    }

    /**
     * 执行日志相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param offset 分页偏移量。
     * @param limit 最大返回数量。
     * @return 返回日志结果。
     */
    private String log(String sessionId, Integer offset, Integer limit) {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        int safeLimit = limit == null ? 200 : Math.max(1, limit.intValue());
        int safeOffset = offset == null ? 0 : Math.max(0, offset.intValue());
        List<String> lines =
                new ArrayList<String>(
                        Arrays.asList(cleanOutput(managed.getOutput()).split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).length() == 0) {
            lines.remove(lines.size() - 1);
        }
        int totalLines = lines.size();
        int start;
        int end;
        if (safeOffset == 0) {
            start = Math.max(0, totalLines - safeLimit);
            end = totalLines;
        } else {
            start = Math.min(safeOffset, totalLines);
            end = Math.min(totalLines, start + safeLimit);
        }
        List<String> selected = lines.subList(start, end);
        String output = joinLines(selected);
        if (managed.isExited()) {
            processRegistry.markCompletionConsumed(managed.getId());
        }
        return ToolResultEnvelope.ok(
                        managed.isExited()
                                ? "后台进程日志已读取：" + managed.getId()
                                : "后台进程日志已读取，进程仍在运行：" + managed.getId())
                .data("session_id", managed.getId())
                .data("process_status", managed.isExited() ? "exited" : "running")
                .data("output", output)
                .data("total_lines", Integer.valueOf(totalLines))
                .data("showing", selected.size() + " lines")
                .data("offset", Integer.valueOf(safeOffset))
                .data("limit", Integer.valueOf(safeLimit))
                .preview(output)
                .toJson();
    }

    /**
     * 执行列表相关逻辑。
     *
     * @return 返回list结果。
     */
    private String list() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ProcessRegistry.ManagedProcess managed : processRegistry.snapshot().values()) {
            items.add(managed.toRedactedMap());
        }
        return ToolResultEnvelope.ok("受管进程：" + items.size() + " 个")
                .data("processes", items)
                .data("count", Integer.valueOf(items.size()))
                .toJson();
    }

    /**
     * 执行events相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回events结果。
     */
    private String events(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, limit.intValue());
        List<Map<String, Object>> events = processRegistry.drainEvents(safeLimit);
        return ToolResultEnvelope.ok("后台进程事件：" + events.size() + " 个")
                .data("events", events)
                .data("count", Integer.valueOf(events.size()))
                .data("limit", Integer.valueOf(safeLimit))
                .toJson();
    }

    /**
     * 执行生命周期相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回生命周期结果。
     */
    private String lifecycle(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, limit.intValue());
        List<Map<String, Object>> events = processRegistry.recentLifecycleEvents(safeLimit);
        return ToolResultEnvelope.ok("后台进程生命周期事件：" + events.size() + " 个")
                .data("events", events)
                .data("count", Integer.valueOf(events.size()))
                .data("limit", Integer.valueOf(safeLimit))
                .toJson();
    }

    /**
     * 执行poll相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回poll结果。
     */
    private String poll(String sessionId) {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        String output = cleanOutput(managed.outputPreview(1000));
        ToolResultEnvelope envelope =
                ToolResultEnvelope.ok(
                                managed.isExited()
                                        ? "后台进程已结束：" + managed.getId()
                                        : "后台进程仍在运行：" + managed.getId())
                        .data("session_id", managed.getId())
                        .data("command", SecretRedactor.redact(managed.getCommand()))
                        .data("process_status", managed.isExited() ? "exited" : "running")
                        .data("pid", managed.getPid())
                        .data("uptime_seconds", Long.valueOf(managed.uptimeSeconds()))
                        .data("output_preview", output)
                        .data("exited", Boolean.valueOf(managed.isExited()))
                        .data("running", Boolean.valueOf(!managed.isExited()))
                        .data("exit_code", managed.getExitCode())
                        .dataIfNotNull("exit_code_meaning", exitCodeMeaning(managed))
                        .data(
                                "lifecycle",
                                processRegistry.lifecycleEventsForProcess(managed.getId(), 20))
                        .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                        .data("output", output)
                        .preview(output)
                        .truncated(managed.isTruncated());
        addNotificationMetadata(envelope, managed);
        if (managed.isExited()) {
            processRegistry.markCompletionConsumed(managed.getId());
        }
        return envelope.toJson();
    }

    /**
     * 执行waitFor相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回wait For结果。
     */
    private String waitFor(String sessionId, Integer timeoutSeconds) throws Exception {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        int maxSeconds = resolveProcessWaitTimeoutSeconds();
        Integer requested =
                timeoutSeconds == null
                        ? null
                        : Integer.valueOf(Math.max(0, timeoutSeconds.intValue()));
        int seconds = requested == null ? maxSeconds : requested.intValue();
        String timeoutNote = null;
        if (requested != null && requested.intValue() > maxSeconds) {
            seconds = maxSeconds;
            timeoutNote =
                    "Requested wait of "
                            + requested
                            + "s was clamped to configured limit of "
                            + maxSeconds
                            + "s";
        }
        boolean finished = processRegistry.waitFor(managed.getId(), seconds * 1000L);
        if (!finished) {
            String output = tail(cleanOutput(managed.getOutput()), 1000);
            String effectiveTimeoutNote =
                    timeoutNote == null
                            ? "Waited " + seconds + "s, process still running"
                            : timeoutNote;
            return ToolResultEnvelope.ok("后台进程等待超时：" + managed.getId())
                    .data("session_id", managed.getId())
                    .data("process_status", "timeout")
                    .data("exited", Boolean.valueOf(false))
                    .data("running", Boolean.valueOf(true))
                    .data("pid", managed.getPid())
                    .data("output", output)
                    .data("timeout_note", effectiveTimeoutNote)
                    .preview(output)
                    .toJson();
        }
        processRegistry.markCompletionConsumed(managed.getId());
        String output = tail(cleanOutput(managed.getOutput()), 2000);
        ToolResultEnvelope envelope =
                ToolResultEnvelope.ok("后台进程已结束：" + managed.getId())
                        .data("session_id", managed.getId())
                        .data("process_status", "exited")
                        .data("exited", Boolean.valueOf(true))
                        .data("running", Boolean.valueOf(false))
                        .data("pid", managed.getPid())
                        .data("exit_code", managed.getExitCode())
                        .dataIfNotNull("exit_code_meaning", exitCodeMeaning(managed))
                        .data(
                                "lifecycle",
                                processRegistry.lifecycleEventsForProcess(managed.getId(), 20))
                        .data("output", output)
                        .preview(output)
                        .truncated(managed.isTruncated());
        if (timeoutNote != null) {
            envelope.data("timeout_note", timeoutNote);
        }
        return envelope.toJson();
    }

    /**
     * 解析进程Wait Timeout Seconds。
     *
     * @return 返回解析后的进程Wait Timeout Seconds。
     */
    private int resolveProcessWaitTimeoutSeconds() {
        return resolveProcessWaitTimeoutSeconds(appConfig);
    }

    /**
     * 解析进程Wait Timeout Seconds。
     *
     * @param appConfig 应用运行配置。
     * @return 返回解析后的进程Wait Timeout Seconds。
     */
    private static int resolveProcessWaitTimeoutSeconds(AppConfig appConfig) {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return 180;
        }
        return Math.max(1, appConfig.getTerminal().getProcessWaitTimeoutSeconds());
    }

    /**
     * 停止当前组件并释放运行状态。
     *
     * @param sessionId 当前会话标识。
     * @return 返回stop结果。
     */
    private String stop(String sessionId) {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        ProcessRegistry.StopResult stopResult = processRegistry.stopDetailed(managed.getId());
        String output = cleanOutput(managed.outputPreview(1000));
        return ToolResultEnvelope.ok(
                        stopResult.isStopped()
                                ? "后台进程已停止：" + managed.getId()
                                : "后台进程未停止：" + managed.getId())
                .data("session_id", managed.getId())
                .data("process_status", stopResult.getStatus())
                .data("stopped", Boolean.valueOf(stopResult.isStopped()))
                .data("exited", Boolean.valueOf(managed.isExited()))
                .data("exit_code", managed.getExitCode())
                .dataIfNotNull("exit_code_meaning", exitCodeMeaning(managed))
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .data("output", output)
                .data("output_preview", output)
                .preview(output)
                .truncated(managed.isTruncated())
                .toJson();
    }

    /**
     * 执行写入相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param data 数据参数。
     * @param appendNewline appendNewline 参数。
     * @return 返回write结果。
     */
    private String write(String sessionId, String data, boolean appendNewline) throws Exception {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        if (managed.isExited()) {
            return alreadyExited(managed);
        }
        String payload = StrUtil.nullToEmpty(data);
        if (appendNewline) {
            payload = payload + "\n";
        }
        assertStdinSafe(managed, payload);
        processRegistry.writeStdin(managed.getId(), payload);
        return ToolResultEnvelope.ok(
                        appendNewline
                                ? "已向后台进程提交输入：" + managed.getId()
                                : "已写入后台进程 stdin：" + managed.getId())
                .data("session_id", managed.getId())
                .data("process_status", "ok")
                .data("bytes_written", Integer.valueOf(payload.length()))
                .data("written", Integer.valueOf(payload.length()))
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .toJson();
    }

    /**
     * 关闭当前组件持有的运行资源。
     *
     * @param sessionId 当前会话标识。
     * @return 返回close结果。
     */
    private String close(String sessionId) throws Exception {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        if (managed.isExited()) {
            return alreadyExited(managed);
        }
        processRegistry.closeStdin(managed.getId());
        return ToolResultEnvelope.ok("后台进程 stdin 已关闭：" + managed.getId())
                .data("session_id", managed.getId())
                .data("process_status", "ok")
                .data("message", "stdin closed")
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .data("exited", Boolean.valueOf(managed.isExited()))
                .toJson();
    }

    /**
     * 执行alreadyExited相关逻辑。
     *
     * @param managed managed 参数。
     * @return 返回already Exited结果。
     */
    private String alreadyExited(ProcessRegistry.ManagedProcess managed) {
        return ToolResultEnvelope.ok("后台进程已结束：" + managed.getId())
                .data("session_id", managed.getId())
                .data("process_status", "already_exited")
                .data("error", "Process has already finished")
                .data("exit_code", managed.getExitCode())
                .dataIfNotNull("exit_code_meaning", exitCodeMeaning(managed))
                .data("exited", Boolean.valueOf(true))
                .data("running", Boolean.valueOf(false))
                .toJson();
    }

    /**
     * 对会把 stdin 当作命令或脚本执行的受管进程重新执行现有安全策略。
     *
     * @param managed 当前受管进程。
     * @param payload 即将写入 stdin 的原始内容。
     */
    private void assertStdinSafe(ProcessRegistry.ManagedProcess managed, String payload) {
        if (managed == null || StrUtil.isBlank(payload)) {
            return;
        }
        String executable = DangerousCommandTextSupport.firstExecutableName(managed.getCommand());
        String toolName = stdinExecutionToolName(executable);
        if (StrUtil.isBlank(toolName)) {
            return;
        }
        try {
            SolonClawCodeExecutionSkills.assertSafe(toolName, payload, securityPolicyService);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "BLOCKED: process stdin 会被 "
                            + safeText(executable)
                            + " 当作命令或脚本执行，已套用同等终端安全策略。\n"
                            + safeError(e),
                    e);
        }
    }

    /**
     * 按真实可执行程序选择 stdin 内容对应的既有安全策略。
     *
     * @param executable 不含路径的可执行程序名称。
     * @return 返回安全策略使用的工具名，普通数据进程返回空字符串。
     */
    private String stdinExecutionToolName(String executable) {
        String normalized = StrUtil.nullToEmpty(executable).toLowerCase(Locale.ROOT);
        if ("sh".equals(normalized)
                || "bash".equals(normalized)
                || "zsh".equals(normalized)
                || "ksh".equals(normalized)
                || "dash".equals(normalized)
                || "cmd".equals(normalized)
                || "cmd.exe".equals(normalized)
                || "powershell".equals(normalized)
                || "powershell.exe".equals(normalized)
                || "pwsh".equals(normalized)
                || "pwsh.exe".equals(normalized)) {
            return ToolNameConstants.EXECUTE_SHELL;
        }
        if ("python".equals(normalized)
                || "python.exe".equals(normalized)
                || "python3".equals(normalized)
                || "python3.exe".equals(normalized)
                || normalized.startsWith("python3.")) {
            return ToolNameConstants.EXECUTE_PYTHON;
        }
        return "node".equals(normalized) || "node.exe".equals(normalized)
                ? ToolNameConstants.EXECUTE_JS
                : "";
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if (StrUtil.isBlank(message) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return safeText(message);
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param text 待处理文本。
     * @return 返回safe Text结果。
     */
    private String safeText(String text) {
        return SecretRedactor.redact(text, 1000);
    }

    /**
     * 追加Notification元数据。
     *
     * @param envelope envelope 参数。
     * @param managed managed 参数。
     */
    private void addNotificationMetadata(
            ToolResultEnvelope envelope, ProcessRegistry.ManagedProcess managed) {
        envelope.data("notify_on_complete", Boolean.valueOf(managed.isNotifyOnComplete()));
        List<String> watchPatterns = managed.getWatchPatterns();
        if (!watchPatterns.isEmpty()) {
            envelope.data("watch_patterns", redactedWatchPatterns(watchPatterns));
        }
    }

    /**
     * 执行redactedWatchPatterns相关逻辑。
     *
     * @param watchPatterns 需要监听并提示的输出模式。。
     * @return 返回redacted Watch Patterns结果。
     */
    private List<String> redactedWatchPatterns(List<String> watchPatterns) {
        if (watchPatterns == null || watchPatterns.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<String> redacted = new ArrayList<String>();
        for (String pattern : watchPatterns) {
            redacted.add(SecretRedactor.redact(pattern));
        }
        return redacted;
    }

    /**
     * 要求进程。
     *
     * @param sessionId 当前会话标识。
     * @return 返回进程结果。
     */
    private ProcessRegistry.ManagedProcess requireProcess(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new IllegalArgumentException("session_id is required");
        }
        ProcessRegistry.ManagedProcess managed = processRegistry.get(sessionId.trim());
        if (managed == null) {
            throw new IllegalArgumentException("Unknown process session_id: " + sessionId);
        }
        return managed;
    }

    /**
     * 解析Work Dir。
     *
     * @param cwd 工作目录参数。
     * @return 返回解析后的Work Dir。
     */
    private File resolveWorkDir(String cwd) {
        String value = StrUtil.blankToDefault(cwd, defaultWorkDir);
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(value);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Invalid workdir: "
                            + verdict.getMessage()
                            + ". Use a simple filesystem path without shell metacharacters.");
        }
        File dir = new File(TerminalPathSupport.toProcessCwd(value));
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("cwd is not a directory: " + safePath(value));
        }
        return dir;
    }

    /**
     * 生成安全展示用的路径。
     *
     * @param path 文件或目录路径。
     * @return 返回safe路径。
     */
    private String safePath(String path) {
        String value = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(path)).trim();
        if (value.length() == 0) {
            return "[unknown]";
        }
        String name = new File(value).getName();
        if (StrUtil.isBlank(name)) {
            name = "[path]";
        }
        return SecretRedactor.redact(name, 400);
    }

    /**
     * 剥离ANSI。
     *
     * @param text 待处理文本。
     * @return 返回strip ANSI结果。
     */
    private String stripAnsi(String text) {
        return TerminalAnsiSanitizer.stripAnsi(text);
    }

    /**
     * 清理输出。
     *
     * @param text 待处理文本。
     * @return 返回clean输出结果。
     */
    private String cleanOutput(String text) {
        return SecretRedactor.redact(stripAnsi(text));
    }

    /**
     * 执行退出CodeMeaning相关逻辑。
     *
     * @param managed managed 参数。
     * @return 返回退出码 Meaning结果。
     */
    private String exitCodeMeaning(ProcessRegistry.ManagedProcess managed) {
        return TerminalExitCodeSemantics.interpret(managed.getCommand(), managed.getExitCode());
    }

    /**
     * 执行joinLines相关逻辑。
     *
     * @param lines lines 参数。
     * @return 返回join Lines结果。
     */
    private String joinLines(List<String> lines) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                buffer.append('\n');
            }
            buffer.append(lines.get(i));
        }
        return buffer.toString();
    }

    /**
     * 执行tail相关逻辑。
     *
     * @param text 待处理文本。
     * @param maxChars maxChars 参数。
     * @return 返回tail结果。
     */
    private String tail(String text, int maxChars) {
        String value = StrUtil.nullToEmpty(text);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars);
    }
}
