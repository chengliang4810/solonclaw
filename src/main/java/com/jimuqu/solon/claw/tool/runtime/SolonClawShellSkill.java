package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.sys.ShellSkill;

/** Solon AI ShellSkill wrapper with local terminal safeguards. */
public class SolonClawShellSkill extends ShellSkill {
    private final AppConfig appConfig;
    private final SecurityPolicyService securityPolicyService;
    private final ProcessRegistry processRegistry;
    private final String shellCmd;
    private final String extension;
    private final List<TerminalOutputTransformer> outputTransformers =
            new CopyOnWriteArrayList<TerminalOutputTransformer>();

    public SolonClawShellSkill(String workDir, AppConfig appConfig) {
        this(workDir, defaultShellCmd(), defaultExtension(), appConfig, null, null);
    }

    public SolonClawShellSkill(
            String workDir, AppConfig appConfig, SecurityPolicyService securityPolicyService) {
        this(workDir, defaultShellCmd(), defaultExtension(), appConfig, securityPolicyService, null);
    }

    public SolonClawShellSkill(
            String workDir,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry) {
        this(
                workDir,
                defaultShellCmd(),
                defaultExtension(),
                appConfig,
                securityPolicyService,
                processRegistry);
    }

    public SolonClawShellSkill(String workDir, String shellCmd, String extension, AppConfig appConfig) {
        this(workDir, shellCmd, extension, appConfig, null, null);
    }

    public SolonClawShellSkill(
            String workDir,
            String shellCmd,
            String extension,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService) {
        this(workDir, shellCmd, extension, appConfig, securityPolicyService, null);
    }

    public SolonClawShellSkill(
            String workDir,
            String shellCmd,
            String extension,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry) {
        super(checkedWorkDir(workDir));
        this.appConfig = appConfig;
        this.securityPolicyService = securityPolicyService;
        this.processRegistry = processRegistry == null ? new ProcessRegistry() : processRegistry;
        this.shellCmd = shellCmd;
        this.extension = extension;
    }

    public void addOutputTransformer(TerminalOutputTransformer transformer) {
        if (transformer != null) {
            outputTransformers.add(transformer);
        }
    }

    public void removeOutputTransformer(TerminalOutputTransformer transformer) {
        if (transformer != null) {
            outputTransformers.remove(transformer);
        }
    }

    @Override
    @ToolMapping(name = "execute_shell", description = "在本地系统中执行单行指令或多行脚本，并获取标准输出。")
    public String execute(
            @Param("code") String code,
            @Param(name = "timeout", required = false, defaultValue = "180000", description = "可选前台超时时间，单位为毫秒；显式传入时最大 600000ms，长时间任务请使用 terminal(background=true)。")
                    Integer timeout) {
        String commandError = validateCommand(code);
        if (commandError != null) {
            return ToolResultEnvelope.error(commandError)
                    .data("exit_code", Integer.valueOf(-1))
                    .toJson();
        }
        SolonClawCodeExecutionSkills.assertSafe(
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.EXECUTE_SHELL,
                code,
                securityPolicyService);
        Integer effectiveTimeout = normalizeForegroundTimeout(timeout, timeout != null);
        if (effectiveTimeout == null) {
            return ToolResultEnvelope.error(foregroundTimeoutExceededMessage(timeout))
                    .data("exit_code", Integer.valueOf(-1))
                    .toJson();
        }
        String executableCode = rewriteCompoundBackground(code);
        SudoTransform transform = transformSudoCommand(executableCode);
        if (!transform.isChanged()) {
            return normalizeTerminalOutput(executeWithStdin(executableCode, null, effectiveTimeout));
        }
        return normalizeTerminalOutput(
                executeWithStdin(transform.getCommand(), transform.getStdin(), effectiveTimeout));
    }

    public String terminal(
            @Param(name = "command", description = "Command to execute") String command,
            @Param(
                            name = "background",
                            required = false,
                            defaultValue = "false",
                            description = "Run in the managed background process registry")
                    Boolean background,
            @Param(
                            name = "timeout",
                            required = false,
                            defaultValue = "180",
                            description = "Timeout in seconds for foreground commands. Maximum 600 seconds when explicitly set; use background=true for long-running commands.")
                    Integer timeoutSeconds,
            @Param(
                            name = "workdir",
                            required = false,
                            description = "Working directory. Defaults to the tool workdir.")
                    String workdir,
            @Param(
                            name = "notify_on_complete",
                            required = false,
                            defaultValue = "false",
                            description = "Accepted for compatibility; delivery is handled by higher-level runtime events.")
                    Boolean notifyOnComplete) {
        return terminal(command, background, timeoutSeconds, workdir, notifyOnComplete, null, null);
    }

