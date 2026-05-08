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
