package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.skills.sys.NodejsSkill;
import org.noear.solon.ai.skills.sys.PythonSkill;

/** Solon AI code execution skills wrapped with Hermes-style safety checks. */
public class HermesCodeExecutionSkills {
    private static final int DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_MAX_STDOUT_CHARS = 50000;
    private static final int MAX_STDERR_CHARS = 10000;
    private static final Pattern ANSI_CONTROL_SEQUENCE =
            Pattern.compile(
                    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|P[^\\u001B]*(?:\\u001B\\\\)|[_^][^\\u001B]*(?:\\u001B\\\\)|[@-Z\\\\-_])|[\\u0080-\\u009F]");
    private static final String[] SAFE_ENV_PREFIXES =
            new String[] {
                "PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "LANG", "LC_", "TERM",
                "TMPDIR", "TMP", "TEMP", "SHELL", "LOGNAME", "XDG_", "PYTHONPATH",
                "VIRTUAL_ENV", "CONDA", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"
            };
    private static final String[] SECRET_ENV_SUBSTRINGS =
            new String[] {"KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"};

    private HermesCodeExecutionSkills() {}

    public static class SafeExecuteCodeTool {
        private final String workDir;
        private final String pythonCommand;
        private final SecurityPolicyService securityPolicyService;
        private final AppConfig appConfig;

        public SafeExecuteCodeTool(
                String workDir,
                String pythonCommand,
                SecurityPolicyService securityPolicyService,
                AppConfig appConfig) {
            this.workDir = checkedWorkDir(workDir);
            this.pythonCommand = StrUtil.blankToDefault(pythonCommand, defaultPythonCommand());
            this.securityPolicyService = securityPolicyService;
            this.appConfig = appConfig;
        }

        @ToolMapping(
                name = "execute_code",
                description =
                        "Run a Python script and return a Hermes-style JSON result. Use normal tools for single tool calls; execute_code is for multi-step local processing. Current Java runtime does not expose Hermes RPC tool stubs yet.")
        public String executeCode(
                @Param(
                                name = "code",
                                description =
                                        "Python code to execute. Print the final result to stdout.")
                        String code,
                @Param(
                                name = "timeout",
                                required = false,
                                defaultValue = "300",
                                description = "Timeout in seconds. Defaults to 300.")
                        Integer timeoutSeconds) {
            long started = System.nanoTime();
            int timeout = normalizeTimeout(timeoutSeconds);
            int toolCallsMade = 0;
            try {
                if (StrUtil.isBlank(code)) {
                    return executeCodeError("Python code must be a non-empty string.", toolCallsMade, started);
                }
                assertSafe(ToolNameConstants.EXECUTE_PYTHON, code, securityPolicyService);

                Path staging = Files.createTempDirectory(new File(workDir).toPath(), "execute_code_");
                try {
                    writeHermesToolsStub(staging.resolve("hermes_tools.py"));
                    Path script = staging.resolve("script.py");
                    Files.write(script, StrUtil.nullToEmpty(code).getBytes(StandardCharsets.UTF_8));
                    ProcessBuilder builder =
                            new ProcessBuilder(Arrays.asList(pythonCommand, script.toString()));
                    builder.directory(new File(workDir));
                    builder.redirectErrorStream(false);
                    configureSandboxEnvironment(builder.environment(), staging);
                    Process process = builder.start();
                    process.getOutputStream().close();
                    CompletableFuture<String> stdout =
                            CompletableFuture.supplyAsync(
                                    () -> readProcessText(process.getInputStream(), maxStdoutChars()));
                    CompletableFuture<String> stderr =
                            CompletableFuture.supplyAsync(
                                    () -> readProcessText(process.getErrorStream(), MAX_STDERR_CHARS));

                    boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                    String status = "success";
                    if (!finished) {
                        process.destroyForcibly();
                        status = "timeout";
                    }
                    String stdoutText = cleanOutput(stdout.get(3, TimeUnit.SECONDS), maxStdoutChars());
                    String stderrText = cleanOutput(stderr.get(3, TimeUnit.SECONDS), MAX_STDERR_CHARS);
                    int exitCode = finished ? process.exitValue() : -1;
                    Map<String, Object> result = baseExecuteCodeResult(status, stdoutText, toolCallsMade, started);
                    if ("timeout".equals(status)) {
                        String timeoutMessage = "Script timed out after " + timeout + "s and was killed.";
                        result.put("error", timeoutMessage);
                        result.put(
                                "output",
                                StrUtil.isBlank(stdoutText)
                                        ? timeoutMessage
                                        : stdoutText + "\n\n" + timeoutMessage);
                    } else if (exitCode != 0) {
                        result.put("status", "error");
                        result.put(
                                "error",
                                StrUtil.blankToDefault(
                                        stderrText, "Script exited with code " + exitCode));
                        if (StrUtil.isNotBlank(stderrText)) {
                            result.put("output", stdoutText + "\n--- stderr ---\n" + stderrText);
                        }
                    }
                    return ONode.serialize(result);
                } finally {
                    deleteQuietly(staging);
                }
            } catch (Exception e) {
                return executeCodeError(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        toolCallsMade,
                        started);
            }
        }

