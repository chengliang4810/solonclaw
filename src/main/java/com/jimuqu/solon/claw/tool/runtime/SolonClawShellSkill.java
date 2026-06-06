package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.BufferedWriter;
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
import org.noear.solon.ai.skills.sys.ShellSkill;
import org.noear.solon.annotation.Param;

/** 承载Solon项目终端技能相关状态和辅助逻辑。 */
public class SolonClawShellSkill extends ShellSkill {
    /** 工作目录MARKERPREFIX的统一常量值。 */
    private static final String CWD_MARKER_PREFIX = "__SOLON_CLAW_CWD__=";

    /** 注入应用配置，用于Solon项目终端技能。 */
    private final AppConfig appConfig;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录Solon项目终端技能中的进程注册表。 */
    private final ProcessRegistry processRegistry;

    /** 记录Solon项目终端技能中的终端命令。 */
    private final String shellCmd;

    /** 记录Solon项目终端技能中的扩展名。 */
    private final String extension;

    /** 记录Solon项目终端技能中的默认工作目录。 */
    private final File defaultWorkDir;

    /** 保存输出转换器集合，维持调用顺序或去重语义。 */
    private final List<TerminalOutputTransformer> outputTransformers =
            new CopyOnWriteArrayList<TerminalOutputTransformer>();

    /** 记录Solon项目终端技能中的live工作目录。 */
    private volatile File liveWorkDir;

    /**
     * 创建Solon项目Shell技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param appConfig 应用运行配置。
     */
    public SolonClawShellSkill(String workDir, AppConfig appConfig) {
        this(workDir, defaultShellCmd(), defaultExtension(), appConfig, null, null);
    }

    /**
     * 创建Solon项目Shell技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public SolonClawShellSkill(
            String workDir, AppConfig appConfig, SecurityPolicyService securityPolicyService) {
        this(
                workDir,
                defaultShellCmd(),
                defaultExtension(),
                appConfig,
                securityPolicyService,
                null);
    }

    /**
     * 创建Solon项目Shell技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     */
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

    /**
     * 创建Solon项目Shell技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param shell命令 终端命令参数。
     * @param extension 扩展名参数。
     * @param appConfig 应用运行配置。
     */
    public SolonClawShellSkill(
            String workDir, String shellCmd, String extension, AppConfig appConfig) {
        this(workDir, shellCmd, extension, appConfig, null, null);
    }

    /**
     * 创建Solon项目Shell技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param shell命令 终端命令参数。
     * @param extension 扩展名参数。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public SolonClawShellSkill(
            String workDir,
            String shellCmd,
            String extension,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService) {
        this(workDir, shellCmd, extension, appConfig, securityPolicyService, null);
    }

    /**
     * 创建Solon项目Shell技能实例，并注入运行所需依赖。
     *
     * @param workDir 命令执行工作目录。
     * @param shell命令 终端命令参数。
     * @param extension 扩展名参数。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     */
    public SolonClawShellSkill(
            String workDir,
            String shellCmd,
            String extension,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry) {
        super(checkedWorkDir(workDir));
        this.defaultWorkDir = resolveSafeCwd(workPath.toString());
        this.liveWorkDir = defaultWorkDir;
        this.appConfig = appConfig;
        this.securityPolicyService = securityPolicyService;
        this.processRegistry = processRegistry == null ? new ProcessRegistry() : processRegistry;
        this.shellCmd = shellCmd;
        this.extension = extension;
    }

    /**
     * 追加输出Transformer。
     *
     * @param transformer transformer 参数。
     */
    public void addOutputTransformer(TerminalOutputTransformer transformer) {
        if (transformer != null) {
            outputTransformers.add(transformer);
        }
    }

    /**
     * 移除输出Transformer。
     *
     * @param transformer transformer 参数。
     */
    public void removeOutputTransformer(TerminalOutputTransformer transformer) {
        if (transformer != null) {
            outputTransformers.remove(transformer);
        }
    }