    public String terminal(
            @Param(name = "command", description = "Command to execute") String command,
            @Param(
                            name = "background",
                            required = false,
                            defaultValue = "false",
                            description = "Run in the managed background process registry")
                    Boolean background,
            @Param(
                            name = "timeout",
                            required = false,
                            defaultValue = "180",
                            description = "Timeout in seconds for foreground commands. Maximum 600 seconds when explicitly set; use background=true for long-running commands.")
                    Integer timeoutSeconds,
            @Param(
                            name = "workdir",
                            required = false,
                            description = "Working directory. Defaults to the tool workdir.")
                    String workdir,
            @Param(
                            name = "notify_on_complete",
                            required = false,
                            defaultValue = "false",
                            description = "Accepted for compatibility; delivery is handled by higher-level runtime events.")
                    Boolean notifyOnComplete,
            @Param(
                            name = "pty",
                            required = false,
                            defaultValue = "false",
                            description = "Accepted for parameter compatibility. PTY execution is disabled for stdin-pipe commands.")
                    Boolean pty) {
        return terminal(command, background, timeoutSeconds, workdir, notifyOnComplete, pty, null);
    }

    @ToolMapping(
            name = "terminal",
            description =
                    "Terminal tool. Run foreground commands or use background=true for long-running processes; background runs return a process session_id for the process tool.")
    public String terminal(
            @Param(name = "command", description = "Command to execute") String command,
            @Param(
                            name = "background",
                            required = false,
                            defaultValue = "false",
                            description = "Run in the managed background process registry")
                    Boolean background,
            @Param(
                            name = "timeout",
                            required = false,
                            defaultValue = "180",
                            description = "Timeout in seconds for foreground commands. Maximum 600 seconds when explicitly set; use background=true for long-running commands.")
                    Integer timeoutSeconds,
            @Param(
                            name = "workdir",
                            required = false,
                            description = "Working directory. Defaults to the tool workdir.")
                    String workdir,
            @Param(
                            name = "notify_on_complete",
                            required = false,
                            defaultValue = "false",
                            description = "When true and background=true, the process is marked for one completion notification.")
                    Boolean notifyOnComplete,
            @Param(
                            name = "pty",
                            required = false,
                            defaultValue = "false",
                            description = "Accepted for parameter compatibility. PTY execution is disabled for stdin-pipe commands.")
                    Boolean pty,
            @Param(
                            name = "watch_patterns",
                            required = false,
                            description = "Strings to watch for in background output. Mutually exclusive with notify_on_complete.")
                    List<String> watchPatterns) {
        try {
            if (Boolean.TRUE.equals(background)) {
                return startBackground(command, workdir, notifyOnComplete, pty, watchPatterns);
            }
            return runForegroundTerminal(command, timeoutSeconds, workdir);
        } catch (Exception e) {
            return terminalError(safeError(e));
        }
    }

    private String runForegroundTerminal(String command, Integer timeoutSeconds, String workdir)
            throws Exception {
        String commandError = validateCommand(command);
        if (commandError != null) {
            return terminalError(commandError);
        }
        SolonClawCodeExecutionSkills.assertSafeWithApprovalTool(
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.TERMINAL,
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.EXECUTE_SHELL,
                command,
                securityPolicyService);
        boolean explicitTimeout = timeoutSeconds != null;
        int seconds = explicitTimeout ? Math.max(1, timeoutSeconds.intValue()) : 180;
        int timeoutMs = seconds * 1000;
        Integer effectiveTimeout =
                normalizeForegroundTimeout(Integer.valueOf(timeoutMs), explicitTimeout);
        if (effectiveTimeout == null) {
            return terminalError(foregroundTimeoutExceededMessage(Integer.valueOf(timeoutMs)));
        }
        File dir = resolveForegroundWorkdir(workdir);
        String executableCode = rewriteCompoundBackground(command);
        SudoTransform transform = transformSudoCommand(executableCode);
        ForegroundResult result =
                executeForegroundWithRetry(
                        transform.isChanged() ? transform.getCommand() : executableCode,
                        transform.getStdin(),
                        effectiveTimeout,
                        dir);
        return terminalResult(command, result);
    }

