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
    private static final int MAX_FINDINGS = 50;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_AUDIT_RULE_SAMPLES = 5;

    private final AppConfig appConfig;
    private volatile AuditSummary lastAuditSummary;

    public TirithSecurityService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public ScanResult checkCommandSecurity(String command) {
        return checkCommandSecurity(command, "posix");
    }

    private ScanResult checkCommandSecurity(String command, String shell) {
        AppConfig.SecurityConfig security =
                appConfig == null ? null : appConfig.getSecurity();
        if (security != null && !security.isTirithEnabled()) {
            return recordAndReturn(command, shell, ScanResult.allow());
        }

        String path =
                security == null
                        ? "tirith"
                        : StrUtil.blankToDefault(security.getTirithPath(), "tirith").trim();
        int timeoutSeconds =
                security == null
                        ? 5
                        : Math.max(1, security.getTirithTimeoutSeconds());
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
                                @Override
                                public String call() throws Exception {
                                    return readUtf8(started.getInputStream());
                                }
                            });
            Future<String> stderr =
                    executor.submit(
                            new Callable<String>() {
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
            if (StrUtil.isBlank(parsed.summary) && StrUtil.isNotBlank(err) && !"allow".equals(action)) {
                parsed.summary = safeText(err.trim(), MAX_SUMMARY_LENGTH);
            }
            return recordAndReturn(command, shell, new ScanResult(action, parsed.findings, parsed.summary));
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
                    // no-op
                }
            }
            executor.shutdownNow();
        }
    }

    public ScanResult checkCommandSecurityForTool(String toolName, String command) {
        return checkCommandSecurity(command, shellForToolCommand(toolName, command));
    }

    public Diagnostic diagnose() {
        AppConfig.SecurityConfig security =
                appConfig == null ? null : appConfig.getSecurity();
        boolean enabled = security == null || security.isTirithEnabled();
        String configuredPath =
                security == null
                        ? "tirith"
                        : StrUtil.blankToDefault(security.getTirithPath(), "tirith").trim();
        int timeoutSeconds =
                security == null
                        ? 5
                        : Math.max(1, security.getTirithTimeoutSeconds());
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
        summary.put("description", "Tirith scans command text through an external checker, maps warn/block findings into approval-required security results, and redacts diagnostics before exposing them.");
        return summary;
    }

    private String scannerState(boolean enabled, boolean configured, boolean available) {
        if (!enabled) {
            return "disabled";
        }
        if (available) {
            return "available";
        }
        return configured ? "configured_unavailable" : "unconfigured";
    }

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

    private Map<String, Object> lastAuditMap(Diagnostic diagnostic) {
        AuditSummary last = lastAuditSummary;
        return (last == null ? emptyLastAuditSummary(diagnostic) : last).toMap();
    }

    private AuditSummary emptyLastAuditSummary(Diagnostic diagnostic) {
        return AuditSummary.emptyLast(diagnostic);
    }

    private AuditSummary sampleAuditSummary(Diagnostic diagnostic) {
        return AuditSummary.sample(diagnostic);
    }

    private ScanResult recordAndReturn(String command, String shell, ScanResult result) {
        try {
            lastAuditSummary = AuditSummary.from(command, normalizeShell(shell), result, diagnose());
        } catch (Exception ignored) {
            // Diagnostics must never change the security decision.
        }
        return result;
    }

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
        if (text.startsWith("cmd /")
                || text.startsWith("cmd.exe /")
                || text.startsWith("cmd ")) {
            return "cmd";
        }
        return "posix";
    }

    private boolean isTerminalTool(String toolName) {
        return "execute_shell".equals(toolName)
                || "terminal".equals(toolName)
                || "run_terminal".equals(toolName)
                || "terminal_run".equals(toolName)
                || "terminal_exec".equals(toolName)
                || "terminal_execute".equals(toolName)
                || "executeshell".equals(toolName);
    }

    private String normalizeShell(String shell) {
        String value = StrUtil.blankToDefault(shell, "posix").trim().toLowerCase(Locale.ROOT);
        if ("powershell".equals(value) || "cmd".equals(value)) {
            return value;
        }
        return "posix";
    }

    private ScanResult operationalFailure(
            boolean failOpen, String failOpenSummary, String failClosedSummary) {
        if (failOpen) {
            return new ScanResult("allow", Collections.<Finding>emptyList(), failOpenSummary);
        }
        return new ScanResult("block", Collections.<Finding>emptyList(), failClosedSummary);
    }

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

    private boolean isExplicitPath(String path) {
        File file = new File(path);
        return file.isAbsolute() || path.indexOf(File.separatorChar) >= 0 || path.indexOf('/') >= 0;
    }

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

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

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
                    safeText(rawSummary == null ? "" : String.valueOf(rawSummary), MAX_SUMMARY_LENGTH);
            return new ParsedOutput(findings, summary);
        } catch (Exception e) {
            return parseFailure(action);
        }
    }

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

    private String getQuietly(Future<String> future) {
        try {
            return future.get(100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static String limit(String value, int max) {
        String text = StrUtil.nullToEmpty(value);
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String safeText(String value, int max) {
        return limit(SecretRedactor.redact(StrUtil.nullToEmpty(value), max), max);
    }

    private static String safeMessage(Exception e) {
        if (e == null) {
            return "Exception";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return safeText(message, MAX_SUMMARY_LENGTH);
    }

    private static String stringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static class ParsedOutput {
        private final List<Finding> findings;
        private String summary;

        private ParsedOutput(List<Finding> findings, String summary) {
            this.findings = findings;
            this.summary = summary;
        }
    }

    private static class AuditSummary {
        private final String surface;
        private final String scannerState;
        private final String failureMode;
        private final String failureBehavior;
        private final int timeoutSeconds;
        private final String shell;
        private final String commandHash;
        private final String commandLengthBucket;
        private final String action;
        private final boolean approvalRequired;
        private final int findingCount;
        private final List<String> findingRuleSamples;
        private final String summaryPreview;
        private final boolean redactionApplied;
        private final boolean rawCommandExposed;
        private final boolean rawPathExposed;
        private final boolean rawFindingsExposed;

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
                            : Collections.unmodifiableList(new ArrayList<String>(findingRuleSamples));
            this.summaryPreview = safeText(summaryPreview, MAX_SUMMARY_LENGTH);
            this.redactionApplied = redactionApplied;
            this.rawCommandExposed = rawCommandExposed;
            this.rawPathExposed = rawPathExposed;
            this.rawFindingsExposed = rawFindingsExposed;
        }

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
                    samples.add(safeText(StrUtil.blankToDefault(finding.getRuleId(), "security_scan"), 200));
                }
            }
            return samples;
        }

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

    public static class ScanResult {
        private final String action;
        private final List<Finding> findings;
        private final String summary;

        private ScanResult(String action, List<Finding> findings, String summary) {
            this.action = StrUtil.blankToDefault(action, "allow").toLowerCase(Locale.ROOT);
            this.findings =
                    findings == null
                            ? Collections.<Finding>emptyList()
                            : Collections.unmodifiableList(new ArrayList<Finding>(findings));
            this.summary = StrUtil.nullToEmpty(summary);
        }

        public static ScanResult allow() {
            return new ScanResult("allow", Collections.<Finding>emptyList(), "");
        }

        public String getAction() {
            return action;
        }

        public List<Finding> getFindings() {
            return findings;
        }

        public String getSummary() {
            return summary;
        }

        public boolean requiresApproval() {
            return "warn".equals(action) || "block".equals(action);
        }
    }

    public static class Diagnostic {
        private final boolean enabled;
        private final boolean configured;
        private final String configuredPath;
        private final String resolvedPath;
        private final String configuredDisplayPath;
        private final String resolvedDisplayPath;
        private final boolean available;
        private final int timeoutSeconds;
        private final boolean failOpen;
        private final String scannerState;
        private final String failureMode;
        private final String failureBehavior;
        private final String summary;

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

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isConfigured() {
            return configured;
        }

        public String getConfiguredPath() {
            return configuredPath;
        }

        public String getResolvedPath() {
            return resolvedPath;
        }

        public boolean isAvailable() {
            return available;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public String getScannerState() {
            return scannerState;
        }

        public String getFailureMode() {
            return failureMode;
        }

        public String getFailureBehavior() {
            return failureBehavior;
        }

        public String getSummary() {
            return summary;
        }

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

    private static String safePathRef(String path) {
        String value = StrUtil.blankToDefault(path, "tirith").trim();
        if (StrUtil.isBlank(value)) {
            return "path://tirith";
        }
        File file = new File(value);
        if (file.isAbsolute() || value.indexOf(File.separatorChar) >= 0 || value.indexOf('/') >= 0) {
            String name = StrUtil.blankToDefault(file.getName(), "tirith");
            return "path://" + safeText(name, 200);
        }
        return safeText(value, 200);
    }

    public static class Finding {
        private final String ruleId;
        private final String severity;
        private final String title;
        private final String description;

        private Finding(String ruleId, String severity, String title, String description) {
            this.ruleId = safeText(ruleId, 200);
            this.severity = safeText(severity, 100);
            this.title = safeText(title, 300);
            this.description = safeText(description, MAX_SUMMARY_LENGTH);
        }

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

        public String getRuleId() {
            return ruleId;
        }

        public String getSeverity() {
            return severity;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }
    }
}
