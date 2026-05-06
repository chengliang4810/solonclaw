package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Hermes 风格的受管后台进程工具。 */
public class ProcessTools {
    private static final Pattern ANSI_CONTROL_SEQUENCE =
            Pattern.compile(
                    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|P[^\\u001B]*(?:\\u001B\\\\)|[_^][^\\u001B]*(?:\\u001B\\\\)|[@-Z\\\\-_])|[\\u0080-\\u009F]");
    private final ProcessRegistry processRegistry;
    private final String defaultWorkDir;
    private final SecurityPolicyService securityPolicyService;

    public ProcessTools(
            ProcessRegistry processRegistry,
            String defaultWorkDir,
            SecurityPolicyService securityPolicyService) {
        this.processRegistry = processRegistry;
        this.defaultWorkDir = defaultWorkDir;
        this.securityPolicyService = securityPolicyService;
    }

    @ToolMapping(
            name = "process",
            description =
                    "Manage tracked background processes. Actions: start, list, poll/log, wait, kill/stop, write, submit, close. Use start for long-running commands instead of shell-level '&', nohup, disown, or watch processes in execute_shell.")
    public String process(
            @Param(name = "action", description = "start, list, poll, log, wait, kill, stop, write, submit, close")
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
                            description = "Text for action=write or submit. submit appends a newline.")
                    String data,
            @Param(
                            name = "timeout",
                            required = false,
                            defaultValue = "30",
                            description = "Wait timeout in seconds for action=wait")
                    Integer timeoutSeconds,
            @Param(
                            name = "offset",
                            required = false,
                            defaultValue = "0",
                            description = "Line offset for action=log. With offset=0, returns the last limit lines.")
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
            if ("poll".equals(normalized)) {
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
            return ToolResultEnvelope.error("Unsupported process action: " + action).toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .toJson();
        }
    }

    private String start(String command, String cwd) throws Exception {
        if (StrUtil.isBlank(command)) {
            return ToolResultEnvelope.error("command is required for action=start").toJson();
        }
        assertBackgroundSafe(command);
        File workDir = resolveWorkDir(cwd);
        ProcessRegistry.ManagedProcess managed = processRegistry.start(command, workDir);
        return ToolResultEnvelope.ok("后台进程已启动：" + managed.getId())
                .data("session_id", managed.getId())
                .data("pid", managed.getPid())
                .data("command", managed.getCommand())
                .data("cwd", managed.getCwd())
                .data("exited", Boolean.valueOf(managed.isExited()))
                .data("exit_code", managed.getExitCode())
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .preview("session_id=" + managed.getId() + "\npid=" + managed.getPid())
                .toJson();
    }

    private String log(String sessionId, Integer offset, Integer limit) {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        int safeLimit = limit == null ? 200 : Math.max(1, limit.intValue());
        int safeOffset = offset == null ? 0 : Math.max(0, offset.intValue());
        List<String> lines =
                new ArrayList<String>(
                        Arrays.asList(stripAnsi(managed.getOutput()).split("\\r?\\n", -1)));
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
        return ToolResultEnvelope.ok(
                        managed.isExited()
                                ? "后台进程日志已读取：" + managed.getId()
                                : "后台进程日志已读取，进程仍在运行：" + managed.getId())
                .data("session_id", managed.getId())
                .data("status", managed.isExited() ? "exited" : "running")
                .data("output", output)
                .data("total_lines", Integer.valueOf(totalLines))
                .data("showing", selected.size() + " lines")
                .data("offset", Integer.valueOf(safeOffset))
                .data("limit", Integer.valueOf(safeLimit))
                .preview(output)
                .toJson();
    }

    private String list() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ProcessRegistry.ManagedProcess managed : processRegistry.snapshot().values()) {
            items.add(managed.toMap());
        }
        return ToolResultEnvelope.ok("受管进程：" + items.size() + " 个")
                .data("processes", items)
                .data("count", Integer.valueOf(items.size()))
                .toJson();
    }

    private String poll(String sessionId) {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        return ToolResultEnvelope.ok(
                        managed.isExited()
                                ? "后台进程已结束：" + managed.getId()
                                : "后台进程仍在运行：" + managed.getId())
                .data("session_id", managed.getId())
                .data("pid", managed.getPid())
                .data("exited", Boolean.valueOf(managed.isExited()))
                .data("running", Boolean.valueOf(!managed.isExited()))
                .data("exit_code", managed.getExitCode())
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .data("output", managed.getOutput())
                .preview(managed.getOutput())
                .truncated(managed.isTruncated())
                .toJson();
    }

    private String waitFor(String sessionId, Integer timeoutSeconds) throws Exception {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        int seconds = timeoutSeconds == null ? 30 : Math.max(0, timeoutSeconds.intValue());
        processRegistry.waitFor(managed.getId(), seconds * 1000L);
        return poll(managed.getId());
    }

    private String stop(String sessionId) {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        boolean stopped = processRegistry.stop(managed.getId());
        return ToolResultEnvelope.ok(stopped ? "后台进程已停止：" + managed.getId() : "后台进程未停止")
                .data("session_id", managed.getId())
                .data("stopped", Boolean.valueOf(stopped))
                .data("exited", Boolean.valueOf(managed.isExited()))
                .data("exit_code", managed.getExitCode())
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .data("output", managed.getOutput())
                .preview(managed.getOutput())
                .truncated(managed.isTruncated())
                .toJson();
    }

    private String write(String sessionId, String data, boolean appendNewline) throws Exception {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        String payload = StrUtil.nullToEmpty(data);
        if (appendNewline) {
            payload = payload + "\n";
        }
        processRegistry.writeStdin(managed.getId(), payload);
        return ToolResultEnvelope.ok(
                        appendNewline ? "已向后台进程提交输入：" + managed.getId() : "已写入后台进程 stdin：" + managed.getId())
                .data("session_id", managed.getId())
                .data("written", Integer.valueOf(payload.length()))
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .toJson();
    }

    private String close(String sessionId) throws Exception {
        ProcessRegistry.ManagedProcess managed = requireProcess(sessionId);
        processRegistry.closeStdin(managed.getId());
        return ToolResultEnvelope.ok("后台进程 stdin 已关闭：" + managed.getId())
                .data("session_id", managed.getId())
                .data("stdin_closed", Boolean.valueOf(managed.isStdinClosed()))
                .data("exited", Boolean.valueOf(managed.isExited()))
                .toJson();
    }

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

    private File resolveWorkDir(String cwd) {
        String value = StrUtil.blankToDefault(cwd, defaultWorkDir);
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(value);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Blocked: "
                            + verdict.getMessage()
                            + ". Use a simple filesystem path without shell metacharacters.");
        }
        File dir = new File(value);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("cwd is not a directory: " + value);
        }
        return dir;
    }

    private void assertBackgroundSafe(String command) {
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict fileVerdict =
                    securityPolicyService.checkCommandPaths(command);
            if (!fileVerdict.isAllowed()) {
                throw new IllegalArgumentException(
                        "BLOCKED: 文件安全策略阻止访问："
                                + fileVerdict.getMessage()
                                + "\n路径："
                                + StrUtil.nullToEmpty(fileVerdict.getPath()));
            }
            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkCommandUrls(command);
            if (!urlVerdict.isAllowed()) {
                throw new IllegalArgumentException(
                        "BLOCKED: URL 安全策略阻止访问："
                                + urlVerdict.getMessage()
                                + "\nURL: "
                                + SecretRedactor.maskUrl(urlVerdict.getUrl()));
            }
        }

        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(null, securityPolicyService);
        DangerousCommandApprovalService.DetectionResult hardline =
                approvalService.detectHardline(ToolNameConstants.EXECUTE_SHELL, command);
        if (hardline != null) {
            throw new IllegalArgumentException(
                    "BLOCKED: 该 process 调用命中硬阻断安全规则："
                            + StrUtil.blankToDefault(
                                    hardline.getDescription(), hardline.getPatternKey()));
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(ToolNameConstants.EXECUTE_SHELL, command);
        if (dangerous != null) {
            throw new IllegalArgumentException(
                    "BLOCKED: 该 process 调用命中危险命令安全规则："
                            + StrUtil.blankToDefault(
                                    dangerous.getDescription(), dangerous.getPatternKey()));
        }
    }

    private String stripAnsi(String text) {
        return ANSI_CONTROL_SEQUENCE.matcher(StrUtil.nullToEmpty(text)).replaceAll("");
    }

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
}