    /**
     * 执行当前回调或工具调用。
     *
     * @param code code 参数。
     * @param timeout 超时时间或等待上限。
     * @return 返回执行结果。
     */
    @Override
    @ToolMapping(name = "execute_shell", description = "在本地系统中执行单行指令或多行脚本，并获取标准输出。")
    public String execute(
            @Param("code") String code,
            @Param(
                            name = "timeout",
                            required = false,
                            defaultValue = "180000",
                            description =
                                    "可选前台超时时间，单位为毫秒；显式传入时最大 600000ms，长时间任务请使用 terminal(background=true)。")
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
            return normalizeTerminalOutput(
                    executeWithStdin(executableCode, null, effectiveTimeout));
        }
        return normalizeTerminalOutput(
                executeWithStdin(transform.getCommand(), transform.getStdin(), effectiveTimeout));
    }

    /**
     * 执行终端相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param background 是否后台运行。。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @param workdir 命令执行工作目录。
     * @param notifyOnComplete 后台任务完成后是否通知。。
     * @return 返回终端结果。
     */
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
                            description =
                                    "Timeout in seconds for foreground commands. Maximum 600 seconds when explicitly set; use background=true for long-running commands.")
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
                            description =
                                    "Accepted for compatibility; delivery is handled by higher-level runtime events.")
                    Boolean notifyOnComplete) {
        return terminal(command, background, timeoutSeconds, workdir, notifyOnComplete, null, null);
    }

    /**
     * 执行终端相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param background 是否后台运行。。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @param workdir 命令执行工作目录。
     * @param notifyOnComplete 后台任务完成后是否通知。。
     * @param pty 是否使用伪终端。。
     * @return 返回终端结果。
     */
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
                            description =
                                    "Timeout in seconds for foreground commands. Maximum 600 seconds when explicitly set; use background=true for long-running commands.")
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
                            description =
                                    "Accepted for compatibility; delivery is handled by higher-level runtime events.")
                    Boolean notifyOnComplete,
            @Param(
                            name = "pty",
                            required = false,
                            defaultValue = "false",
                            description =
                                    "Accepted for parameter compatibility. PTY execution is disabled for stdin-pipe commands.")
                    Boolean pty) {
        return terminal(command, background, timeoutSeconds, workdir, notifyOnComplete, pty, null);
    }

    /**
     * 执行终端相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param background 是否后台运行。。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @param workdir 命令执行工作目录。
     * @param notifyOnComplete 后台任务完成后是否通知。。
     * @param pty 是否使用伪终端。。
     * @param watchPatterns 需要监听并提示的输出模式。。
     * @return 返回终端结果。
     */
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
                            description =
                                    "Timeout in seconds for foreground commands. Maximum 600 seconds when explicitly set; use background=true for long-running commands.")
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
                            description =
                                    "When true and background=true, the process is marked for one completion notification.")
                    Boolean notifyOnComplete,
            @Param(
                            name = "pty",
                            required = false,
                            defaultValue = "false",
                            description =
                                    "Accepted for parameter compatibility. PTY execution is disabled for stdin-pipe commands.")
                    Boolean pty,
            @Param(
                            name = "watch_patterns",
                            required = false,
                            description =
                                    "Strings to watch for in background output. Mutually exclusive with notify_on_complete.")
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

    /**
     * 运行Foreground终端。
     *
     * @param command 待执行或解析的命令文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @param workdir 命令执行工作目录。
     * @return 返回Foreground终端结果。
     */
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
        boolean explicitWorkdir = StrUtil.isNotBlank(workdir);
        File dir = resolveForegroundWorkdir(workdir);
        String executableCode = rewriteCompoundBackground(command);
        SudoTransform transform = transformSudoCommand(executableCode);
        ForegroundResult result =
                executeForegroundWithRetry(
                        transform.isChanged() ? transform.getCommand() : executableCode,
                        transform.getStdin(),
                        effectiveTimeout,
                        dir);
        if (!explicitWorkdir) {
            updateLiveWorkDir(result.getCwd());
        }
        return terminalResult(command, result);
    }

    /**
     * 执行终端结果相关逻辑。
     *
     * @param originalCommand original命令参数。
     * @param result 结果响应或执行结果。
     * @return 返回终端结果。
     */
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

    /**
     * 执行终端错误相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回终端Error结果。
     */
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

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        if (e == null) {
            return "Exception";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return SecretRedactor.redact(message, 1000);
    }

    /**
     * 启动Background。
     *
     * @param command 待执行或解析的命令文本。
     * @param workdir 命令执行工作目录。
     * @param notifyOnComplete 后台任务完成后是否通知。。
     * @param pty 是否使用伪终端。。
     * @param watchPatterns 需要监听并提示的输出模式。。
     * @return 返回Background结果。
     */
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
        SudoTransform transform = transformSudoCommand(command);
        String executableCommand = transform.isChanged() ? transform.getCommand() : command;
        if (transform.isChanged()) {
            DangerousCommandApprovalService.grantCurrentThreadApproval(
                    com.jimuqu.solon.claw.support.constants.ToolNameConstants.TERMINAL,
                    executableCommand);
        }
        SolonClawCodeExecutionSkills.assertSafeForManagedBackgroundWithApprovalTool(
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.TERMINAL,
                com.jimuqu.solon.claw.support.constants.ToolNameConstants.EXECUTE_SHELL,
                executableCommand,
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
        ProcessRegistry.ManagedProcess managed =
                processRegistry.start(
                        executableCommand,
                        dir,
                        Boolean.TRUE.equals(notifyOnComplete),
                        normalizedWatchPatterns);
        if (transform.getStdin() != null) {
            managed.writeStdin(transform.getStdin());
        }
        ToolResultEnvelope envelope =
                ToolResultEnvelope.ok("后台进程已启动：" + managed.getId())
                        .data("session_id", managed.getId())
                        .data("command", SecretRedactor.redact(managed.getCommand()))
                        .data("cwd", managed.displayCwd())
                        .data("pid", managed.getPid())
                        .data("status", managed.isExited() ? "exited" : "running")
                        .data("background", Boolean.TRUE)
                        .data("notify_on_complete", Boolean.TRUE.equals(notifyOnComplete))
                        .data("uptime_seconds", Long.valueOf(managed.uptimeSeconds()))
                        .data(
                                "output_preview",
                                normalizeTerminalOutput(managed.outputPreview(1000)))
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

    /**
     * 执行redactedWatchPatterns相关逻辑。
     *
     * @param watchPatterns 需要监听并提示的输出模式。。
     * @return 返回redacted Watch Patterns结果。
     */
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

    /**
     * 规范化Watch Patterns。
     *
     * @param watchPatterns 需要监听并提示的输出模式。。
     * @return 返回Watch Patterns结果。
     */
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

    /**
     * 校验命令。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令结果。
     */
    private String validateCommand(String command) {
        if (command == null) {
            return "Invalid terminal command: expected string, got NoneType/null.";
        }
        if (StrUtil.isBlank(command)) {
            return "Invalid terminal command: expected non-empty string.";
        }
        return null;
    }

    /**
     * 执行rewriteCompoundBackground相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回rewrite Compound Background结果。
     */
    public String rewriteCompoundBackground(String command) {
        return ProcessRegistry.rewriteCompoundBackground(command);
    }

    /**
     * 执行命令RequiresPipeStdin相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令Requires Pipe Stdin结果。
     */
    public boolean commandRequiresPipeStdin(String command) {
        String normalized = StrUtil.nullToEmpty(command).trim().toLowerCase(java.util.Locale.ROOT);
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized.startsWith("gh auth login") && normalized.contains("--with-token");
    }

    /**
     * 执行ptyDisabledNote相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param pty 是否使用伪终端。。
     * @return 返回pty Disabled Note结果。
     */
    public String ptyDisabledNote(String command, Boolean pty) {
        if (!Boolean.TRUE.equals(pty) || !commandRequiresPipeStdin(command)) {
            return null;
        }
        return "PTY disabled for this command because it expects piped stdin/EOF "
                + "(for example gh auth login --with-token). For local background "
                + "processes, call process(action='close') after writing so it receives EOF.";
    }

    /**
     * 执行interpret退出Code相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param exitCode 命令退出码。
     * @return 返回interpret 退出码结果。
     */
    public String interpretExitCode(String command, Integer exitCode) {
        return TerminalExitCodeSemantics.interpret(command, exitCode);
    }

    /**
     * 执行转换sudo命令相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回transform Sudo命令结果。
     */
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

    /**
     * 执行sudoRewrite策略摘要相关逻辑。
     *
     * @return 返回sudo Rewrite策略Summary结果。
     */
    public Map<String, Object> sudoRewritePolicySummary() {
        return sudoRewritePolicySummary(resolveSudoPassword() != null);
    }

    /**
     * 执行sudoRewrite策略摘要相关逻辑。
     *
     * @param sudoPasswordConfigured sudoPassword已配置对象。
     * @return 返回sudo Rewrite策略Summary结果。
     */
    public static Map<String, Object> sudoRewritePolicySummary(boolean sudoPasswordConfigured) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("configured", Boolean.valueOf(sudoPasswordConfigured));
        summary.put("envKey", "SOLONCLAW_SUDO_PASSWORD");
        summary.put("configKey", "solonclaw.terminal.sudoPassword");
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
        summary.put(
                "description",
                "Configured sudo commands are rewritten to use sudo -S -p '' with the password sent through stdin; secrets are never embedded in the visible command.");
        return summary;
    }

    /**
     * 执行终端输出策略摘要相关逻辑。
     *
     * @return 返回终端输出策略Summary结果。
     */
    public Map<String, Object> terminalOutputPolicySummary() {
        return terminalOutputPolicySummary(appConfig);
    }

    /**
     * 执行终端输出策略摘要相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回终端输出策略Summary结果。
     */
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
        summary.put("exitCodeMeaningReturned", Boolean.TRUE);
        summary.put("executeShellExitMeaningNotice", Boolean.TRUE);
        summary.put("exitCodeSemantics", TerminalExitCodeSemantics.policySummary());
        summary.put("foregroundRetryErrorsInterpreted", Boolean.TRUE);
        summary.put(
                "description",
                "Terminal output is ANSI-stripped, secret-redacted, bounded with a head/tail truncation notice, and enriched with timeout, sudo, and exit-code guidance before it is returned.");
        return summary;
    }

    /**
     * 执行rewriteRealsudoInvocations相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回rewrite Real Sudo Invocations结果。
     */
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

    /**
     * 读取Shell token。
     *
     * @param command 待执行或解析的命令文本。
     * @param start start 参数。
     * @return 返回读取到的Shell token。
     */
    private Token readShellToken(String command, int start) {
        int i = start;
        int n = command.length();
        while (i < n) {
            char ch = command.charAt(i);
            if (Character.isWhitespace(ch)
                    || ch == ';'
                    || ch == '|'
                    || ch == '&'
                    || ch == '('
                    || ch == ')') {
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

    /**
     * 判断是否存在Sudo Stdin Flag。
     *
     * @param command 待执行或解析的命令文本。
     * @param index 索引参数。
     * @return 如果Sudo Stdin Flag满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否以Any开头。
     *
     * @param value 待规范化或校验的原始值。
     * @param index 索引参数。
     * @param first first 参数。
     * @param second second 参数。
     * @param third third 参数。
     * @return 返回starts With Any结果。
     */
    private boolean startsWithAny(
            String value, int index, String first, String second, String third) {
        return value.startsWith(first, index)
                || value.startsWith(second, index)
                || value.startsWith(third, index);
    }

    /**
     * 判断是否具有环境变量Assignment特征。
     *
     * @param token token 参数。
     * @return 返回looks Like Env Assignment结果。
     */
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

    /**
     * 解析Sudo密码。
     *
     * @return 返回解析后的Sudo密码。
     */
    private String resolveSudoPassword() {
        String envValue = System.getenv("SOLONCLAW_SUDO_PASSWORD");
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

    /**
     * 执行With Stdin。
     *
     * @param code code 参数。
     * @param stdin stdin 参数。
     * @param timeoutMs timeoutMs 参数。
     * @return 返回With Stdin结果。
     */
    private String executeWithStdin(String code, String stdin, Integer timeoutMs) {
        File dir = resolveForegroundWorkdir(null);
        ForegroundResult result = executeForeground(code, stdin, timeoutMs, dir);
        updateLiveWorkDir(result.getCwd());
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
                return transformTerminalOutput(
                        code, output, result.getExitCode(), result.getError());
            }
            output = result.getError();
            output = appendExitCodeMeaningNotice(code, output, result.getExitCode());
            return transformTerminalOutput(code, output, result.getExitCode(), result.getError());
        }
        output = StrUtil.nullToEmpty(result.getOutput()).trim();
        output = output.length() == 0 ? "执行成功" : output;
        output = appendExitCodeMeaningNotice(code, output, result.getExitCode());
        return transformTerminalOutput(code, output, result.getExitCode(), result.getError());
    }

    /**
     * 根据命令退出码追加可读说明。
     *
     * @param command 待执行或解析的命令文本。
     * @param output 命令执行输出文本。
     * @param exitCode 命令退出码。
     * @return 返回退出码 Meaning Notice结果。
     */
    private String appendExitCodeMeaningNotice(String command, String output, Integer exitCode) {
        String meaning = TerminalExitCodeSemantics.interpret(command, exitCode);
        if (StrUtil.isBlank(meaning)) {
            return output;
        }
        String notice = "退出码说明：" + meaning;
        String value = StrUtil.nullToEmpty(output);
        if (value.contains(notice)) {
            return value;
        }
        return value.length() == 0 ? notice : value + "\n" + notice;
    }

    /**
     * 转换终端输出为工具结果可展示文本。
     *
     * @param command 待执行或解析的命令文本。
     * @param output 命令执行输出文本。
     * @param exitCode 命令退出码。
     * @param error 错误参数。
     * @return 返回transform终端输出结果。
     */
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

    /**
     * 追加Sudo Failure Hint。
     *
     * @param output 命令执行输出文本。
     * @return 返回Sudo Failure Hint结果。
     */
    private String appendSudoFailureHint(String output) {
        String value = StrUtil.nullToEmpty(output);
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (!isSudoPasswordFailure(normalized)) {
            return value;
        }
        String hint =
                "提示：sudo 需要密码或交互式终端。"
                        + "如需在消息渠道或后台任务中使用 sudo，请在运行环境设置 SOLONCLAW_SUDO_PASSWORD，"
                        + "或在 dashboard 安全配置中设置 solonclaw.terminal.sudoPassword。";
        if (value.contains(hint)) {
            return value;
        }
        return value.length() == 0 ? hint : value + "\n\n" + hint;
    }

    /**
     * 判断是否Sudo密码Failure。
     *
     * @param normalizedOutput normalized输出参数。
     * @return 如果Sudo密码Failure满足条件则返回 true，否则返回 false。
     */
    private boolean isSudoPasswordFailure(String normalizedOutput) {
        return normalizedOutput.contains("sudo: a password is required")
                || normalizedOutput.contains("sudo: no tty present")
                || normalizedOutput.contains("sudo: a terminal is required")
                || normalizedOutput.contains("sudo: no password was provided")
                || normalizedOutput.contains("sudo: a password is required to run sudo");
    }

    /**
     * 执行输出WithTimeoutNotice相关逻辑。
     *
     * @param result 结果响应或执行结果。
     * @return 返回输出With Timeout Notice结果。
     */
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

    /**
     * 执行输出WithNotice相关逻辑。
     *
     * @param outputText 输出文本参数。
     * @param notice notice 参数。
     * @return 返回输出With Notice结果。
     */
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

    /**
     * 执行Foreground With Retry。
     *
     * @param code code 参数。
     * @param stdin stdin 参数。
     * @param timeoutMs timeoutMs 参数。
     * @param directory 文件或目录路径参数。
     * @return 返回Foreground With Retry结果。
     */
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

    /**
     * 判断是否需要Retry Foreground Execution。
     *
     * @param result 结果响应或执行结果。
     * @return 如果Retry Foreground Execution满足条件则返回 true，否则返回 false。
     */
    private boolean shouldRetryForegroundExecution(ForegroundResult result) {
        if (result == null || result.isTimedOut()) {
            return false;
        }
        return result.getExitCode() != null
                && result.getExitCode().intValue() == -1
                && StrUtil.isNotBlank(result.getError());
    }

    /**
     * 解析Foreground Max Retries。
     *
     * @return 返回解析后的Foreground Max Retries。
     */
    private int resolveForegroundMaxRetries() {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return 3;
        }
        return Math.max(0, appConfig.getTerminal().getForegroundMaxRetries());
    }

    /**
     * 解析Foreground Retry Base Delay Seconds。
     *
     * @return 返回解析后的Foreground Retry Base Delay Seconds。
     */
    private int resolveForegroundRetryBaseDelaySeconds() {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return 2;
        }
        return Math.max(0, appConfig.getTerminal().getForegroundRetryBaseDelaySeconds());
    }

    /**
     * 执行sleepBefore重试相关逻辑。
     *
     * @param baseDelaySeconds 基础DelaySeconds参数。
     * @param retryAttempt 重试Attempt参数。
     */
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

    /**
     * 执行prepend终端Init相关逻辑。
     *
     * @param code code 参数。
     * @return 返回prepend Shell Init结果。
     */
    String prependShellInit(String code) {
        List<String> files = resolveShellInitFiles();
        return prependShellInit(code, files, isWindows());
    }

    /**
     * 执行prepend终端Init相关逻辑。
     *
     * @param code code 参数。
     * @param files 文件或目录路径参数。
     * @param windows Windows参数。
     * @return 返回prepend Shell Init结果。
     */
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

    /**
     * 解析Shell Init Files。
     *
     * @return 返回解析后的Shell Init Files。
     */
    List<String> resolveShellInitFiles() {
        List<String> configured = Collections.emptyList();
        boolean autoSource = true;
        if (appConfig != null
                && appConfig.getTerminal() != null
                && appConfig.getTerminal().getShellInitFiles() != null) {
            configured = appConfig.getTerminal().getShellInitFiles();
            autoSource = appConfig.getTerminal().isAutoSourceBashrc();
        }
        String home =
                StrUtil.blankToDefault(System.getenv("HOME"), System.getProperty("user.home"));
        return resolveShellInitFiles(
                configured,
                autoSource,
                isWindows(),
                home,
                System.getenv(),
                effectiveSecurityPolicyService());
    }

    /**
     * 解析Shell Init Files。
     *
     * @param configured 已配置对象。
     * @param autoSourceBashrc auto来源Bashrc参数。
     * @param windows Windows参数。
     * @param home 主渠道参数。
     * @param env 环境变量参数。
     * @return 返回解析后的Shell Init Files。
     */
    public static List<String> resolveShellInitFiles(
            List<String> configured,
            boolean autoSourceBashrc,
            boolean windows,
            String home,
            Map<String, String> env) {
        return resolveShellInitFiles(configured, autoSourceBashrc, windows, home, env, null);
    }

    /**
     * 解析Shell Init Files。
     *
     * @param configured 已配置对象。
     * @param autoSourceBashrc auto来源Bashrc参数。
     * @param windows Windows参数。
     * @param home 主渠道参数。
     * @param env 环境变量参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回解析后的Shell Init Files。
     */
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

    /**
     * 判断是否Safe Configured Shell Init。
     *
     * @param path 文件或目录路径。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 如果Safe Configured Shell Init满足条件则返回 true，否则返回 false。
     */
    private static boolean isSafeConfiguredShellInit(
            String path, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService == null) {
            return true;
        }
        SecurityPolicyService.FileVerdict verdict = securityPolicyService.checkPath(path, false);
        return verdict.isAllowed();
    }

    /**
     * 执行生效安全策略服务相关逻辑。
     *
     * @return 返回生效安全策略服务结果。
     */
    private SecurityPolicyService effectiveSecurityPolicyService() {
        if (securityPolicyService != null) {
            return securityPolicyService;
        }
        if (appConfig == null) {
            return null;
        }
        return new SecurityPolicyService(appConfig);
    }

    /**
     * 执行expand终端Init路径相关逻辑。
     *
     * @param raw 原始输入值。
     * @param home 主渠道参数。
     * @param env 环境变量参数。
     * @return 返回expand Shell Init路径。
     */
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

    /**
     * 执行Foreground。
     *
     * @param code code 参数。
     * @param stdin stdin 参数。
     * @param timeoutMs timeoutMs 参数。
     * @param directory 文件或目录路径参数。
     * @return 返回Foreground结果。
     */
    private ForegroundResult executeForeground(
            String code, String stdin, Integer timeoutMs, File directory) {
        Path tempScript = null;
        try {
            File safeDirectory =
                    directory == null ? resolveSafeCwd(workPath.toString()) : directory;
            tempScript = Files.createTempFile(safeDirectory.toPath(), "_script_", extension);
            writeShellScript(tempScript, code);
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
            ParsedForegroundOutput parsed = parseForegroundOutput(bufferedOutput(outputBuffer));
            return new ForegroundResult(
                    parsed.output,
                    Integer.valueOf(process.exitValue()),
                    null,
                    false,
                    0,
                    parsed.cwd);
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

    /**
     * 规范化Foreground Timeout。
     *
     * @param timeoutMs timeoutMs 参数。
     * @return 返回Foreground Timeout结果。
     */
    private Integer normalizeForegroundTimeout(Integer timeoutMs) {
        return normalizeForegroundTimeout(timeoutMs, true);
    }

    /**
     * 规范化Foreground Timeout。
     *
     * @param timeoutMs timeoutMs 参数。
     * @param explicitTimeout explicitTimeout 参数。
     * @return 返回Foreground Timeout结果。
     */
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

    /**
     * 执行前台进程TimeoutExceeded消息相关逻辑。
     *
     * @param timeoutMs timeoutMs 参数。
     * @return 返回foreground Timeout Exceeded消息结果。
     */
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

    /**
     * 解析Background Workdir。
     *
     * @param workdir 命令执行工作目录。
     * @return 返回解析后的Background Workdir。
     */
    private File resolveBackgroundWorkdir(String workdir) {
        if (StrUtil.isBlank(workdir)) {
            return liveOrDefaultWorkDir();
        }
        String value = workdir;
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(value);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Blocked: "
                            + verdict.getMessage()
                            + ". Use a simple filesystem path without shell metacharacters.");
        }
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict pathVerdict =
                    securityPolicyService.checkPath(value, false);
            if (!pathVerdict.isAllowed()) {
                throw new IllegalArgumentException(
                        "Blocked: workdir path is not allowed by path safety policy: "
                                + pathVerdict.getMessage());
            }
        }
        return resolveSafeCwd(value, workPath.toFile());
    }

    /**
     * 解析Foreground Workdir。
     *
     * @param workdir 命令执行工作目录。
     * @return 返回解析后的Foreground Workdir。
     */
    private File resolveForegroundWorkdir(String workdir) {
        return resolveBackgroundWorkdir(workdir);
    }

    /**
     * 执行liveOr默认工作目录相关逻辑。
     *
     * @return 返回live Or默认Work Dir结果。
     */
    private File liveOrDefaultWorkDir() {
        File live = liveWorkDir;
        if (live != null && isAllowedWorkDir(live) && live.isDirectory()) {
            return live;
        }
        return defaultWorkDir;
    }

    /**
     * 更新Live Work Dir。
     *
     * @param cwd 工作目录参数。
     */
    private void updateLiveWorkDir(String cwd) {
        if (StrUtil.isBlank(cwd)) {
            return;
        }
        File dir = resolveSafeCwd(cwd, liveOrDefaultWorkDir());
        if (isAllowedWorkDir(dir) && dir.isDirectory()) {
            liveWorkDir = dir;
        }
    }

    /**
     * 判断是否Allowed Work Dir。
     *
     * @param dir 文件或目录路径参数。
     * @return 如果Allowed Work Dir满足条件则返回 true，否则返回 false。
     */
    private boolean isAllowedWorkDir(File dir) {
        if (dir == null) {
            return false;
        }
        String value = dir.getAbsolutePath();
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(value);
        if (!verdict.isAllowed()) {
            return false;
        }
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict pathVerdict =
                    securityPolicyService.checkPath(value, false);
            return pathVerdict.isAllowed();
        }
        return true;
    }

    /**
     * 写入Shell Script。
     *
     * @param tempScript tempScript 参数。
     * @param code code 参数。
     */
    private void writeShellScript(Path tempScript, String code) throws Exception {
        String script = prependShellInit(code);
        if (isWindows()) {
            Files.write(tempScript, script.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(tempScript, StandardCharsets.UTF_8)) {
            writer.write(script);
            writer.newLine();
            writer.write("__solon_claw_status=$?");
            writer.newLine();
            writer.write("printf '\\n" + CWD_MARKER_PREFIX + "%s\\n' \"$(pwd -P)\"");
            writer.newLine();
            writer.write("exit $__solon_claw_status");
            writer.newLine();
        }
    }

    /**
     * 解析Foreground输出。
     *
     * @param output 命令执行输出文本。
     * @return 返回解析后的Foreground输出。
     */
    private ParsedForegroundOutput parseForegroundOutput(String output) {
        String value = StrUtil.nullToEmpty(output);
        int marker = value.lastIndexOf(CWD_MARKER_PREFIX);
        if (marker < 0) {
            return new ParsedForegroundOutput(value, null);
        }
        int cwdStart = marker + CWD_MARKER_PREFIX.length();
        int cwdEnd = value.indexOf('\n', cwdStart);
        if (cwdEnd < 0) {
            cwdEnd = value.length();
        }
        String cwd = value.substring(cwdStart, cwdEnd).trim();
        String userOutput = value.substring(0, marker);
        if (userOutput.endsWith("\r\n")) {
            userOutput = userOutput.substring(0, userOutput.length() - 2);
        } else if (userOutput.endsWith("\n")) {
            userOutput = userOutput.substring(0, userOutput.length() - 1);
        }
        return new ParsedForegroundOutput(userOutput, cwd);
    }

    /**
     * 解析Safe Cwd。
     *
     * @param cwd 工作目录参数。
     * @return 返回解析后的Safe Cwd。
     */
    public static File resolveSafeCwd(String cwd) {
        return resolveSafeCwd(cwd, new File(System.getProperty("java.io.tmpdir")));
    }

    /**
     * 解析Safe Cwd。
     *
     * @param cwd 工作目录参数。
     * @param fallback 兜底参数。
     * @return 返回解析后的Safe Cwd。
     */
    public static File resolveSafeCwd(String cwd, File fallback) {
        return TerminalPathSupport.resolveSafeCwd(cwd, fallback);
    }

    /**
     * 读取输出。
     *
     * @param process 进程参数。
     * @return 返回读取到的输出。
     */
    private String readOutput(Process process) throws Exception {
        StringBuilder buffer = new StringBuilder();
        readOutput(process, buffer);
        return bufferedOutput(buffer);
    }

    /**
     * 读取输出。
     *
     * @param process 进程参数。
     * @param buffer buffer 参数。
     */
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

    /**
     * 追加输出。
     *
     * @param buffer buffer 参数。
     * @param chars chars 参数。
     * @param length length 参数。
     */
    private void appendOutput(StringBuilder buffer, char[] chars, int length) {
        synchronized (buffer) {
            buffer.append(chars, 0, length);
        }
    }

    /**
     * 追加输出。
     *
     * @param buffer buffer 参数。
     * @param text 待处理文本。
     */
    private void appendOutput(StringBuilder buffer, String text) {
        synchronized (buffer) {
            buffer.append(StrUtil.nullToEmpty(text));
        }
    }

    /**
     * 执行bufferedLength相关逻辑。
     *
     * @param buffer buffer 参数。
     * @return 返回buffered Length结果。
     */
    private int bufferedLength(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.length();
        }
    }

    /**
     * 执行buffered输出相关逻辑。
     *
     * @param buffer buffer 参数。
     * @return 返回buffered输出结果。
     */
    private String bufferedOutput(StringBuilder buffer) {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    /**
     * 规范化终端输出。
     *
     * @param output 命令执行输出文本。
     * @return 返回终端输出结果。
     */
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
            value =
                    value.substring(0, headChars)
                            + notice
                            + value.substring(value.length() - tailChars);
        }
        return SecretRedactor.redact(TerminalAnsiSanitizer.stripAnsi(value));
    }

    /**
     * 解析Max输出Chars。
     *
     * @return 返回解析后的Max输出Chars。
     */
    private int resolveMaxOutputChars() {
        return resolveToolOutputInlineLimit(appConfig);
    }

    /**
     * 解析工具输出Inline限制。
     *
     * @param appConfig 应用运行配置。
     * @return 返回解析后的工具输出Inline限制。
     */
    private static int resolveToolOutputInlineLimit(AppConfig appConfig) {
        int value = 50000;
        if (appConfig != null && appConfig.getTask() != null) {
            value = appConfig.getTask().getToolOutputInlineLimit();
        }
        return Math.max(256, value);
    }

    /**
     * 执行默认终端命令相关逻辑。
     *
     * @return 返回默认Shell 命令结果。
     */
    private static String defaultShellCmd() {
        return isWindows() ? "cmd /c" : (checkCmd("bash") ? "bash" : "/bin/sh");
    }

    /**
     * 执行默认扩展名相关逻辑。
     *
     * @return 返回默认Extension结果。
     */
    private static String defaultExtension() {
        return isWindows() ? ".bat" : ".sh";
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 检查命令。
     *
     * @param cmd cmd 参数。
     * @return 返回命令结果。
     */
    private static boolean checkCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd + " --version");
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行checked工作目录相关逻辑。
     *
     * @param workDir 命令执行工作目录。
     * @return 返回checked Work Dir结果。
     */
    private static String checkedWorkDir(String workDir) {
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(workDir);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Blocked: "
                            + verdict.getMessage()
                            + ". Use a simple filesystem path without shell metacharacters.");
        }
        return workDir;
    }

    /** 承载sudo转换相关状态和辅助逻辑。 */
    public static class SudoTransform {
        /** 记录sudo转换中的命令。 */
        private final String command;

        /** 记录sudo转换中的stdin。 */
        private final String stdin;

        /** 是否启用changed。 */
        private final boolean changed;

        /**
         * 创建Sudo Transform实例，并注入运行所需依赖。
         *
         * @param command 待执行或解析的命令文本。
         * @param stdin stdin 参数。
         * @param changed changed 参数。
         */
        private SudoTransform(String command, String stdin, boolean changed) {
            this.command = command;
            this.stdin = stdin;
            this.changed = changed;
        }

        /**
         * 执行unchanged相关逻辑。
         *
         * @param command 待执行或解析的命令文本。
         * @return 返回unchanged结果。
         */
        private static SudoTransform unchanged(String command) {
            return new SudoTransform(command, null, false);
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
         * 读取Stdin。
         *
         * @return 返回读取到的Stdin。
         */
        public String getStdin() {
            return stdin;
        }

        /**
         * 判断是否Changed。
         *
         * @return 如果Changed满足条件则返回 true，否则返回 false。
         */
        public boolean isChanged() {
            return changed;
        }
    }

    /** 定义终端输出Transformer的抽象契约，供不同运行时实现保持一致行为。 */
    public interface TerminalOutputTransformer {
        /**
         * 执行转换相关逻辑。
         *
         * @param context 当前请求或运行上下文。
         * @return 返回transform结果。
         */
        String transform(TerminalOutputContext context) throws Exception;
    }

    /** 承载终端输出上下文相关状态和辅助逻辑。 */
    public static class TerminalOutputContext {
        /** 记录终端输出上下文中的命令。 */
        private final String command;

        /** 记录终端输出上下文中的输出。 */
        private final String output;

        /** 记录终端输出上下文中的退出Code。 */
        private final Integer exitCode;

        /** 记录终端输出上下文中的错误。 */
        private final String error;

        /**
         * 创建终端输出上下文实例，并注入运行所需依赖。
         *
         * @param command 待执行或解析的命令文本。
         * @param output 命令执行输出文本。
         * @param exitCode 命令退出码。
         * @param error 错误参数。
         */
        private TerminalOutputContext(
                String command, String output, Integer exitCode, String error) {
            this.command = command;
            this.output = output;
            this.exitCode = exitCode;
            this.error = error;
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
         * 读取输出。
         *
         * @return 返回读取到的输出。
         */
        public String getOutput() {
            return output;
        }

        /**
         * 读取退出码。
         *
         * @return 返回读取到的退出码。
         */
        public Integer getExitCode() {
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
    }

    /** 承载sudoRewrite相关状态和辅助逻辑。 */
    private static class SudoRewrite {
        /** 记录sudoRewrite中的命令。 */
        private final String command;

        /** 是否启用changed。 */
        private final boolean changed;

        /**
         * 创建Sudo Rewrite实例，并注入运行所需依赖。
         *
         * @param command 待执行或解析的命令文本。
         * @param changed changed 参数。
         */
        private SudoRewrite(String command, boolean changed) {
            this.command = command;
            this.changed = changed;
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
         * 判断是否Changed。
         *
         * @return 如果Changed满足条件则返回 true，否则返回 false。
         */
        public boolean isChanged() {
            return changed;
        }
    }

    /** 承载token相关状态和辅助逻辑。 */
    private static class Token {
        /** 记录token中的值。 */
        private final String value;

        /** 记录token中的end。 */
        private final int end;

        /**
         * 创建token实例，并注入运行所需依赖。
         *
         * @param value 待规范化或校验的原始值。
         * @param end end 参数。
         */
        private Token(String value, int end) {
            this.value = value;
            this.end = end;
        }

        /**
         * 读取Value。
         *
         * @return 返回读取到的Value。
         */
        public String getValue() {
            return value;
        }

        /**
         * 读取End。
         *
         * @return 返回读取到的End。
         */
        public int getEnd() {
            return end;
        }
    }

    /** 表示前台进程结果，携带调用方后续判断所需信息。 */
    private static class ForegroundResult {
        /** 记录前台进程中的输出。 */
        private final String output;

        /** 记录前台进程中的退出Code。 */
        private final Integer exitCode;

        /** 记录前台进程中的错误。 */
        private final String error;

        /** 是否启用timedOut。 */
        private final boolean timedOut;

        /** 记录前台进程中的重试次数。 */
        private final int retryCount;

        /** 记录前台进程中的工作目录。 */
        private final String cwd;

        /**
         * 创建Foreground结果实例，并注入运行所需依赖。
         *
         * @param output 命令执行输出文本。
         * @param exitCode 命令退出码。
         * @param error 错误参数。
         */
        private ForegroundResult(String output, Integer exitCode, String error) {
            this(output, exitCode, error, false, 0);
        }

        /**
         * 创建Foreground结果实例，并注入运行所需依赖。
         *
         * @param output 命令执行输出文本。
         * @param exitCode 命令退出码。
         * @param error 错误参数。
         * @param timedOut timedOut 参数。
         */
        private ForegroundResult(String output, Integer exitCode, String error, boolean timedOut) {
            this(output, exitCode, error, timedOut, 0);
        }

        /**
         * 创建Foreground结果实例，并注入运行所需依赖。
         *
         * @param output 命令执行输出文本。
         * @param exitCode 命令退出码。
         * @param error 错误参数。
         * @param timedOut timedOut 参数。
         * @param retryCount 重试Count参数。
         */
        private ForegroundResult(
                String output, Integer exitCode, String error, boolean timedOut, int retryCount) {
            this(output, exitCode, error, timedOut, retryCount, null);
        }

        /**
         * 创建Foreground结果实例，并注入运行所需依赖。
         *
         * @param output 命令执行输出文本。
         * @param exitCode 命令退出码。
         * @param error 错误参数。
         * @param timedOut timedOut 参数。
         * @param retryCount 重试Count参数。
         * @param cwd 工作目录参数。
         */
        private ForegroundResult(
                String output,
                Integer exitCode,
                String error,
                boolean timedOut,
                int retryCount,
                String cwd) {
            this.output = output;
            this.exitCode = exitCode;
            this.error = error;
            this.timedOut = timedOut;
            this.retryCount = retryCount;
            this.cwd = cwd;
        }

        /**
         * 读取输出。
         *
         * @return 返回读取到的输出。
         */
        public String getOutput() {
            return output;
        }

        /**
         * 读取退出码。
         *
         * @return 返回读取到的退出码。
         */
        public Integer getExitCode() {
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
         * 判断是否Timed Out。
         *
         * @return 如果Timed Out满足条件则返回 true，否则返回 false。
         */
        public boolean isTimedOut() {
            return timedOut;
        }

        /**
         * 读取Retry次数。
         *
         * @return 返回读取到的Retry次数。
         */
        public int getRetryCount() {
            return retryCount;
        }

        /**
         * 读取Cwd。
         *
         * @return 返回读取到的Cwd。
         */
        public String getCwd() {
            return cwd;
        }

        /**
         * 执行with重试次数相关逻辑。
         *
         * @param retryCount 重试Count参数。
         * @return 返回with Retry次数结果。
         */
        public ForegroundResult withRetryCount(int retryCount) {
            return new ForegroundResult(
                    output, exitCode, error, timedOut, Math.max(0, retryCount), cwd);
        }
    }

    /** 承载Parsed前台进程输出相关状态和辅助逻辑。 */
    private static class ParsedForegroundOutput {
        /** 记录Parsed前台进程输出中的输出。 */
        private final String output;

        /** 记录Parsed前台进程输出中的工作目录。 */
        private final String cwd;

        /**
         * 创建Parsed Foreground输出实例，并注入运行所需依赖。
         *
         * @param output 命令执行输出文本。
         * @param cwd 工作目录参数。
         */
        private ParsedForegroundOutput(String output, String cwd) {
            this.output = output;
            this.cwd = cwd;
        }
    }
}