        private Map<String, Object> baseExecuteCodeResult(
                String status, String output, int toolCallsMade, long started) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", status);
            result.put("output", output);
            result.put("tool_calls_made", Integer.valueOf(toolCallsMade));
            result.put("duration_seconds", Double.valueOf(durationSeconds(started)));
            return result;
        }

        private String executeCodeError(String error, int toolCallsMade, long started) {
            Map<String, Object> result =
                    baseExecuteCodeResult(
                            "error", "", toolCallsMade, started);
            result.put("error", cleanOutput(error, MAX_STDERR_CHARS));
            return ONode.serialize(result);
        }

        private void configureSandboxEnvironment(Map<String, String> env, Path staging) {
            Map<String, String> raw = new LinkedHashMap<String, String>(env);
            env.clear();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                String name = entry.getKey();
                if (isSecretEnvName(name)) {
                    continue;
                }
                if (isSafeEnvName(name)) {
                    env.put(name, entry.getValue());
                }
            }
            String existingPythonPath = env.get("PYTHONPATH");
            env.put(
                    "PYTHONPATH",
                    existingPythonPath == null || existingPythonPath.length() == 0
                            ? staging.toString()
                            : staging.toString() + File.pathSeparator + existingPythonPath);
            env.put("PYTHONIOENCODING", "UTF-8");
            env.put("PYTHONDONTWRITEBYTECODE", "1");
        }

        private boolean isSafeEnvName(String name) {
            String value = StrUtil.nullToEmpty(name);
            for (String prefix : SAFE_ENV_PREFIXES) {
                if (value.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isSecretEnvName(String name) {
            String upper = StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT);
            for (String marker : SECRET_ENV_SUBSTRINGS) {
                if (upper.contains(marker)) {
                    return true;
                }
            }
            return false;
        }

        private int normalizeTimeout(Integer timeoutSeconds) {
            int requested =
                    timeoutSeconds == null
                            ? DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS
                            : timeoutSeconds.intValue();
            if (requested <= 0) {
                requested = DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS;
            }
            int maxSeconds = DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS;
            if (appConfig != null && appConfig.getTerminal() != null) {
                maxSeconds =
                        Math.max(
                                DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS,
                                appConfig.getTerminal().getMaxForegroundTimeoutSeconds());
            }
            return Math.min(requested, Math.max(1, maxSeconds));
        }

        private int maxStdoutChars() {
            int value = DEFAULT_MAX_STDOUT_CHARS;
            if (appConfig != null && appConfig.getTask() != null) {
                value = appConfig.getTask().getToolOutputInlineLimit();
            }
            return Math.max(256, value);
        }

        private String cleanOutput(String text, int maxChars) {
            String value = StrUtil.nullToEmpty(text);
            if (value.length() > maxChars) {
                int headChars = Math.max(1, (int) (maxChars * 0.4));
                int tailChars = Math.max(1, maxChars - headChars);
                int omitted = Math.max(0, value.length() - headChars - tailChars);
                value =
                        value.substring(0, headChars)
                                + "\n\n... [OUTPUT TRUNCATED - "
                                + omitted
                                + " chars omitted out of "
                                + value.length()
                                + " total] ...\n\n"
                                + value.substring(value.length() - tailChars);
            }
            return SecretRedactor.redact(ANSI_CONTROL_SEQUENCE.matcher(value).replaceAll(""));
        }

        private double durationSeconds(long started) {
            double value = (System.nanoTime() - started) / 1000000000.0d;
            return Math.round(value * 100.0d) / 100.0d;
        }

        private void writeHermesToolsStub(Path target) throws Exception {
            String source =
                    "import json, shlex, time\n"
                            + "\n"
                            + "def json_parse(text):\n"
                            + "    return json.loads(text, strict=False)\n"
                            + "\n"
                            + "def shell_quote(s):\n"
                            + "    return shlex.quote(s)\n"
                            + "\n"
                            + "def retry(fn, max_attempts=3, delay=2):\n"
                            + "    last_err = None\n"
                            + "    for attempt in range(max_attempts):\n"
                            + "        try:\n"
                            + "            return fn()\n"
                            + "        except Exception as e:\n"
                            + "            last_err = e\n"
                            + "            if attempt < max_attempts - 1:\n"
                            + "                time.sleep(delay * (2 ** attempt))\n"
                            + "    raise last_err\n"
                            + "\n"
                            + "def _unavailable(name):\n"
                            + "    raise RuntimeError(name + ' is not available in jimuqu-agent execute_code yet. Use normal tool calls instead.')\n"
                            + "\n"
                            + "def web_search(*args, **kwargs): return _unavailable('web_search')\n"
                            + "def web_extract(*args, **kwargs): return _unavailable('web_extract')\n"
                            + "def read_file(*args, **kwargs): return _unavailable('read_file')\n"
                            + "def write_file(*args, **kwargs): return _unavailable('write_file')\n"
                            + "def search_files(*args, **kwargs): return _unavailable('search_files')\n"
                            + "def patch(*args, **kwargs): return _unavailable('patch')\n"
                            + "def terminal(*args, **kwargs): return _unavailable('terminal')\n";
            Files.write(target, source.getBytes(StandardCharsets.UTF_8));
        }

        private void deleteQuietly(Path path) {
            if (path == null || !Files.exists(path)) {
                return;
            }
            try {
                if (Files.isDirectory(path)) {
                    Files.list(path)
                            .forEach(
                                    child -> {
                                        deleteQuietly(child);
                                    });
                }
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    public static class SafePythonSkill extends PythonSkill {
        private final SecurityPolicyService securityPolicyService;

        public SafePythonSkill(
                String workDir, String pythonCommand, SecurityPolicyService securityPolicyService) {
            super(workDir, pythonCommand);
            this.securityPolicyService = securityPolicyService;
        }

        @Override
        @ToolMapping(name = "execute_python", description = "执行 Python 代码，并返回标准输出。")
        public String execute(
                @Param("code") String code,
                @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒")
                        Integer timeout) {
            assertSafe(ToolNameConstants.EXECUTE_PYTHON, code, securityPolicyService);
            return super.execute(code, timeout);
        }
    }

    public static class SafeNodejsSkill extends NodejsSkill {
        private final SecurityPolicyService securityPolicyService;

        public SafeNodejsSkill(String workDir, SecurityPolicyService securityPolicyService) {
            super(workDir);
            this.securityPolicyService = securityPolicyService;
        }

        @Override
        @ToolMapping(name = "execute_js", description = "执行 Node.js JavaScript 代码，并返回标准输出。")
        public String execute(
                @Param("code") String code,
                @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒")
                        Integer timeout) {
            assertSafe(ToolNameConstants.EXECUTE_JS, code, securityPolicyService);
            return super.execute(code, timeout);
        }
    }

    static void assertSafe(
            String toolName, String code, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict fileVerdict =
                    securityPolicyService.checkCommandPaths(code);
            if (!fileVerdict.isAllowed()) {
                throw new IllegalArgumentException(blockedFileMessage(toolName, fileVerdict));
            }
            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkCommandUrls(code);
            if (!urlVerdict.isAllowed()) {
                throw new IllegalArgumentException(blockedUrlMessage(urlVerdict));
            }
        }

        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(null, securityPolicyService);
        DangerousCommandApprovalService.DetectionResult hardline =
                approvalService.detectHardline(toolName, code);
        if (hardline != null) {
            throw new IllegalArgumentException(blockedHardlineMessage(toolName, hardline));
        }
        String foregroundGuidance = approvalService.foregroundBackgroundGuidance(toolName, code);
        if (foregroundGuidance != null) {
            throw new IllegalArgumentException(foregroundGuidance);
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(toolName, code);
        if (dangerous != null) {
            throw new IllegalArgumentException(blockedDangerousMessage(toolName, dangerous));
        }
    }

    private static String blockedFileMessage(
            String toolName, SecurityPolicyService.FileVerdict verdict) {
        return "BLOCKED: 文件安全策略阻止访问："
                + verdict.getMessage()
                + "\n工具："
                + toolName
                + "\n路径："
                + StrUtil.nullToEmpty(verdict.getPath())
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }

    private static String blockedUrlMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretRedactor.maskUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    private static String blockedHardlineMessage(
            String toolName, DangerousCommandApprovalService.DetectionResult detection) {
        return "BLOCKED: 该 "
                + toolName
                + " 调用命中硬阻断安全规则："
                + StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey())
                + "。请改用更小、更可审计的安全操作。";
    }

    private static String blockedDangerousMessage(
            String toolName, DangerousCommandApprovalService.DetectionResult detection) {
        return "BLOCKED: 该 "
                + toolName
                + " 调用命中危险命令安全规则："
                + StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey())
                + "。直接执行入口没有审批上下文，请改用可审批的 Agent 工具调用流程或拆成更安全的操作。";
    }

    private static String readProcessText(java.io.InputStream inputStream, int maxChars) {
        try {
            InputStreamReader reader =
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            StringBuilder buffer = new StringBuilder();
            int maxReadChars = Math.max(1024, maxChars * 2);
            char[] chars = new char[4096];
            int read;
            while ((read = reader.read(chars)) != -1) {
                if (buffer.length() < maxReadChars) {
                    int keep = Math.min(read, maxReadChars - buffer.length());
                    buffer.append(chars, 0, keep);
                }
            }
            return buffer.toString();
        } catch (Exception e) {
            return "系统失败: " + e.getMessage();
        }
    }

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

    private static String defaultPythonCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "python"
                : "python3";
    }
}
