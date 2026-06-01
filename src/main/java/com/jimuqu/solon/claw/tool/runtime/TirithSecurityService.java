package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private final AppConfig appConfig;

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
            return ScanResult.allow();
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
            return operationalFailure(
                    failOpen, "tirith path unavailable", "tirith path unavailable (fail-closed)");
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
                return operationalFailure(
                        failOpen,
                        "tirith timed out (" + timeoutSeconds + "s)",
                        "tirith timed out (fail-closed)");
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
                return operationalFailure(
                        failOpen,
                        "tirith exit code " + exitCode + " (fail-open)",
                        "tirith exit code " + exitCode + " (fail-closed)");
            }

            ParsedOutput parsed = parseOutput(out, action);
            if (StrUtil.isBlank(parsed.summary) && StrUtil.isNotBlank(err) && !"allow".equals(action)) {
                parsed.summary = safeText(err.trim(), MAX_SUMMARY_LENGTH);
            }
            return new ScanResult(action, parsed.findings, parsed.summary);
        } catch (Exception e) {
            String message = safeMessage(e);
            return operationalFailure(
                    failOpen,
                    "tirith unavailable: " + message,
                    "tirith spawn failed (fail-closed): " + message);
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
                "finding.ruleId",
                "finding.severity",
                "finding.title",
                "finding.description");
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
