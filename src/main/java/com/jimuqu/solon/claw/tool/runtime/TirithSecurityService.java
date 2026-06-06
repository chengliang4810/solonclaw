package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;

/** Tirith 命令安全扫描适配。 */
public class TirithSecurityService {
    /** 最大FINDINGS的统一常量值。 */
    private static final int MAX_FINDINGS = 50;

    /** 最大摘要LENGTH的统一常量值。 */
    private static final int MAX_SUMMARY_LENGTH = 500;

    /** 最大审计RULESAMPLES的统一常量值。 */
    private static final int MAX_AUDIT_RULE_SAMPLES = 5;

    /** 注入应用配置，用于Tirith安全。 */
    private final AppConfig appConfig;

    /** 记录Tirith安全中的最近一次审计摘要。 */
    private volatile AuditSummary lastAuditSummary;

    /**
     * 创建Tirith安全服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public TirithSecurityService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 检查命令安全。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令安全结果。
     */
    public ScanResult checkCommandSecurity(String command) {
        return checkCommandSecurity(command, "posix");
    }

    /**
     * 检查命令安全。
     *
     * @param command 待执行或解析的命令文本。
     * @param shell 终端参数。
     * @return 返回命令安全结果。
     */
    private ScanResult checkCommandSecurity(String command, String shell) {
        AppConfig.SecurityConfig security = appConfig == null ? null : appConfig.getSecurity();
        if (security != null && !security.isTirithEnabled()) {
            return recordAndReturn(command, shell, ScanResult.allow());
        }

        String path =
                security == null
                        ? "tirith"
                        : StrUtil.blankToDefault(security.getTirithPath(), "tirith").trim();
        int timeoutSeconds = security == null ? 5 : Math.max(1, security.getTirithTimeoutSeconds());
        boolean failOpen = security == null || security.isTirithFailOpen();
        String resolvedPath = resolvePath(path);
        if (StrUtil.isBlank(resolvedPath)) {
            return recordAndReturn(
                    command,
                    shell,
                    operationalFailure(
                            failOpen,
                            "tirith path unavailable",
                            "tirith path unavailable (fail-closed)"));
        }

        Process process = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(
                            resolvedPath,
                            "check",
                            "--json",
                            "--non-interactive",
                            "--shell",
                            normalizeShell(shell),
                            "--",
                            StrUtil.nullToEmpty(command));
            SubprocessEnvironmentSanitizer.sanitize(builder.environment(), appConfig);
            process = builder.start();
            final Process started = process;
            Future<String> stdout =
                    executor.submit(
                            new Callable<String>() {
                                /**
                                 * 执行回调调用并返回结果。
                                 *
                                 * @return 返回call结果。
                                 */
                                @Override
                                public String call() throws Exception {
                                    return readUtf8(started.getInputStream());
                                }
                            });
            Future<String> stderr =
                    executor.submit(
                            new Callable<String>() {
                                /**
                                 * 执行回调调用并返回结果。
                                 *
                                 * @return 返回call结果。
                                 */
                                @Override
                                public String call() throws Exception {
                                    return readUtf8(started.getErrorStream());
                                }
                            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return recordAndReturn(
                        command,
                        shell,
                        operationalFailure(
                                failOpen,
                                "tirith timed out (" + timeoutSeconds + "s)",
                                "tirith timed out (fail-closed)"));
            }

            int exitCode = process.exitValue();
            String out = getQuietly(stdout);
            String err = getQuietly(stderr);
            String action;
            if (exitCode == 0) {
                action = "allow";
            } else if (exitCode == 1) {
                action = "block";
            } else if (exitCode == 2) {
                action = "warn";
            } else {
                return recordAndReturn(
                        command,
                        shell,
                        operationalFailure(
                                failOpen,
                                "tirith exit code " + exitCode + " (fail-open)",
                                "tirith exit code " + exitCode + " (fail-closed)"));
            }

            ParsedOutput parsed = parseOutput(out, action);
            if (StrUtil.isBlank(parsed.summary)
                    && StrUtil.isNotBlank(err)
                    && !"allow".equals(action)) {
                parsed.summary = safeText(err.trim(), MAX_SUMMARY_LENGTH);
            }
            return recordAndReturn(
                    command, shell, new ScanResult(action, parsed.findings, parsed.summary));
        } catch (Exception e) {
            String message = safeMessage(e);
            return recordAndReturn(
                    command,
                    shell,
                    operationalFailure(
                            failOpen,
                            "tirith unavailable: " + message,
                            "tirith spawn failed (fail-closed): " + message));
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                    // 当前分支无需额外处理。
                }
            }
            executor.shutdownNow();
        }
    }

    /**
     * 检查命令安全For工具。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @return 返回命令安全For工具结果。
     */
    public ScanResult checkCommandSecurityForTool(String toolName, String command) {
        return checkCommandSecurity(command, shellForToolCommand(toolName, command));
    }

    /**
     * 执行diagnose相关逻辑。
     *
     * @return 返回diagnose结果。
     */
    public Diagnostic diagnose() {
        AppConfig.SecurityConfig security = appConfig == null ? null : appConfig.getSecurity();
        boolean enabled = security == null || security.isTirithEnabled();
        String configuredPath =
                security == null
                        ? "tirith"
                        : StrUtil.blankToDefault(security.getTirithPath(), "tirith").trim();
        int timeoutSeconds = security == null ? 5 : Math.max(1, security.getTirithTimeoutSeconds());
        boolean failOpen = security == null || security.isTirithFailOpen();
        String resolvedPath = resolvePath(configuredPath);
        boolean configured = StrUtil.isNotBlank(configuredPath);
        boolean available = enabled && isExecutableAvailable(resolvedPath);
        String scannerState = scannerState(enabled, configured, available);
        String failureMode = failOpen ? "fail-open" : "fail-closed";
        String failureBehavior =
                failOpen ? "allow_on_operational_failure" : "block_on_operational_failure";
        String summary;
        if (!enabled) {
            summary = "tirith security scan is disabled";
        } else if (available) {
            summary = "tirith executable is available (" + failureMode + ")";
        } else {
            summary = "tirith executable is unavailable (" + failureMode + ")";
        }
        return new Diagnostic(
                enabled,
                configured,
                configuredPath,
                resolvedPath,
                available,
                timeoutSeconds,
                failOpen,
                scannerState,
                failureMode,
                failureBehavior,
                summary);
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    public Map<String, Object> policySummary() {
        Diagnostic diagnostic = diagnose();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("enabled", Boolean.valueOf(diagnostic.isEnabled()));
        summary.put("configured", Boolean.valueOf(diagnostic.isConfigured()));
        summary.put("available", Boolean.valueOf(diagnostic.isAvailable()));
        summary.put("timeoutSeconds", Integer.valueOf(diagnostic.getTimeoutSeconds()));
        summary.put("failOpen", Boolean.valueOf(diagnostic.isFailOpen()));
        summary.put("scannerState", diagnostic.getScannerState());
        summary.put("failureMode", diagnostic.getFailureMode());
        summary.put("failureBehavior", diagnostic.getFailureBehavior());
        summary.put("diagnostic", diagnostic.toMap());
        summary.put("diagnosticSummary", diagnosticSummary(diagnostic));
        summary.put("auditSurface", auditSurfaceSummary(diagnostic));
        summary.put("lastAuditAvailable", Boolean.valueOf(lastAuditSummary != null));
        summary.put("lastAudit", lastAuditMap(diagnostic));
        summary.put("sampleAudit", sampleAuditSummary(diagnostic).toMap());
        summary.put("actions", java.util.Arrays.asList("allow", "warn", "block"));
        summary.put("warnRequiresApproval", Boolean.TRUE);
        summary.put("blockRequiresApproval", Boolean.TRUE);
        summary.put("commandPassedAsSingleArgument", Boolean.TRUE);
        summary.put("nonInteractiveMode", Boolean.TRUE);
        summary.put("jsonOutputMode", Boolean.TRUE);
        summary.put("subprocessEnvironmentSanitized", Boolean.TRUE);
        summary.put("timeoutKillsProcess", Boolean.TRUE);
        summary.put("stdoutStderrCollectedSeparately", Boolean.TRUE);
        summary.put("exitCodeZeroAllows", Boolean.TRUE);
        summary.put("exitCodeOneBlocks", Boolean.TRUE);
        summary.put("exitCodeTwoWarns", Boolean.TRUE);
        summary.put("unexpectedExitCodeUsesFailureMode", Boolean.TRUE);
        summary.put("parseFailureKeepsDecision", Boolean.TRUE);
        summary.put("toolShellDetectionApplied", Boolean.TRUE);
        summary.put("findingLimit", Integer.valueOf(MAX_FINDINGS));
        summary.put("summaryLimit", Integer.valueOf(MAX_SUMMARY_LENGTH));
        summary.put("secretRedaction", Boolean.TRUE);
        summary.put("redactedSummaryFields", redactedSummaryFields());
        summary.put("rawConfiguredPathExposed", Boolean.FALSE);
        summary.put("rawResolvedPathExposed", Boolean.FALSE);
        summary.put("rawFindingsExposed", Boolean.FALSE);
        summary.put("rawCommandExposed", Boolean.FALSE);
        summary.put("rawPathExposed", Boolean.FALSE);
        summary.put("lastAuditRedacted", Boolean.TRUE);
        summary.put("sampleAuditRedacted", Boolean.TRUE);
        summary.put("shellDetection", java.util.Arrays.asList("posix", "powershell", "cmd"));
        summary.put("failOpenMode", diagnostic.getFailureBehavior());
        summary.put(
                "description",
                "Tirith scans command text through an external checker, maps warn/block findings into approval-required security results, and redacts diagnostics before exposing them.");
        return summary;
    }

    /**
     * 执行scanner状态相关逻辑。
     *
     * @param enabled 启用状态开关值。
     * @param configured 已配置对象。
     * @param available available 参数。
     * @return 返回scanner状态。
     */
    private String scannerState(boolean enabled, boolean configured, boolean available) {
        if (!enabled) {
            return "disabled";
        }
        if (available) {
            return "available";
        }
        return configured ? "configured_unavailable" : "unconfigured";
    }

    /**
     * 执行redacted摘要Fields相关逻辑。
     *
     * @return 返回redacted Summary Fields结果。
     */
    private List<String> redactedSummaryFields() {
        return java.util.Arrays.asList(
                "diagnostic.configuredPath",
                "diagnostic.resolvedPath",
                "diagnostic.summary",
                "scan.summary",
                "lastAudit.summaryPreview",
                "lastAudit.findingRuleSamples",
                "sampleAudit.summaryPreview",
                "finding.ruleId",
                "finding.severity",
                "finding.title",
                "finding.description");
    }

    /**
     * 执行诊断摘要相关逻辑。
     *
     * @param diagnostic 诊断参数。
     * @return 返回诊断Summary结果。
     */
    private Map<String, Object> diagnosticSummary(Diagnostic diagnostic) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("scannerConfigured", Boolean.valueOf(diagnostic.isConfigured()));
        map.put("scannerAvailable", Boolean.valueOf(diagnostic.isAvailable()));
        map.put("scannerState", diagnostic.getScannerState());
        map.put("failureMode", diagnostic.getFailureMode());
        map.put("failureBehavior", diagnostic.getFailureBehavior());
        map.put("timeoutSeconds", Integer.valueOf(diagnostic.getTimeoutSeconds()));
        map.put("redactionApplied", Boolean.TRUE);
        map.put("rawConfiguredPathExposed", Boolean.FALSE);
        map.put("rawResolvedPathExposed", Boolean.FALSE);
        map.put("rawCommandExposed", Boolean.FALSE);
        map.put("rawPathExposed", Boolean.FALSE);
        map.put("summary", diagnostic.getSummary());
        return map;
    }

    /**
     * 执行审计Surface摘要相关逻辑。
     *
     * @param diagnostic 诊断参数。
     * @return 返回审计Surface Summary结果。
     */
    private Map<String, Object> auditSurfaceSummary(Diagnostic diagnostic) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("surface", "tirith_command_scan");
        map.put("scannerConfigured", Boolean.valueOf(diagnostic.isConfigured()));
        map.put("scannerAvailable", Boolean.valueOf(diagnostic.isAvailable()));
        map.put("failureMode", diagnostic.getFailureMode());
        map.put("timeoutSeconds", Integer.valueOf(diagnostic.getTimeoutSeconds()));
        map.put("lastAuditAvailable", Boolean.valueOf(lastAuditSummary != null));
        map.put("sampleAuditAvailable", Boolean.TRUE);
        map.put("rawCommandExposed", Boolean.FALSE);
        map.put("rawPathExposed", Boolean.FALSE);
        map.put("rawFindingsExposed", Boolean.FALSE);
        map.put("redactionApplied", Boolean.TRUE);
        return map;
    }

    /**
     * 执行last审计映射相关逻辑。
     *
     * @param diagnostic 诊断参数。
     * @return 返回last审计Map结果。
     */
    private Map<String, Object> lastAuditMap(Diagnostic diagnostic) {
        AuditSummary last = lastAuditSummary;
        return (last == null ? emptyLastAuditSummary(diagnostic) : last).toMap();
    }

    /**
     * 执行emptyLast审计摘要相关逻辑。
     *
     * @param diagnostic 诊断参数。
     * @return 返回empty Last审计Summary结果。
     */
    private AuditSummary emptyLastAuditSummary(Diagnostic diagnostic) {
        return AuditSummary.emptyLast(diagnostic);
    }

    /**
     * 执行样例审计摘要相关逻辑。
     *
     * @param diagnostic 诊断参数。
     * @return 返回sample审计Summary结果。
     */
    private AuditSummary sampleAuditSummary(Diagnostic diagnostic) {
        return AuditSummary.sample(diagnostic);
    }

    /**
     * 记录And Return。
     *
     * @param command 待执行或解析的命令文本。
     * @param shell 终端参数。
     * @param result 结果响应或执行结果。
     * @return 返回And Return结果。
     */
    private ScanResult recordAndReturn(String command, String shell, ScanResult result) {
        try {
            lastAuditSummary =
                    AuditSummary.from(command, normalizeShell(shell), result, diagnose());
        } catch (Exception ignored) {
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
        return result;
    }

    /**
     * 执行终端For工具命令相关逻辑。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @return 返回Shell For工具命令结果。
     */
    private String shellForToolCommand(String toolName, String command) {
        String tool = StrUtil.nullToEmpty(toolName).toLowerCase(Locale.ROOT);
        String text = StrUtil.nullToEmpty(command).trim().toLowerCase(Locale.ROOT);
        if (!isTerminalTool(tool)) {
            return "posix";
        }
        if (text.startsWith("powershell ")
                || text.startsWith("powershell.exe ")
                || text.startsWith("pwsh ")
                || text.startsWith("pwsh.exe ")) {
            return "powershell";
        }
        if (text.startsWith("cmd /") || text.startsWith("cmd.exe /") || text.startsWith("cmd ")) {
            return "cmd";
        }
        return "posix";
    }

    /**
     * 判断是否终端工具。
     *
     * @param toolName 工具名称。
     * @return 如果终端工具满足条件则返回 true，否则返回 false。
     */
    private boolean isTerminalTool(String toolName) {
        return "execute_shell".equals(toolName)
                || "terminal".equals(toolName)
                || "run_terminal".equals(toolName)
                || "terminal_run".equals(toolName)
                || "terminal_exec".equals(toolName)
                || "terminal_execute".equals(toolName)
                || "executeshell".equals(toolName);
    }

    /**
     * 规范化Shell。
     *
     * @param shell 终端参数。
     * @return 返回Shell结果。
     */
    private String normalizeShell(String shell) {
        String value = StrUtil.blankToDefault(shell, "posix").trim().toLowerCase(Locale.ROOT);
        if ("powershell".equals(value) || "cmd".equals(value)) {
            return value;
        }
        return "posix";
    }

    /**
     * 执行operationalFailure相关逻辑。
     *
     * @param failOpen failOpen 参数。
     * @param failOpenSummary failOpen摘要参数。
     * @param failClosedSummary failClosed摘要参数。
     * @return 返回operational Failure结果。
     */
    private ScanResult operationalFailure(
            boolean failOpen, String failOpenSummary, String failClosedSummary) {
        if (failOpen) {
            return new ScanResult("allow", Collections.<Finding>emptyList(), failOpenSummary);
        }
        return new ScanResult("block", Collections.<Finding>emptyList(), failClosedSummary);
    }

    /**
     * 解析路径。
     *
     * @param path 文件或目录路径。
     * @return 返回解析后的路径。
     */
    private String resolvePath(String path) {
        String raw = StrUtil.blankToDefault(path, "tirith").trim();
        if (raw.startsWith("~")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        File file = new File(raw);
        if (file.isAbsolute() || raw.indexOf(File.separatorChar) >= 0 || raw.indexOf('/') >= 0) {
            return file.getAbsolutePath();
        }
        return raw;
    }

    /**
     * 判断是否Executable Available。
     *
     * @param path 文件或目录路径。
     * @return 如果Executable Available满足条件则返回 true，否则返回 false。
     */
    private boolean isExecutableAvailable(String path) {
        if (StrUtil.isBlank(path)) {
            return false;
        }
        File file = new File(path);
        if (isExplicitPath(path)) {
            return file.isFile() && file.canExecute();
        }
        List<String> names = executableNames(path);
        String pathEnv = System.getenv("PATH");
        if (StrUtil.isBlank(pathEnv)) {
            return false;
        }
        String[] dirs = pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String dir : dirs) {
            if (StrUtil.isBlank(dir)) {
                continue;
            }
            for (String name : names) {
                File candidate = new File(dir, name);
                if (candidate.isFile() && candidate.canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否Explicit路径。
     *
     * @param path 文件或目录路径。
     * @return 如果Explicit路径满足条件则返回 true，否则返回 false。
     */
    private boolean isExplicitPath(String path) {
        File file = new File(path);
        return file.isAbsolute() || path.indexOf(File.separatorChar) >= 0 || path.indexOf('/') >= 0;
    }

    /**
     * 执行executableNames相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回executable Names结果。
     */
    private List<String> executableNames(String path) {
        List<String> names = new ArrayList<String>();
        names.add(path);
        if (!isWindows()) {
            return names;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe")
                || lower.endsWith(".cmd")
                || lower.endsWith(".bat")
                || lower.endsWith(".com")) {
            return names;
        }
        String pathext = StrUtil.blankToDefault(System.getenv("PATHEXT"), ".COM;.EXE;.BAT;.CMD");
        for (String ext : pathext.split(";")) {
            if (StrUtil.isNotBlank(ext)) {
                names.add(path + ext.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 解析输出。
     *
     * @param stdout stdout 参数。
     * @param action 操作参数。
     * @return 返回解析后的输出。
     */
    private ParsedOutput parseOutput(String stdout, String action) {
        if (StrUtil.isBlank(stdout)) {
            return new ParsedOutput(Collections.<Finding>emptyList(), "");
        }
        try {
            Object parsed = ONode.deserialize(stdout, Object.class);
            if (!(parsed instanceof Map)) {
                return parseFailure(action);
            }
            Map<?, ?> map = (Map<?, ?>) parsed;
            List<Finding> findings = parseFindings(map.get("findings"));
            Object rawSummary = map.get("summary");
            String summary =
                    safeText(
                            rawSummary == null ? "" : String.valueOf(rawSummary),
                            MAX_SUMMARY_LENGTH);
            return new ParsedOutput(findings, summary);
        } catch (Exception e) {
            return parseFailure(action);
        }
    }

    /**
     * 解析Failure。
     *
     * @param action 操作参数。
     * @return 返回解析后的Failure。
     */
    private ParsedOutput parseFailure(String action) {
        if ("block".equals(action)) {
            return new ParsedOutput(
                    Collections.<Finding>emptyList(),
                    "security issue detected (details unavailable)");
        }
        if ("warn".equals(action)) {
            return new ParsedOutput(
                    Collections.<Finding>emptyList(),
                    "security warning detected (details unavailable)");
        }
        return new ParsedOutput(Collections.<Finding>emptyList(), "");
    }

    /**
     * 解析Findings。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的Findings。
     */
    private List<Finding> parseFindings(Object raw) {
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }
        List<Finding> findings = new ArrayList<Finding>();
        for (Object item : (List<?>) raw) {
            if (findings.size() >= MAX_FINDINGS) {
                break;
            }
            if (item instanceof Map) {
                findings.add(Finding.from((Map<?, ?>) item));
            }
        }
        return findings;
    }

    /**
     * 读取Utf8。
     *
     * @param inputStream 输入流参数。
     * @return 返回读取到的Utf8。
     */
    private static String readUtf8(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        StringBuilder buffer = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
        }
        return buffer.toString();
    }

    /**
     * 读取Quietly。
     *
     * @param future future 参数。
     * @return 返回读取到的Quietly。
     */
    private String getQuietly(Future<String> future) {
        try {
            return future.get(100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 执行限制相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param max max 参数。
     * @return 返回限制结果。
     */
    private static String limit(String value, int max) {
        String text = StrUtil.nullToEmpty(value);
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param max max 参数。
     * @return 返回safe Text结果。
     */
    private static String safeText(String value, int max) {
        return limit(SecretRedactor.redact(StrUtil.nullToEmpty(value), max), max);
    }

    /**
     * 生成安全展示用的消息。
     *
     * @param e 捕获到的异常。
     * @return 返回safe消息结果。
     */
    private static String safeMessage(Exception e) {
        if (e == null) {
            return "Exception";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return safeText(message, MAX_SUMMARY_LENGTH);
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回string Value结果。
     */
    private static String stringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 承载Parsed输出相关状态和辅助逻辑。 */
    private static class ParsedOutput {
        /** 保存findings集合，维持调用顺序或去重语义。 */
        private final List<Finding> findings;

        /** 记录Parsed输出中的摘要。 */
        private String summary;

        /**
         * 创建Parsed输出实例，并注入运行所需依赖。
         *
         * @param findings findings 参数。
         * @param summary 摘要参数。
         */
        private ParsedOutput(List<Finding> findings, String summary) {
            this.findings = findings;
            this.summary = summary;
        }
    }

    /** 承载审计摘要相关状态和辅助逻辑。 */
    private static class AuditSummary {
        /** 记录审计摘要中的surface。 */
        private final String surface;

        /** 记录审计摘要中的scanner状态。 */
        private final String scannerState;

        /** 记录审计摘要中的failure模式。 */
        private final String failureMode;

        /** 记录审计摘要中的failureBehavior。 */
        private final String failureBehavior;

        /** 记录审计摘要中的timeoutSeconds。 */
        private final int timeoutSeconds;

        /** 记录审计摘要中的终端。 */
        private final String shell;

        /** 记录审计摘要中的命令哈希。 */
        private final String commandHash;

        /** 记录审计摘要中的命令LengthBucket。 */
        private final String commandLengthBucket;

        /** 记录审计摘要中的action。 */
        private final String action;

        /** 是否启用审批Required。 */
        private final boolean approvalRequired;

        /** 记录审计摘要中的finding次数。 */
        private final int findingCount;

        /** 保存findingRuleSamples集合，维持调用顺序或去重语义。 */
        private final List<String> findingRuleSamples;

        /** 记录审计摘要中的摘要预览。 */
        private final String summaryPreview;

        /** 是否启用脱敏Applied。 */
        private final boolean redactionApplied;

        /** 是否启用原始命令Exposed。 */
        private final boolean rawCommandExposed;

        /** 是否启用原始路径Exposed。 */
        private final boolean rawPathExposed;

        /** 是否启用原始FindingsExposed。 */
        private final boolean rawFindingsExposed;

        /**
         * 创建审计Summary实例，并注入运行所需依赖。
         *
         * @param surface surface 参数。
         * @param scannerState scanner状态参数。
         * @param failureMode failure模式参数。
         * @param failureBehavior failureBehavior 参数。
         * @param timeoutSeconds 超时时间，单位为秒。
         * @param shell 终端参数。
         * @param commandHash 命令哈希参数。
         * @param commandLengthBucket 命令LengthBucket参数。
         * @param action 操作参数。
         * @param approvalRequired 审批Required参数。
         * @param findingCount findingCount 参数。
         * @param findingRuleSamples findingRuleSamples 参数。
         * @param summaryPreview 摘要预览参数。
         * @param redactionApplied redactionApplied 参数。
         * @param rawCommandExposed 原始命令Exposed参数。
         * @param rawPathExposed 文件或目录路径参数。
         * @param rawFindingsExposed 原始FindingsExposed参数。
         */
        private AuditSummary(
                String surface,
                String scannerState,
                String failureMode,
                String failureBehavior,
                int timeoutSeconds,
                String shell,
                String commandHash,
                String commandLengthBucket,
                String action,
                boolean approvalRequired,
                int findingCount,
                List<String> findingRuleSamples,
                String summaryPreview,
                boolean redactionApplied,
                boolean rawCommandExposed,
                boolean rawPathExposed,
                boolean rawFindingsExposed) {
            this.surface = safeText(surface, 100);
            this.scannerState = safeText(scannerState, 100);
            this.failureMode = safeText(failureMode, 100);
            this.failureBehavior = safeText(failureBehavior, 200);
            this.timeoutSeconds = timeoutSeconds;
            this.shell = safeText(shell, 100);
            this.commandHash = safeText(commandHash, 100);
            this.commandLengthBucket = safeText(commandLengthBucket, 100);
            this.action = safeText(action, 100);
            this.approvalRequired = approvalRequired;
            this.findingCount = findingCount;
            this.findingRuleSamples =
                    findingRuleSamples == null
                            ? Collections.<String>emptyList()
                            : Collections.unmodifiableList(
                                    new ArrayList<String>(findingRuleSamples));
            this.summaryPreview = safeText(summaryPreview, MAX_SUMMARY_LENGTH);
            this.redactionApplied = redactionApplied;
            this.rawCommandExposed = rawCommandExposed;
            this.rawPathExposed = rawPathExposed;
            this.rawFindingsExposed = rawFindingsExposed;
        }

        /**
         * 执行from相关逻辑。
         *
         * @param command 待执行或解析的命令文本。
         * @param shell 终端参数。
         * @param result 结果响应或执行结果。
         * @param diagnostic 诊断参数。
         * @return 返回from结果。
         */
        private static AuditSummary from(
                String command, String shell, ScanResult result, Diagnostic diagnostic) {
            ScanResult safeResult = result == null ? ScanResult.allow() : result;
            Diagnostic safeDiagnostic = diagnostic == null ? fallbackDiagnostic() : diagnostic;
            return new AuditSummary(
                    "tirith_command_scan",
                    safeDiagnostic.getScannerState(),
                    safeDiagnostic.getFailureMode(),
                    safeDiagnostic.getFailureBehavior(),
                    safeDiagnostic.getTimeoutSeconds(),
                    shell,
                    commandHash(command),
                    commandLengthBucket(command),
                    safeResult.getAction(),
                    safeResult.requiresApproval(),
                    safeResult.getFindings().size(),
                    findingRuleSamples(safeResult.getFindings()),
                    safeResult.getSummary(),
                    true,
                    false,
                    false,
                    false);
        }

        /**
         * 执行样例相关逻辑。
         *
         * @param diagnostic 诊断参数。
         * @return 返回sample结果。
         */
        private static AuditSummary sample(Diagnostic diagnostic) {
            Diagnostic safeDiagnostic = diagnostic == null ? fallbackDiagnostic() : diagnostic;
            return new AuditSummary(
                    "tirith_command_scan",
                    safeDiagnostic.getScannerState(),
                    safeDiagnostic.getFailureMode(),
                    safeDiagnostic.getFailureBehavior(),
                    safeDiagnostic.getTimeoutSeconds(),
                    "posix",
                    "sha256:sample",
                    "sample",
                    "warn",
                    true,
                    1,
                    Collections.singletonList("security_scan"),
                    "sample security warning (redacted)",
                    true,
                    false,
                    false,
                    false);
        }

        /**
         * 执行emptyLast相关逻辑。
         *
         * @param diagnostic 诊断参数。
         * @return 返回empty Last结果。
         */
        private static AuditSummary emptyLast(Diagnostic diagnostic) {
            Diagnostic safeDiagnostic = diagnostic == null ? fallbackDiagnostic() : diagnostic;
            return new AuditSummary(
                    "tirith_command_scan",
                    safeDiagnostic.getScannerState(),
                    safeDiagnostic.getFailureMode(),
                    safeDiagnostic.getFailureBehavior(),
                    safeDiagnostic.getTimeoutSeconds(),
                    "",
                    "",
                    "none",
                    "none",
                    false,
                    0,
                    Collections.<String>emptyList(),
                    "no tirith scan has been recorded in this service instance",
                    true,
                    false,
                    false,
                    false);
        }

        /**
         * 执行兜底诊断相关逻辑。
         *
         * @return 返回兜底诊断结果。
         */
        private static Diagnostic fallbackDiagnostic() {
            return new Diagnostic(
                    false,
                    false,
                    "tirith",
                    "tirith",
                    false,
                    5,
                    true,
                    "unknown",
                    "fail-open",
                    "allow_on_operational_failure",
                    "tirith diagnostic unavailable");
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("surface", surface);
            map.put("scannerState", scannerState);
            map.put("failureMode", failureMode);
            map.put("failureBehavior", failureBehavior);
            map.put("timeoutSeconds", Integer.valueOf(timeoutSeconds));
            map.put("shell", shell);
            map.put("commandHash", commandHash);
            map.put("commandLengthBucket", commandLengthBucket);
            map.put("action", action);
            map.put("approvalRequired", Boolean.valueOf(approvalRequired));
            map.put("findingCount", Integer.valueOf(findingCount));
            map.put("findingRuleSamples", findingRuleSamples);
            map.put("summaryPreview", summaryPreview);
            map.put("redactionApplied", Boolean.valueOf(redactionApplied));
            map.put("rawCommandExposed", Boolean.valueOf(rawCommandExposed));
            map.put("rawPathExposed", Boolean.valueOf(rawPathExposed));
            map.put("rawFindingsExposed", Boolean.valueOf(rawFindingsExposed));
            return map;
        }

        /**
         * 执行findingRuleSamples相关逻辑。
         *
         * @param findings findings 参数。
         * @return 返回finding Rule Samples结果。
         */
        private static List<String> findingRuleSamples(List<Finding> findings) {
            if (findings == null || findings.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> samples = new ArrayList<String>();
            for (Finding finding : findings) {
                if (samples.size() >= MAX_AUDIT_RULE_SAMPLES) {
                    break;
                }
                if (finding != null) {
                    samples.add(
                            safeText(
                                    StrUtil.blankToDefault(finding.getRuleId(), "security_scan"),
                                    200));
                }
            }
            return samples;
        }

        /**
         * 执行命令哈希相关逻辑。
         *
         * @param command 待执行或解析的命令文本。
         * @return 返回命令Hash结果。
         */
        private static String commandHash(String command) {
            String text = StrUtil.nullToEmpty(command);
            if (text.length() == 0) {
                return "";
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
                return "sha256:" + hexPrefix(bytes, 12);
            } catch (Exception ignored) {
                return "sha256:unavailable";
            }
        }

        /**
         * 执行hexPrefix相关逻辑。
         *
         * @param bytes 字节参数。
         * @param chars chars 参数。
         * @return 返回hex Prefix结果。
         */
        private static String hexPrefix(byte[] bytes, int chars) {
            char[] digits = "0123456789abcdef".toCharArray();
            StringBuilder builder = new StringBuilder(chars);
            for (int i = 0; i < bytes.length && builder.length() < chars; i++) {
                int value = bytes[i] & 0xff;
                builder.append(digits[value >>> 4]);
                if (builder.length() < chars) {
                    builder.append(digits[value & 0x0f]);
                }
            }
            return builder.toString();
        }

        /**
         * 执行命令LengthBucket相关逻辑。
         *
         * @param command 待执行或解析的命令文本。
         * @return 返回命令Length Bucket结果。
         */
        private static String commandLengthBucket(String command) {
            int length = StrUtil.nullToEmpty(command).length();
            if (length == 0) {
                return "empty";
            }
            if (length <= 80) {
                return "1-80";
            }
            if (length <= 400) {
                return "81-400";
            }
            if (length <= 1000) {
                return "401-1000";
            }
            return "1000+";
        }
    }

    /** 表示Scan结果，携带调用方后续判断所需信息。 */
    public static class ScanResult {
        /** 记录Scan中的action。 */
        private final String action;

        /** 保存findings集合，维持调用顺序或去重语义。 */
        private final List<Finding> findings;

        /** 记录Scan中的摘要。 */
        private final String summary;

        /**
         * 创建Scan结果实例，并注入运行所需依赖。
         *
         * @param action 操作参数。
         * @param findings findings 参数。
         * @param summary 摘要参数。
         */
        private ScanResult(String action, List<Finding> findings, String summary) {
            this.action = StrUtil.blankToDefault(action, "allow").toLowerCase(Locale.ROOT);
            this.findings =
                    findings == null
                            ? Collections.<Finding>emptyList()
                            : Collections.unmodifiableList(new ArrayList<Finding>(findings));
            this.summary = StrUtil.nullToEmpty(summary);
        }

        /**
         * 执行allow相关逻辑。
         *
         * @return 返回allow结果。
         */
        public static ScanResult allow() {
            return new ScanResult("allow", Collections.<Finding>emptyList(), "");
        }

        /**
         * 读取Action。
         *
         * @return 返回读取到的Action。
         */
        public String getAction() {
            return action;
        }

        /**
         * 读取Findings。
         *
         * @return 返回读取到的Findings。
         */
        public List<Finding> getFindings() {
            return findings;
        }

        /**
         * 读取Summary。
         *
         * @return 返回读取到的Summary。
         */
        public String getSummary() {
            return summary;
        }

        /**
         * 执行requires审批相关逻辑。
         *
         * @return 返回requires审批结果。
         */
        public boolean requiresApproval() {
            return "warn".equals(action) || "block".equals(action);
        }
    }

    /** 承载诊断相关状态和辅助逻辑。 */
    public static class Diagnostic {
        /** 标记该配置项或记录是否处于启用状态。 */
        private final boolean enabled;

        /** 是否启用已配置。 */
        private final boolean configured;

        /** 记录诊断中的已配置路径。 */
        private final String configuredPath;

        /** 记录诊断中的resolved路径。 */
        private final String resolvedPath;

        /** 记录诊断中的已配置展示路径。 */
        private final String configuredDisplayPath;

        /** 记录诊断中的resolved展示路径。 */
        private final String resolvedDisplayPath;

        /** 是否启用available。 */
        private final boolean available;

        /** 记录诊断中的timeoutSeconds。 */
        private final int timeoutSeconds;

        /** 是否启用failOpen。 */
        private final boolean failOpen;

        /** 记录诊断中的scanner状态。 */
        private final String scannerState;

        /** 记录诊断中的failure模式。 */
        private final String failureMode;

        /** 记录诊断中的failureBehavior。 */
        private final String failureBehavior;

        /** 记录诊断中的摘要。 */
        private final String summary;

        /**
         * 创建诊断实例，并注入运行所需依赖。
         *
         * @param enabled 启用状态开关值。
         * @param configured 已配置对象。
         * @param configuredPath 文件或目录路径参数。
         * @param resolvedPath 文件或目录路径参数。
         * @param available available 参数。
         * @param timeoutSeconds 超时时间，单位为秒。
         * @param failOpen failOpen 参数。
         * @param scannerState scanner状态参数。
         * @param failureMode failure模式参数。
         * @param failureBehavior failureBehavior 参数。
         * @param summary 摘要参数。
         */
        private Diagnostic(
                boolean enabled,
                boolean configured,
                String configuredPath,
                String resolvedPath,
                boolean available,
                int timeoutSeconds,
                boolean failOpen,
                String scannerState,
                String failureMode,
                String failureBehavior,
                String summary) {
            this.enabled = enabled;
            this.configured = configured;
            this.configuredPath = safeText(configuredPath, MAX_SUMMARY_LENGTH);
            this.resolvedPath = safeText(resolvedPath, MAX_SUMMARY_LENGTH);
            this.configuredDisplayPath = safePathRef(configuredPath);
            this.resolvedDisplayPath = safePathRef(resolvedPath);
            this.available = available;
            this.timeoutSeconds = timeoutSeconds;
            this.failOpen = failOpen;
            this.scannerState = safeText(scannerState, 100);
            this.failureMode = safeText(failureMode, 100);
            this.failureBehavior = safeText(failureBehavior, 200);
            this.summary = safeText(summary, MAX_SUMMARY_LENGTH);
        }

        /**
         * 判断是否启用。
         *
         * @return 如果启用满足条件则返回 true，否则返回 false。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 判断是否Configured。
         *
         * @return 如果Configured满足条件则返回 true，否则返回 false。
         */
        public boolean isConfigured() {
            return configured;
        }

        /**
         * 读取Configured路径。
         *
         * @return 返回读取到的Configured路径。
         */
        public String getConfiguredPath() {
            return configuredPath;
        }

        /**
         * 读取Resolved路径。
         *
         * @return 返回读取到的Resolved路径。
         */
        public String getResolvedPath() {
            return resolvedPath;
        }

        /**
         * 判断是否Available。
         *
         * @return 如果Available满足条件则返回 true，否则返回 false。
         */
        public boolean isAvailable() {
            return available;
        }

        /**
         * 读取Timeout Seconds。
         *
         * @return 返回读取到的Timeout Seconds。
         */
        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        /**
         * 判断是否Fail Open。
         *
         * @return 如果Fail Open满足条件则返回 true，否则返回 false。
         */
        public boolean isFailOpen() {
            return failOpen;
        }

        /**
         * 读取Scanner状态。
         *
         * @return 返回读取到的Scanner状态。
         */
        public String getScannerState() {
            return scannerState;
        }

        /**
         * 读取Failure模式。
         *
         * @return 返回读取到的Failure模式。
         */
        public String getFailureMode() {
            return failureMode;
        }

        /**
         * 读取Failure Behavior。
         *
         * @return 返回读取到的Failure Behavior。
         */
        public String getFailureBehavior() {
            return failureBehavior;
        }

        /**
         * 读取Summary。
         *
         * @return 返回读取到的Summary。
         */
        public String getSummary() {
            return summary;
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("enabled", Boolean.valueOf(enabled));
            map.put("configured", Boolean.valueOf(configured));
            map.put("configuredPath", configuredDisplayPath);
            map.put("resolvedPath", resolvedDisplayPath);
            map.put("available", Boolean.valueOf(available));
            map.put("timeoutSeconds", Integer.valueOf(timeoutSeconds));
            map.put("failOpen", Boolean.valueOf(failOpen));
            map.put("scannerState", scannerState);
            map.put("failureMode", failureMode);
            map.put("failureBehavior", failureBehavior);
            map.put("summary", summary);
            return map;
        }
    }

    /**
     * 生成安全展示用的路径Ref。
     *
     * @param path 文件或目录路径。
     * @return 返回safe路径Ref结果。
     */
    private static String safePathRef(String path) {
        String value = StrUtil.blankToDefault(path, "tirith").trim();
        if (StrUtil.isBlank(value)) {
            return "path://tirith";
        }
        File file = new File(value);
        if (file.isAbsolute()
                || value.indexOf(File.separatorChar) >= 0
                || value.indexOf('/') >= 0) {
            String name = StrUtil.blankToDefault(file.getName(), "tirith");
            return "path://" + safeText(name, 200);
        }
        return safeText(value, 200);
    }

    /** 承载Finding相关状态和辅助逻辑。 */
    public static class Finding {
        /** 记录Finding中的rule标识。 */
        private final String ruleId;

        /** 记录Finding中的severity。 */
        private final String severity;

        /** 记录Finding中的标题。 */
        private final String title;

        /** 记录Finding中的描述。 */
        private final String description;

        /**
         * 创建Finding实例，并注入运行所需依赖。
         *
         * @param ruleId rule标识。
         * @param severity severity 参数。
         * @param title title 参数。
         * @param description 描述参数。
         */
        private Finding(String ruleId, String severity, String title, String description) {
            this.ruleId = safeText(ruleId, 200);
            this.severity = safeText(severity, 100);
            this.title = safeText(title, 300);
            this.description = safeText(description, MAX_SUMMARY_LENGTH);
        }

        /**
         * 执行from相关逻辑。
         *
         * @param map 待读取的映射对象。
         * @return 返回from结果。
         */
        public static Finding from(Map<?, ?> map) {
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    values.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return new Finding(
                    StrUtil.blankToDefault(values.get("rule_id"), values.get("ruleId")),
                    values.get("severity"),
                    values.get("title"),
                    values.get("description"));
        }

        /**
         * 读取Rule标识。
         *
         * @return 返回读取到的Rule标识。
         */
        public String getRuleId() {
            return ruleId;
        }

        /**
         * 读取Severity。
         *
         * @return 返回读取到的Severity。
         */
        public String getSeverity() {
            return severity;
        }

        /**
         * 读取标题。
         *
         * @return 返回读取到的标题。
         */
        public String getTitle() {
            return title;
        }

        /**
         * 读取Description。
         *
         * @return 返回读取到的Description。
         */
        public String getDescription() {
            return description;
        }
    }
}