    private String terminalResult(String originalCommand, ForegroundResult result) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        String output =
                transformTerminalOutput(
                        originalCommand,
                        outputWithTimeoutNotice(result),
                        result.getExitCode(),
                        result.getError());
        map.put("output", normalizeTerminalOutput(output).trim());
        map.put("exit_code", result.getExitCode());
        map.put("error", result.getError());
        if (result.getRetryCount() > 0) {
            map.put("retry_count", Integer.valueOf(result.getRetryCount()));
        }
        String meaning = TerminalExitCodeSemantics.interpret(originalCommand, result.getExitCode());
        if (meaning != null) {
            map.put("exit_code_meaning", meaning);
        }
        return ONode.serialize(map);
    }

    private String terminalError(String message) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("output", "");
        map.put("exit_code", Integer.valueOf(-1));
        map.put(
                "error",
                SecretRedactor.redact(
                        StrUtil.blankToDefault(message, "Failed to execute command"), 1000));
        map.put("status", "error");
        map.put("success", Boolean.FALSE);
        return ONode.serialize(map);
    }

    private String safeError(Exception e) {
        if (e == null) {
            return "Exception";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return SecretRedactor.redact(message, 1000);
    }

    private String startBackground(
            String command,
            String workdir,
            Boolean notifyOnComplete,
            Boolean pty,
            List<String> watchPatterns)
            throws Exception {
        String commandError = validateCommand(command);
        if (commandError != null) {
            return ToolResultEnvelope.error(commandError)
                    .data("exit_code", Integer.valueOf(-1))
                    .data("background", Boolean.TRUE)
                    .toJson();
        }
        SolonClawCodeExecutionSkills.assertSafeForManagedBackgroundWithApprovalTool(
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.TERMINAL,
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.EXECUTE_SHELL,
                command,
                securityPolicyService);
        File dir = resolveBackgroundWorkdir(workdir);
        String ptyNote = ptyDisabledNote(command, pty);
        List<String> normalizedWatchPatterns = normalizeWatchPatterns(watchPatterns);
        String conflictNote = null;
        if (Boolean.TRUE.equals(notifyOnComplete) && !normalizedWatchPatterns.isEmpty()) {
            conflictNote =
                    "watch_patterns ignored because notify_on_complete=True; "
                            + "these two flags produce duplicate notifications when combined";
            normalizedWatchPatterns = Collections.emptyList();
        }
        SudoTransform transform = transformSudoCommand(command);
        String executableCommand = transform.isChanged() ? transform.getCommand() : command;
        ProcessRegistry.ManagedProcess managed =
                processRegistry.start(
                        executableCommand,
                        dir,
                        Boolean.TRUE.equals(notifyOnComplete),
                        normalizedWatchPatterns);
        if (transform.getStdin() != null) {
            managed.writeStdin(transform.getStdin());
        }
        ToolResultEnvelope envelope = ToolResultEnvelope.ok("后台进程已启动：" + managed.getId())
                .data("session_id", managed.getId())
                .data("command", SecretRedactor.redact(managed.getCommand()))
                .data("cwd", managed.displayCwd())
                .data("pid", managed.getPid())
                .data("status", managed.isExited() ? "exited" : "running")
                .data("background", Boolean.TRUE)
                .data("notify_on_complete", Boolean.TRUE.equals(notifyOnComplete))
                .data("uptime_seconds", Long.valueOf(managed.uptimeSeconds()))
                .data("output_preview", normalizeTerminalOutput(managed.outputPreview(1000)))
                .preview("session_id=" + managed.getId() + "\npid=" + managed.getPid());
        if (conflictNote != null) {
            envelope.data("watch_patterns_ignored", conflictNote);
        }
        if (!normalizedWatchPatterns.isEmpty()) {
            envelope.data("watch_patterns", redactedWatchPatterns(normalizedWatchPatterns));
        }
        if (ptyNote != null) {
            envelope.data("pty", Boolean.FALSE);
            envelope.data("pty_note", ptyNote);
        }
        return envelope.toJson();
    }

    private List<String> redactedWatchPatterns(List<String> watchPatterns) {
        if (watchPatterns == null || watchPatterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> redacted = new ArrayList<String>();
        for (String pattern : watchPatterns) {
            redacted.add(SecretRedactor.redact(pattern));
        }
        return Collections.unmodifiableList(redacted);
    }

    private List<String> normalizeWatchPatterns(List<String> watchPatterns) {
        if (watchPatterns == null || watchPatterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<String>();
        for (String pattern : watchPatterns) {
            String value = StrUtil.nullToEmpty(pattern).trim();
            if (value.length() > 0) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(normalized);
    }

    private String validateCommand(String command) {
        if (command == null) {
            return "Invalid terminal command: expected string, got NoneType/null.";
        }
        if (StrUtil.isBlank(command)) {
            return "Invalid terminal command: expected non-empty string.";
        }
        return null;
    }

    public String rewriteCompoundBackground(String command) {
        return ProcessRegistry.rewriteCompoundBackground(command);
    }

    public boolean commandRequiresPipeStdin(String command) {
        String normalized = StrUtil.nullToEmpty(command).trim().toLowerCase(java.util.Locale.ROOT);
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized.startsWith("gh auth login") && normalized.contains("--with-token");
    }

    public String ptyDisabledNote(String command, Boolean pty) {
        if (!Boolean.TRUE.equals(pty) || !commandRequiresPipeStdin(command)) {
            return null;
        }
        return "PTY disabled for this command because it expects piped stdin/EOF "
                + "(for example gh auth login --with-token). For local background "
                + "processes, call process(action='close') after writing so it receives EOF.";
    }

    public String interpretExitCode(String command, Integer exitCode) {
        return TerminalExitCodeSemantics.interpret(command, exitCode);
    }

    public SudoTransform transformSudoCommand(String command) {
        if (command == null) {
            return SudoTransform.unchanged(null);
        }
        String raw = command;
        String password = resolveSudoPassword();
        if (password == null) {
            return SudoTransform.unchanged(raw);
        }
        SudoRewrite rewrite = rewriteRealSudoInvocations(raw);
        if (!rewrite.isChanged()) {
            return SudoTransform.unchanged(raw);
        }
        return new SudoTransform(rewrite.getCommand(), password + "\n", true);
    }

    public Map<String, Object> sudoRewritePolicySummary() {
        return sudoRewritePolicySummary(resolveSudoPassword() != null);
    }

    public static Map<String, Object> sudoRewritePolicySummary(boolean sudoPasswordConfigured) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("configured", Boolean.valueOf(sudoPasswordConfigured));
        summary.put("envKey", "SUDO_PASSWORD");
        summary.put("configKey", "terminal.sudoPassword");
        summary.put("rewritesRealSudoInvocations", Boolean.TRUE);
        summary.put("stdinPasswordInjection", Boolean.TRUE);
        summary.put("passwordRedacted", Boolean.TRUE);
        summary.put("existingStdinFlagPreserved", Boolean.TRUE);
        summary.put("commentsIgnored", Boolean.TRUE);
        summary.put("quotedSudoIgnored", Boolean.TRUE);
        summary.put("envAssignmentPrefixSupported", Boolean.TRUE);
        summary.put("compoundCommandSupported", Boolean.TRUE);
        summary.put("ptyDisabledForStdinPipe", Boolean.TRUE);
        summary.put("missingPasswordHint", Boolean.TRUE);
        summary.put("description", "Configured sudo commands are rewritten to use sudo -S -p '' with the password sent through stdin; secrets are never embedded in the visible command.");
        return summary;
    }

    public Map<String, Object> terminalOutputPolicySummary() {
        return terminalOutputPolicySummary(appConfig);
    }

    public static Map<String, Object> terminalOutputPolicySummary(AppConfig appConfig) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("ansiStripped", Boolean.TRUE);
        summary.put("ecma48SequencesStripped", Boolean.TRUE);
        summary.put("oscSequencesStripped", Boolean.TRUE);
        summary.put("eightBitC1ControlsStripped", Boolean.TRUE);
        summary.put("displayControlCharsStripped", Boolean.TRUE);
        summary.put("bidiControlsStripped", Boolean.TRUE);
        summary.put("secretRedactionApplied", Boolean.TRUE);
        summary.put("maxInlineChars", Integer.valueOf(resolveToolOutputInlineLimit(appConfig)));
        summary.put("headTailTruncation", Boolean.TRUE);
        summary.put("truncationNoticeIncluded", Boolean.TRUE);
        summary.put("emptySuccessMessage", "执行成功");
        summary.put("timeoutNoticeAppended", Boolean.TRUE);
        summary.put("sudoFailureHintAppended", Boolean.TRUE);
        summary.put("outputTransformersSupported", Boolean.TRUE);
        summary.put("transformerFailureIsolated", Boolean.TRUE);
        summary.put("exitCodeSemanticsAvailable", Boolean.TRUE);
        summary.put("exitCodeSemantics", TerminalExitCodeSemantics.policySummary());
        summary.put("foregroundRetryErrorsInterpreted", Boolean.TRUE);
        summary.put("description", "Terminal output is ANSI-stripped, secret-redacted, bounded with a head/tail truncation notice, and enriched with timeout, sudo, and exit-code guidance before it is returned.");
        return summary;
    }

    private SudoRewrite rewriteRealSudoInvocations(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = command.length();
        boolean commandStart = true;
        boolean found = false;
        while (i < n) {
            char ch = command.charAt(i);
            if (Character.isWhitespace(ch)) {
                out.append(ch);
                if (ch == '\n') {
                    commandStart = true;
                }
                i++;
                continue;
            }
            if (ch == '#' && commandStart) {
                int commentEnd = command.indexOf('\n', i);
                if (commentEnd < 0) {
                    out.append(command.substring(i));
                    break;
                }
                out.append(command, i, commentEnd);
                i = commentEnd;
                continue;
            }
            if (startsWithAny(command, i, "&&", "||", ";;")) {
                out.append(command, i, i + 2);
                i += 2;
                commandStart = true;
                continue;
            }
            if (ch == ';' || ch == '|' || ch == '&' || ch == '(') {
                out.append(ch);
                i++;
                commandStart = true;
                continue;
            }
            if (ch == ')') {
                out.append(ch);
                i++;
                commandStart = false;
                continue;
            }

            Token token = readShellToken(command, i);
            if (commandStart && "sudo".equals(token.getValue())) {
                if (hasSudoStdinFlag(command, token.getEnd())) {
                    out.append(token.getValue());
                } else {
                    out.append("sudo -S -p ''");
                    found = true;
                }
            } else {
                out.append(token.getValue());
            }

            if (commandStart && looksLikeEnvAssignment(token.getValue())) {
                commandStart = true;
            } else {
                commandStart = false;
            }
            i = token.getEnd();
        }
        return new SudoRewrite(out.toString(), found);
    }

    private Token readShellToken(String command, int start) {
        int i = start;
        int n = command.length();
        while (i < n) {
            char ch = command.charAt(i);
            if (Character.isWhitespace(ch) || ch == ';' || ch == '|' || ch == '&' || ch == '(' || ch == ')') {
                break;
            }
            if (ch == '\'') {
                i++;
                while (i < n && command.charAt(i) != '\'') {
                    i++;
                }
                if (i < n) {
                    i++;
                }
                continue;
            }
            if (ch == '"') {
                i++;
                while (i < n) {
                    char inner = command.charAt(i);
                    if (inner == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (inner == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (ch == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            i++;
        }
        return new Token(command.substring(start, i), i);
    }

    private boolean hasSudoStdinFlag(String command, int index) {
        int i = index;
        int n = command.length();
        while (i < n && Character.isWhitespace(command.charAt(i)) && command.charAt(i) != '\n') {
            i++;
        }
        if (i >= n || command.charAt(i) != '-') {
            return false;
        }
        Token option = readShellToken(command, i);
        String value = option.getValue();
        return value.indexOf('S') >= 0;
    }

    private boolean startsWithAny(String value, int index, String first, String second, String third) {
        return value.startsWith(first, index)
                || value.startsWith(second, index)
                || value.startsWith(third, index);
    }

    private boolean looksLikeEnvAssignment(String token) {
        if (StrUtil.isBlank(token) || token.startsWith("=")) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals);
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (i == 0) {
                if (!(Character.isLetter(ch) || ch == '_')) {
                    return false;
                }
            } else if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                return false;
            }
        }
        return true;
    }

    private String resolveSudoPassword() {
        String envValue = System.getenv("SUDO_PASSWORD");
        if (envValue != null) {
            return envValue;
        }
        if (appConfig != null
                && appConfig.getTerminal() != null
                && appConfig.getTerminal().getSudoPassword() != null) {
            return appConfig.getTerminal().getSudoPassword();
        }
        return null;
    }

    private String executeWithStdin(String code, String stdin, Integer timeoutMs) {
        ForegroundResult result =
                executeForeground(code, stdin, timeoutMs, resolveForegroundWorkdir(null));
        String output;
        if (result.getError() != null) {
            if (result.isTimedOut()) {
                String timeoutNotice =
                        "执行超时：运行时间超过 "
                                + timeoutMs
                                + " 毫秒。"
                                + " "
                                + StrUtil.blankToDefault(result.getError(), "Command timed out");
                output = outputWithNotice(result.getOutput(), timeoutNotice).trim();
                return transformTerminalOutput(code, output, result.getExitCode(), result.getError());
            }
            output = result.getError();
            return transformTerminalOutput(code, output, result.getExitCode(), result.getError());
        }
        output = StrUtil.nullToEmpty(result.getOutput()).trim();
        output = output.length() == 0 ? "执行成功" : output;
        return transformTerminalOutput(code, output, result.getExitCode(), result.getError());
    }

    private String transformTerminalOutput(
            String command, String output, Integer exitCode, String error) {
        String value = appendSudoFailureHint(StrUtil.nullToEmpty(output));
        if (outputTransformers.isEmpty()) {
            return value;
        }
        TerminalOutputContext context = new TerminalOutputContext(command, value, exitCode, error);
        for (TerminalOutputTransformer transformer : outputTransformers) {
            try {
                String transformed = transformer.transform(context);
                if (transformed != null) {
                    return transformed;
                }
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    private String appendSudoFailureHint(String output) {
        String value = StrUtil.nullToEmpty(output);
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (!isSudoPasswordFailure(normalized)) {
            return value;
        }
        String hint =
                "提示：sudo 需要密码或交互式终端。"
                        + "如需在消息渠道或后台任务中使用 sudo，请在运行环境设置 SUDO_PASSWORD，"
                        + "或在 dashboard 安全配置中设置 terminal.sudoPassword。";
        if (value.contains(hint)) {
            return value;
        }
        return value.length() == 0 ? hint : value + "\n\n" + hint;
    }

    private boolean isSudoPasswordFailure(String normalizedOutput) {
        return normalizedOutput.contains("sudo: a password is required")
                || normalizedOutput.contains("sudo: no tty present")
                || normalizedOutput.contains("sudo: a terminal is required")
                || normalizedOutput.contains("sudo: no password was provided")
                || normalizedOutput.contains("sudo: a password is required to run sudo");
    }

    private String outputWithTimeoutNotice(ForegroundResult result) {
        String output = StrUtil.nullToEmpty(result == null ? null : result.getOutput()).trim();
        if (result == null || !result.isTimedOut()) {
            return output;
        }
        String notice = StrUtil.blankToDefault(result.getError(), "Command timed out").trim();
        if (output.length() == 0) {
            return notice;
        }
        return output + "\n" + notice;
    }

    private String outputWithNotice(String outputText, String notice) {
        String output = StrUtil.nullToEmpty(outputText).trim();
        String message = StrUtil.nullToEmpty(notice).trim();
        if (output.length() == 0) {
            return message;
        }
        if (message.length() == 0) {
            return output;
        }
        return output + "\n" + message;
    }

    private ForegroundResult executeForegroundWithRetry(
            String code, String stdin, Integer timeoutMs, File directory) {
        int maxRetries = resolveForegroundMaxRetries();
        int retryDelaySeconds = resolveForegroundRetryBaseDelaySeconds();
        ForegroundResult result = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            result = executeForeground(code, stdin, timeoutMs, directory);
            if (!shouldRetryForegroundExecution(result) || attempt >= maxRetries) {
                return result.withRetryCount(attempt);
            }
            sleepBeforeRetry(retryDelaySeconds, attempt + 1);
        }
        return result == null ? new ForegroundResult("", Integer.valueOf(-1), "系统失败") : result;
    }

    private boolean shouldRetryForegroundExecution(ForegroundResult result) {
        if (result == null || result.isTimedOut()) {
            return false;
        }
        return result.getExitCode() != null
                && result.getExitCode().intValue() == -1
                && StrUtil.isNotBlank(result.getError());
    }

    private int resolveForegroundMaxRetries() {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return 3;
        }
        return Math.max(0, appConfig.getTerminal().getForegroundMaxRetries());
    }

    private int resolveForegroundRetryBaseDelaySeconds() {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return 2;
        }
        return Math.max(0, appConfig.getTerminal().getForegroundRetryBaseDelaySeconds());
    }

    private void sleepBeforeRetry(int baseDelaySeconds, int retryAttempt) {
        if (baseDelaySeconds <= 0) {
            return;
        }
        long delayMs = (long) baseDelaySeconds * 1000L * (1L << Math.max(0, retryAttempt - 1));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String prependShellInit(String code) {
        List<String> files = resolveShellInitFiles();
        return prependShellInit(code, files, isWindows());
    }

    public static String prependShellInit(String code, List<String> files, boolean windows) {
        if (windows) {
            return StrUtil.nullToEmpty(code);
        }
        if (files.isEmpty()) {
            return StrUtil.nullToEmpty(code);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("set +e\n");
        for (String file : files) {
            String safe = file.replace("'", "'\\''");
            builder.append("[ -r '")
                    .append(safe)
                    .append("' ] && . '")
                    .append(safe)
                    .append("' 2>/dev/null || true\n");
        }
        builder.append(StrUtil.nullToEmpty(code));
        return builder.toString();
    }

    List<String> resolveShellInitFiles() {
        List<String> configured = Collections.emptyList();
        boolean autoSource = true;
        if (appConfig != null
                && appConfig.getTerminal() != null
                && appConfig.getTerminal().getShellInitFiles() != null) {
            configured = appConfig.getTerminal().getShellInitFiles();
            autoSource = appConfig.getTerminal().isAutoSourceBashrc();
        }
        String home = StrUtil.blankToDefault(System.getenv("HOME"), System.getProperty("user.home"));
        return resolveShellInitFiles(
                configured,
                autoSource,
                isWindows(),
                home,
                System.getenv(),
                effectiveSecurityPolicyService());
    }

    public static List<String> resolveShellInitFiles(
            List<String> configured,
            boolean autoSourceBashrc,
            boolean windows,
            String home,
            Map<String, String> env) {
        return resolveShellInitFiles(
                configured, autoSourceBashrc, windows, home, env, null);
    }

    static List<String> resolveShellInitFiles(
            List<String> configured,
            boolean autoSourceBashrc,
            boolean windows,
            String home,
            Map<String, String> env,
            SecurityPolicyService securityPolicyService) {
        if (windows) {
            return Collections.emptyList();
        }
        List<String> candidates = new ArrayList<String>();
        boolean explicit = configured != null && !configured.isEmpty();
        if (explicit) {
            candidates.addAll(configured);
        } else if (autoSourceBashrc) {
            candidates.add("~/.profile");
            candidates.add("~/.bash_profile");
            candidates.add("~/.bashrc");
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> resolved = new ArrayList<String>();
        for (String item : candidates) {
            String path = expandShellInitPath(item, home, env);
            if (StrUtil.isBlank(path)) {
                continue;
            }
            try {
                Path normalized = Paths.get(path);
                if (Files.isRegularFile(normalized)) {
                    String value = normalized.toString();
                    if (!explicit || isSafeConfiguredShellInit(value, securityPolicyService)) {
                        resolved.add(value);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.unmodifiableList(resolved);
    }

    private static boolean isSafeConfiguredShellInit(
            String path, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService == null) {
            return true;
        }
        SecurityPolicyService.FileVerdict verdict = securityPolicyService.checkPath(path, false);
        return verdict.isAllowed();
    }

    private SecurityPolicyService effectiveSecurityPolicyService() {
        if (securityPolicyService != null) {
            return securityPolicyService;
        }
        if (appConfig == null) {
            return null;
        }
        return new SecurityPolicyService(appConfig);
    }

    private static String expandShellInitPath(String raw, String home, Map<String, String> env) {
        String value = StrUtil.nullToEmpty(raw).trim();
        if (value.length() == 0) {
            return "";
        }
        if (value.equals("~") || value.startsWith("~/")) {
            if (StrUtil.isBlank(home)) {
                return value;
            }
            value = home + value.substring(1);
        }
        Map<String, String> values = env == null ? Collections.<String, String>emptyMap() : env;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String envValue = entry.getValue() == null ? "" : entry.getValue();
            value = value.replace("${" + name + "}", envValue);
            value = value.replace("$" + name, envValue);
        }
        return value;
    }

    private ForegroundResult executeForeground(
            String code, String stdin, Integer timeoutMs, File directory) {
        Path tempScript = null;
        try {
            File safeDirectory = directory == null ? resolveSafeCwd(workPath.toString()) : directory;
            tempScript = Files.createTempFile(safeDirectory.toPath(), "_script_", extension);
            Files.write(tempScript, prependShellInit(code).getBytes(StandardCharsets.UTF_8));
            java.util.List<String> command =
                    new java.util.ArrayList<String>(
                            java.util.Arrays.asList(shellCmd.split("\\s+")));
            command.add(tempScript.toAbsolutePath().toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(safeDirectory);
            builder.redirectErrorStream(true);
            SubprocessEnvironmentSanitizer.sanitize(builder.environment(), appConfig);
            Process process = builder.start();
            if (stdin == null) {
                process.getOutputStream().close();
            }
            final StringBuilder outputBuffer = new StringBuilder();
            CompletableFuture<Void> outputFuture =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    readOutput(process, outputBuffer);
                                } catch (Exception e) {
                                    appendOutput(outputBuffer, "系统失败: " + safeError(e));
                                }
                            });
            if (stdin != null) {
                OutputStreamWriter writer =
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(stdin);
                writer.flush();
                writer.close();
            }
            int timeout = timeoutMs == null || timeoutMs < 0 ? 120000 : timeoutMs.intValue();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ForegroundResult(
                        bufferedOutput(outputBuffer),
                        Integer.valueOf(-1),
                        "Command timed out after " + timeout + " ms",
                        true);
            }
            outputFuture.get(1, TimeUnit.SECONDS);
            String output = bufferedOutput(outputBuffer);
            return new ForegroundResult(output, Integer.valueOf(process.exitValue()), null);
        } catch (Exception e) {
            return new ForegroundResult("", Integer.valueOf(-1), "系统失败: " + safeError(e));
        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Integer normalizeForegroundTimeout(Integer timeoutMs) {
        return normalizeForegroundTimeout(timeoutMs, true);
    }

    private Integer normalizeForegroundTimeout(Integer timeoutMs, boolean explicitTimeout) {
        if (timeoutMs == null || timeoutMs < 0) {
            return 180000;
        }
        if (!explicitTimeout) {
            return timeoutMs;
        }
        int maxSeconds = 600;
        if (appConfig != null && appConfig.getTerminal() != null) {
            maxSeconds = appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
        }
        int maxMs = Math.max(1, maxSeconds) * 1000;
        if (timeoutMs > maxMs) {
            return null;
        }
        return timeoutMs;
    }

    private String foregroundTimeoutExceededMessage(Integer timeoutMs) {
        int maxSeconds = 600;
        if (appConfig != null && appConfig.getTerminal() != null) {
            maxSeconds = appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
        }
        return "Foreground timeout "
                + timeoutMs
                + "ms exceeds the maximum of "
                + (Math.max(1, maxSeconds) * 1000)
                + "ms. Use background=true with notify_on_complete=true for long-running commands.";
    }

    private File resolveBackgroundWorkdir(String workdir) {
        String value = StrUtil.blankToDefault(workdir, workPath.toString());
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(value);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Blocked: "
                            + verdict.getMessage()
                            + ". Use a simple filesystem path without shell metacharacters.");
        }
        return resolveSafeCwd(value, workPath.toFile());
    }

    private File resolveForegroundWorkdir(String workdir) {
        return resolveBackgroundWorkdir(workdir);
    }

    public static File resolveSafeCwd(String cwd) {
        return resolveSafeCwd(cwd, new File(System.getProperty("java.io.tmpdir")));
    }

    public static File resolveSafeCwd(String cwd, File fallback) {
        return TerminalPathSupport.resolveSafeCwd(cwd, fallback);
    }

    private String readOutput(Process process) throws Exception {
        StringBuilder buffer = new StringBuilder();
        readOutput(process, buffer);
        return bufferedOutput(buffer);
    }

    private void readOutput(Process process, StringBuilder buffer) throws Exception {
        InputStreamReader reader =
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        char[] chars = new char[4096];
        int read;
        while ((read = reader.read(chars)) != -1) {
            appendOutput(buffer, chars, read);
            if (bufferedLength(buffer) > 1024 * 1024) {
                process.destroyForcibly();
                appendOutput(buffer, "\n... [输出已截断]");
                break;
            }
        }
    }

    private void appendOutput(StringBuilder buffer, char[] chars, int length) {
        synchronized (buffer) {
            buffer.append(chars, 0, length);
        }
    }

    private void appendOutput(StringBuilder buffer, String text) {
        synchronized (buffer) {
            buffer.append(StrUtil.nullToEmpty(text));
        }
    }

    private int bufferedLength(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.length();
        }
    }

    private String bufferedOutput(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    private String normalizeTerminalOutput(String output) {
        String value = StrUtil.nullToEmpty(output);
        int maxOutputChars = resolveMaxOutputChars();
        if (value.length() > maxOutputChars) {
            int headChars = Math.max(1, (int) (maxOutputChars * 0.4));
            int tailChars = Math.max(1, maxOutputChars - headChars);
            int omitted = Math.max(0, value.length() - headChars - tailChars);
            String notice =
                    "\n\n... [OUTPUT TRUNCATED - "
                            + omitted
                            + " chars omitted out of "
                            + value.length()
                            + " total] ...\n\n";
            value = value.substring(0, headChars) + notice + value.substring(value.length() - tailChars);
        }
        return SecretRedactor.redact(TerminalAnsiSanitizer.stripAnsi(value));
    }

    private int resolveMaxOutputChars() {
        return resolveToolOutputInlineLimit(appConfig);
    }

    private static int resolveToolOutputInlineLimit(AppConfig appConfig) {
        int value = 50000;
        if (appConfig != null && appConfig.getTask() != null) {
            value = appConfig.getTask().getToolOutputInlineLimit();
        }
        return Math.max(256, value);
    }

    private static String defaultShellCmd() {
        return isWindows() ? "cmd /c" : (checkCmd("bash") ? "bash" : "/bin/sh");
    }

    private static String defaultExtension() {
        return isWindows() ? ".bat" : ".sh";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean checkCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd + " --version");
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String checkedWorkDir(String workDir) {
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(workDir);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Blocked: " + verdict.getMessage() + ". Use a simple filesystem path without shell metacharacters.");
        }
        return workDir;
    }

    public static class SudoTransform {
        private final String command;
        private final String stdin;
        private final boolean changed;

        private SudoTransform(String command, String stdin, boolean changed) {
            this.command = command;
            this.stdin = stdin;
            this.changed = changed;
        }

        private static SudoTransform unchanged(String command) {
            return new SudoTransform(command, null, false);
        }

        public String getCommand() {
            return command;
        }

        public String getStdin() {
            return stdin;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    public interface TerminalOutputTransformer {
        String transform(TerminalOutputContext context) throws Exception;
    }

    public static class TerminalOutputContext {
        private final String command;
        private final String output;
        private final Integer exitCode;
        private final String error;

        private TerminalOutputContext(String command, String output, Integer exitCode, String error) {
            this.command = command;
            this.output = output;
            this.exitCode = exitCode;
            this.error = error;
        }

        public String getCommand() {
            return command;
        }

        public String getOutput() {
            return output;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public String getError() {
            return error;
        }
    }

    private static class SudoRewrite {
        private final String command;
        private final boolean changed;

        private SudoRewrite(String command, boolean changed) {
            this.command = command;
            this.changed = changed;
        }

        public String getCommand() {
            return command;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    private static class Token {
        private final String value;
        private final int end;

        private Token(String value, int end) {
            this.value = value;
            this.end = end;
        }

        public String getValue() {
            return value;
        }

        public int getEnd() {
            return end;
        }
    }

    private static class ForegroundResult {
        private final String output;
        private final Integer exitCode;
        private final String error;
        private final boolean timedOut;
        private final int retryCount;

        private ForegroundResult(String output, Integer exitCode, String error) {
            this(output, exitCode, error, false, 0);
        }

        private ForegroundResult(String output, Integer exitCode, String error, boolean timedOut) {
            this(output, exitCode, error, timedOut, 0);
        }

        private ForegroundResult(
                String output, Integer exitCode, String error, boolean timedOut, int retryCount) {
            this.output = output;
            this.exitCode = exitCode;
            this.error = error;
            this.timedOut = timedOut;
            this.retryCount = retryCount;
        }

        public String getOutput() {
            return output;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public String getError() {
            return error;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public ForegroundResult withRetryCount(int retryCount) {
            return new ForegroundResult(output, exitCode, error, timedOut, Math.max(0, retryCount));
        }
    }
}

