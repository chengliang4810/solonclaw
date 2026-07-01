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
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.talents.sys.NodejsTalent;
import org.noear.solon.ai.talents.sys.PythonTalent;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载Solon项目CodeExecution技能相关状态和辅助逻辑。 */
public class SolonClawCodeExecutionSkills {
    /** 记录代码执行工具的清理、RPC 与进程读取异常，避免关键路径静默吞错。 */
    private static final Logger log = LoggerFactory.getLogger(SolonClawCodeExecutionSkills.class);

    /** 默认执行CODE超时时间秒数的统一常量值。 */
    private static final int DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS = 300;

    /** 默认最大STDOUTCHARS的统一常量值。 */
    private static final int DEFAULT_MAX_STDOUT_CHARS = 50000;

    /** 最大STDERRCHARS的统一常量值。 */
    private static final int MAX_STDERR_CHARS = 10000;

    /** 执行CODERPC工具的统一常量值。 */
    private static final List<String> EXECUTE_CODE_RPC_TOOLS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "websearch",
                            "webfetch",
                            "file_read",
                            "file_write",
                            "read_file",
                            "write_file",
                            "search_files",
                            "patch",
                            "terminal"));

    /** MANAGED文件工具CALL的统一常量值。 */
    private static final Pattern MANAGED_FILE_TOOL_CALL =
            Pattern.compile(
                    "(?:\\bsolonclaw_tools\\s*\\.\\s*)?(?:\\bread_file|\\bwrite_file)\\s*\\(\\s*(['\"])(.*?)\\1",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** MANAGED webfetch 工具 CALL 的统一常量值。 */
    private static final Pattern MANAGED_WEBFETCH_TOOL_CALL =
            Pattern.compile(
                    "(?:\\bsolonclaw_tools\\s*\\.\\s*)?\\bwebfetch\\s*\\(\\s*(['\"])(.*?)\\1",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Python 字符串内转义控制序列的统一匹配常量。 */
    private static final Pattern PYTHON_ESCAPED_CONTROL_SEQUENCE =
            Pattern.compile("\\\\(?:u001[bB]|x1[bB]|033)");

    /** 创建Solon项目Code Execution技能实例。 */
    private SolonClawCodeExecutionSkills() {}

    /**
     * 执行codeExecution策略摘要相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回code Execution策略Summary结果。
     */
    public static Map<String, Object> codeExecutionPolicySummary(AppConfig appConfig) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("executeCodeSupported", Boolean.TRUE);
        summary.put("executePythonSupported", Boolean.TRUE);
        summary.put("executeJsSupported", Boolean.TRUE);
        summary.put("solonAiSysSkillsWrapped", Boolean.TRUE);
        summary.put("workdirTextValidated", Boolean.TRUE);
        summary.put(
                "scriptPreflightPathPolicy", Boolean.valueOf(isFileGuardrailEnabled(appConfig)));
        summary.put("scriptPreflightUrlPolicy", Boolean.valueOf(isUrlGuardrailEnabled(appConfig)));
        summary.put("fileGuardrailMode", fileGuardrailMode(appConfig));
        summary.put("urlGuardrailMode", urlGuardrailMode(appConfig));
        summary.put("dangerousCommandRulesApplied", Boolean.TRUE);
        summary.put("hardlineRulesApplied", Boolean.TRUE);
        summary.put("foregroundBackgroundGuardrail", Boolean.TRUE);
        summary.put("managedFileToolPathLiteralsIgnoredForPreflight", Boolean.TRUE);
        summary.put("managedWebfetchUrlLiteralsIgnoredForPreflight", Boolean.TRUE);
        summary.put("escapedControlSequencesIgnoredForPathPreflight", Boolean.TRUE);
        summary.put("stagingDirectoryPerRun", Boolean.TRUE);
        summary.put("stagingPrefix", "execute_code_");
        summary.put("stagingCleanup", Boolean.TRUE);
        summary.put("sandboxEnvironmentSanitized", Boolean.TRUE);
        summary.put(
                "subprocessEnvironmentPolicy",
                SubprocessEnvironmentSanitizer.policySummary(appConfig));
        summary.put("pythonPathPrependsStaging", Boolean.TRUE);
        summary.put("pythonIoEncodingUtf8", Boolean.TRUE);
        summary.put("pythonDontWriteBytecode", Boolean.TRUE);
        summary.put("rpcToolBridgeEnabled", Boolean.TRUE);
        summary.put("rpcTools", EXECUTE_CODE_RPC_TOOLS);
        summary.put("rpcRequestFilesSorted", Boolean.TRUE);
        summary.put("rpcToolOutputsRedacted", Boolean.TRUE);
        summary.put("defaultTimeoutSeconds", Integer.valueOf(DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS));
        summary.put("maxTimeoutClampedByTerminalConfig", Boolean.TRUE);
        summary.put("timeoutKillsProcess", Boolean.TRUE);
        summary.put("stdoutLimitChars", Integer.valueOf(defaultMaxStdoutChars(appConfig)));
        summary.put("stderrLimitChars", Integer.valueOf(MAX_STDERR_CHARS));
        summary.put("ansiOutputStripped", Boolean.TRUE);
        summary.put("outputRedacted", Boolean.TRUE);
        summary.put("outputTruncated", Boolean.TRUE);
        summary.put("stderrReturnedOnlyOnErrors", Boolean.TRUE);
        summary.put("safeErrorTextRedacted", Boolean.TRUE);
        return summary;
    }

    /** 提供Safe执行Code工具能力，供 Agent 运行时按安全策略调用。 */
    public static class SafeExecuteCodeTool {
        /** 记录安全执行Code中的work目录。 */
        private final String workDir;

        /** 记录安全执行Code中的python命令。 */
        private final String pythonCommand;

        /** 注入安全策略服务，用于调用对应业务能力。 */
        private final SecurityPolicyService securityPolicyService;

        /** 注入应用配置，用于安全执行Code。 */
        private final AppConfig appConfig;

        /** 记录安全执行Code中的文件状态Tracker。 */
        private final SolonClawFileStateTracker fileStateTracker;

        /** 记录安全执行Code中的文件技能。 */
        private final SolonClawFileReadWriteSkill fileSkill;

        /** 记录安全执行Code中的补丁工具。 */
        private final SolonClawPatchTools patchTools;

        /** 记录安全执行Code中的终端技能。 */
        private final SolonClawShellSkill shellSkill;

        /** 记录安全执行Code中的websearch工具。 */
        private final SolonClawWebTools.SafeWebsearchTool websearchTool;

        /** 记录安全执行Code中的webfetch工具。 */
        private final SolonClawWebTools.SafeWebfetchTool webfetchTool;

        /**
         * 创建Safe执行Code工具实例，并注入运行所需依赖。
         *
         * @param workDir 命令执行工作目录。
         * @param pythonCommand python命令参数。
         * @param securityPolicyService 安全策略服务依赖。
         * @param appConfig 应用运行配置。
         */
        public SafeExecuteCodeTool(
                String workDir,
                String pythonCommand,
                SecurityPolicyService securityPolicyService,
                AppConfig appConfig) {
            this(workDir, pythonCommand, securityPolicyService, appConfig, null, null);
        }

        /**
         * 创建Safe执行Code工具实例，并注入运行所需依赖。
         *
         * @param workDir 命令执行工作目录。
         * @param pythonCommand python命令参数。
         * @param securityPolicyService 安全策略服务依赖。
         * @param appConfig 应用运行配置。
         * @param websearchTool websearch工具参数。
         * @param webfetchTool webfetch工具参数。
         */
        SafeExecuteCodeTool(
                String workDir,
                String pythonCommand,
                SecurityPolicyService securityPolicyService,
                AppConfig appConfig,
                SolonClawWebTools.SafeWebsearchTool websearchTool,
                SolonClawWebTools.SafeWebfetchTool webfetchTool) {
            this.workDir = TerminalPathSupport.checkedWorkDir(workDir);
            this.pythonCommand = StrUtil.blankToDefault(pythonCommand, defaultPythonCommand());
            this.securityPolicyService = securityPolicyService;
            this.appConfig = appConfig;
            this.fileStateTracker = new SolonClawFileStateTracker();
            this.fileSkill =
                    new SolonClawFileReadWriteSkill(
                            this.workDir, securityPolicyService, appConfig, fileStateTracker);
            this.patchTools =
                    new SolonClawPatchTools(this.workDir, securityPolicyService, fileStateTracker);
            this.shellSkill =
                    new SolonClawShellSkill(
                            this.workDir, appConfig, securityPolicyService, new ProcessRegistry());
            this.websearchTool =
                    websearchTool == null
                            ? new SolonClawWebTools.SafeWebsearchTool(
                                    securityPolicyService,
                                    new org.noear.solon.ai.talents.web.WebsearchTalent(),
                                    appConfig)
                            : websearchTool;
            this.webfetchTool =
                    webfetchTool == null
                            ? new SolonClawWebTools.SafeWebfetchTool(securityPolicyService)
                            : webfetchTool;
        }

        /**
         * 执行Code。
         *
         * @param code code 参数。
         * @param timeoutSeconds 超时时间，单位为秒。
         * @return 返回Code结果。
         */
        @ToolMapping(
                name = "execute_code",
                description =
                        "Run a Python script and return a structured JSON result. The solonclaw_tools module exposes websearch, webfetch, file_read/read_file, file_write/write_file, search_files, patch and terminal for multi-step local processing.")
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
            AtomicInteger toolCallsMade = new AtomicInteger(0);
            try {
                if (StrUtil.isBlank(code)) {
                    return executeCodeError(
                            "Python code must be a non-empty string.",
                            toolCallsMade.get(),
                            started);
                }
                assertSafeExecuteCodeScript(code, securityPolicyService);

                Path staging =
                        Files.createTempDirectory(new File(workDir).toPath(), "execute_code_");
                try {
                    Path rpcDir = staging.resolve("rpc");
                    Files.createDirectories(rpcDir);
                    writeSolonClawToolsStub(staging.resolve("solonclaw_tools.py"));
                    Path script = staging.resolve("script.py");
                    Files.write(script, StrUtil.nullToEmpty(code).getBytes(StandardCharsets.UTF_8));
                    ProcessBuilder builder =
                            new ProcessBuilder(Arrays.asList(pythonCommand, script.toString()));
                    builder.directory(new File(workDir));
                    builder.redirectErrorStream(false);
                    configureSandboxEnvironment(builder.environment(), staging);
                    builder.environment().put("SOLONCLAW_RPC_DIR", rpcDir.toString());
                    Process process = builder.start();
                    process.getOutputStream().close();
                    AtomicBoolean rpcAccepting = new AtomicBoolean(true);
                    CompletableFuture<Void> rpcFuture =
                            CompletableFuture.runAsync(
                                    () -> runRpcLoop(rpcDir, toolCallsMade, rpcAccepting));
                    CompletableFuture<String> stdout =
                            CompletableFuture.supplyAsync(
                                    () ->
                                            readProcessText(
                                                    process.getInputStream(), maxStdoutChars()));
                    CompletableFuture<String> stderr =
                            CompletableFuture.supplyAsync(
                                    () ->
                                            readProcessText(
                                                    process.getErrorStream(), MAX_STDERR_CHARS));

                    boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                    String status = "success";
                    if (!finished) {
                        process.destroyForcibly();
                        status = "timeout";
                    }
                    rpcAccepting.set(false);
                    try {
                        rpcFuture.get(3, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.debug(
                                "execute_code RPC loop did not stop cleanly: {}",
                                safeErrorText(e));
                    }
                    String stdoutText =
                            cleanOutput(stdout.get(3, TimeUnit.SECONDS), maxStdoutChars());
                    String stderrText =
                            cleanOutput(stderr.get(3, TimeUnit.SECONDS), MAX_STDERR_CHARS);
                    int exitCode = finished ? process.exitValue() : -1;
                    Map<String, Object> result =
                            baseExecuteCodeResult(status, stdoutText, toolCallsMade.get(), started);
                    if ("timeout".equals(status)) {
                        String timeoutMessage =
                                "Script timed out after " + timeout + "s and was killed.";
                        result.put("error", timeoutMessage);
                        result.put(
                                "output",
                                StrUtil.isBlank(stdoutText)
                                        ? timeoutMessage
                                        : stdoutText + "\n\n" + timeoutMessage);
                    } else if (exitCode != 0) {
                        result.put("status", "error");
                        result.put("process_status", "error");
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
                        toolCallsMade.get(),
                        started);
            }
        }

        /**
         * 执行基础执行Code结果相关逻辑。
         *
         * @param status 状态参数。
         * @param output 命令执行输出文本。
         * @param toolCallsMade 工具CallsMade参数。
         * @param started started 参数。
         * @return 返回base执行Code结果。
         */
        private Map<String, Object> baseExecuteCodeResult(
                String status, String output, int toolCallsMade, long started) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", status);
            result.put("process_status", status);
            result.put("output", output);
            result.put("tool_calls_made", Integer.valueOf(toolCallsMade));
            result.put("duration_seconds", Double.valueOf(durationSeconds(started)));
            return result;
        }

        /**
         * 执行Code Error。
         *
         * @param error 错误参数。
         * @param toolCallsMade 工具CallsMade参数。
         * @param started started 参数。
         * @return 返回Code Error结果。
         */
        private String executeCodeError(String error, int toolCallsMade, long started) {
            Map<String, Object> result = baseExecuteCodeResult("error", "", toolCallsMade, started);
            result.put(
                    "error",
                    cleanOutput(SecretRedactor.redact(error, MAX_STDERR_CHARS), MAX_STDERR_CHARS));
            return ONode.serialize(result);
        }

        /**
         * 执行configureSandboxEnvironment相关逻辑。
         *
         * @param env 环境变量参数。
         * @param staging staging 参数。
         */
        private void configureSandboxEnvironment(Map<String, String> env, Path staging) {
            SubprocessEnvironmentSanitizer.sanitize(env, appConfig);
            String existingPythonPath = env.get("PYTHONPATH");
            env.put(
                    "PYTHONPATH",
                    existingPythonPath == null || existingPythonPath.length() == 0
                            ? staging.toString()
                            : staging.toString() + File.pathSeparator + existingPythonPath);
            env.put("PYTHONIOENCODING", "UTF-8");
            env.put("PYTHONDONTWRITEBYTECODE", "1");
        }

        /**
         * 规范化Timeout。
         *
         * @param timeoutSeconds 超时时间，单位为秒。
         * @return 返回Timeout结果。
         */
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

        /**
         * 执行maxStdoutChars相关逻辑。
         *
         * @return 返回max Stdout Chars结果。
         */
        private int maxStdoutChars() {
            int value = DEFAULT_MAX_STDOUT_CHARS;
            if (appConfig != null && appConfig.getTask() != null) {
                value = appConfig.getTask().getToolOutputInlineLimit();
            }
            return Math.max(256, value);
        }

        /**
         * 清理输出。
         *
         * @param text 待处理文本。
         * @param maxChars maxChars 参数。
         * @return 返回clean输出结果。
         */
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
            return SecretRedactor.redact(TerminalAnsiSanitizer.stripAnsi(value));
        }

        /**
         * 执行durationSeconds相关逻辑。
         *
         * @param started started 参数。
         * @return 返回duration Seconds结果。
         */
        private double durationSeconds(long started) {
            double value = (System.nanoTime() - started) / 1000000000.0d;
            return Math.round(value * 100.0d) / 100.0d;
        }

        /**
         * 写入Solon项目工具Stub。
         *
         * @param target target 参数。
         */
        private void writeSolonClawToolsStub(Path target) throws Exception {
            String source =
                    "import json, os, shlex, time\n"
                            + "\n"
                            + "_seq = 0\n"
                            + "_rpc_dir = os.environ.get('SOLONCLAW_RPC_DIR')\n"
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
                            + "def _call(tool_name, args):\n"
                            + "    global _seq\n"
                            + "    if not _rpc_dir:\n"
                            + "        raise RuntimeError('SOLONCLAW_RPC_DIR is not configured')\n"
                            + "    _seq += 1\n"
                            + "    seq = '%06d' % _seq\n"
                            + "    req = os.path.join(_rpc_dir, 'req_' + seq + '.json')\n"
                            + "    res = os.path.join(_rpc_dir, 'res_' + seq + '.json')\n"
                            + "    tmp = req + '.tmp'\n"
                            + "    with open(tmp, 'w', encoding='utf-8') as f:\n"
                            + "        json.dump({'seq': _seq, 'tool': tool_name, 'args': args}, f, ensure_ascii=False)\n"
                            + "    os.replace(tmp, req)\n"
                            + "    deadline = time.time() + 300\n"
                            + "    while time.time() < deadline:\n"
                            + "        if os.path.exists(res):\n"
                            + "            with open(res, 'r', encoding='utf-8') as f:\n"
                            + "                raw = f.read()\n"
                            + "            try:\n"
                            + "                os.remove(res)\n"
                            + "            except OSError:\n"
                            + "                pass\n"
                            + "            return json.loads(raw, strict=False)\n"
                            + "        time.sleep(0.05)\n"
                            + "    raise TimeoutError('Timed out waiting for ' + tool_name + ' response')\n"
                            + "\n"
                            + "def _unavailable(name):\n"
                            + "    raise RuntimeError(name + ' is not available in solonclaw execute_code yet. Use normal tool calls instead.')\n"
                            + "\n"
                            + "def websearch(query, limit=5): return _call('websearch', {'query': query, 'limit': limit})\n"
                            + "def webfetch(url, format='markdown', timeout=120): return _call('webfetch', {'url': url, 'format': format, 'timeout': timeout})\n"
                            + "def file_read(path, offset=1, limit=500): return _call('file_read', {'path': path, 'offset': offset, 'limit': limit})\n"
                            + "def read_file(path, offset=1, limit=500): return _call('read_file', {'path': path, 'offset': offset, 'limit': limit})\n"
                            + "def file_write(path, content): return _call('file_write', {'path': path, 'content': content})\n"
                            + "def write_file(path, content): return _call('write_file', {'path': path, 'content': content})\n"
                            + "def search_files(pattern, target='content', path='.', file_glob=None, limit=50, offset=0, output_mode='content', context=0): return _call('search_files', {'pattern': pattern, 'target': target, 'path': path, 'file_glob': file_glob, 'limit': limit, 'offset': offset, 'output_mode': output_mode, 'context': context})\n"
                            + "def patch(path=None, old_string=None, new_string=None, replace_all=False, mode='replace', patch=None): return _call('patch', {'path': path, 'old_string': old_string, 'new_string': new_string, 'replace_all': replace_all, 'mode': mode, 'patch': patch})\n"
                            + "def terminal(command, timeout=None, workdir=None, **kwargs): return _call('terminal', {'command': command, 'timeout': timeout, 'workdir': workdir})\n";
            Files.write(target, source.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * 运行Rpc循环。
         *
         * @param rpcDir 文件或目录路径参数。
         * @param toolCallsMade 工具CallsMade参数。
         * @param accepting accepting 参数。
         */
        private void runRpcLoop(Path rpcDir, AtomicInteger toolCallsMade, AtomicBoolean accepting) {
            while (accepting.get() || hasPendingRequests(rpcDir)) {
                try {
                    List<Path> requests = listRequestFiles(rpcDir);
                    if (requests.isEmpty()) {
                        Thread.sleep(50L);
                        continue;
                    }
                    for (Path request : requests) {
                        handleRpcRequest(rpcDir, request, toolCallsMade);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.debug("execute_code RPC loop iteration failed: {}", safeErrorText(ex));
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /**
         * 执行Rpc请求相关逻辑。
         *
         * @param rpcDir 文件或目录路径参数。
         * @param request 当前请求对象。
         * @param toolCallsMade 工具CallsMade参数。
         */
        private void handleRpcRequest(Path rpcDir, Path request, AtomicInteger toolCallsMade) {
            String response;
            int seq = 0;
            try {
                Map<String, Object> payload =
                        castMap(
                                ONode.deserialize(
                                        new String(
                                                Files.readAllBytes(request),
                                                StandardCharsets.UTF_8),
                                        Object.class));
                seq = getInt(payload, "seq", extractSeq(request));
                String toolName = getString(payload, "tool", "");
                Map<String, Object> args = castMap(payload.get("args"));
                if (toolCallsMade.get() >= 50) {
                    response =
                            ONode.serialize(
                                    errorMap(
                                            "Tool call limit reached (50). No more tool calls allowed in this execution."));
                } else {
                    response = dispatchRpcTool(toolName, args);
                    toolCallsMade.incrementAndGet();
                }
            } catch (Exception e) {
                response = ONode.serialize(errorMap(safeErrorText(e)));
                seq = extractSeq(request);
            }
            try {
                Path result = rpcDir.resolve(String.format(Locale.ROOT, "res_%06d.json", seq));
                Path temp = rpcDir.resolve(result.getFileName().toString() + ".tmp");
                Files.write(temp, response.getBytes(StandardCharsets.UTF_8));
                Files.move(temp, result, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(request);
            } catch (Exception e) {
                log.warn(
                        "execute_code RPC response write failed for request {}, error={}",
                        request == null ? "<null>" : request.getFileName(),
                        safeErrorText(e));
            }
        }

        /**
         * 分发Rpc工具。
         *
         * @param toolName 工具名称。
         * @param args 工具或命令参数。
         * @return 返回Rpc工具结果。
         */
        private String dispatchRpcTool(String toolName, Map<String, Object> args) {
            try {
                ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool(toolName);
                if ("read_file".equals(toolName) || "file_read".equals(toolName)) {
                    return normalizeToolResult(
                            fileSkill.read(
                                    getString(args, "path", null),
                                    Integer.valueOf(getInt(args, "offset", 1)),
                                    Integer.valueOf(getInt(args, "limit", 500))));
                }
                if ("write_file".equals(toolName) || "file_write".equals(toolName)) {
                    return normalizeToolResult(
                            fileSkill.write(
                                    getString(args, "path", null), getString(args, "content", "")));
                }
                if ("patch".equals(toolName)) {
                    return normalizeToolResult(
                            patchTools.patch(
                                    getString(args, "mode", "replace"),
                                    getString(args, "path", null),
                                    getString(args, "old_string", null),
                                    getString(args, "new_string", null),
                                    Boolean.valueOf(getBoolean(args, "replace_all", false)),
                                    getString(args, "patch", null)));
                }
                if ("terminal".equals(toolName)) {
                    return normalizeToolResult(
                            shellSkill.terminal(
                                    getString(args, "command", null),
                                    Boolean.FALSE,
                                    Integer.valueOf(getInt(args, "timeout", 180)),
                                    getString(args, "workdir", null),
                                    Boolean.FALSE));
                }
                if ("search_files".equals(toolName)) {
                    return rpcJson(searchFiles(args));
                }
                if ("websearch".equals(toolName)) {
                    return rpcJson(webSearch(args));
                }
                if ("webfetch".equals(toolName)) {
                    return rpcJson(webFetch(args));
                }
                return rpcJson(
                        errorMap(
                                "Tool '"
                                        + toolName
                                        + "' is not available in execute_code. Available: patch, read_file, search_files, terminal, webfetch, websearch, write_file"));
            } catch (Exception e) {
                return rpcJson(errorMap(safeErrorText(e)));
            }
        }

        /**
         * 规范化工具结果。
         *
         * @param result 结果响应或执行结果。
         * @return 返回工具结果。
         */
        private String normalizeToolResult(String result) {
            String value = StrUtil.nullToEmpty(result);
            try {
                Object parsed = ONode.deserialize(value, Object.class);
                if (parsed instanceof Map) {
                    Map<String, Object> map = castMap(parsed);
                    ensureStatusField(map);
                    ensureOutputField(map);
                    return rpcJson(map);
                }
                if (parsed instanceof List) {
                    return rpcJson(parsed);
                }
            } catch (Exception e) {
                log.debug(
                        "execute_code RPC tool result is not structured JSON; wrapping as text: {}",
                        safeErrorText(e));
            }
            Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
            wrapped.put("output", value);
            wrapped.put("result", value);
            wrapped.put("status", "success");
            return rpcJson(wrapped);
        }

        /**
         * 执行rpcJSON相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回rpc JSON结果。
         */
        @SuppressWarnings("unchecked")
        private String rpcJson(Object value) {
            return ONode.serialize(sanitizeRpcValue(value));
        }

        /**
         * 清理Rpc Value。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回Rpc Value结果。
         */
        @SuppressWarnings("unchecked")
        private Object sanitizeRpcValue(Object value) {
            if (value instanceof String) {
                return SecretRedactor.redact((String) value, 4000);
            }
            if (value instanceof Map) {
                Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    Object key = entry.getKey();
                    sanitized.put(
                            key == null ? "" : SecretRedactor.redact(String.valueOf(key), 400),
                            sanitizeRpcValue(entry.getValue()));
                }
                return sanitized;
            }
            if (value instanceof List) {
                List<Object> sanitized = new ArrayList<Object>();
                for (Object item : (List<Object>) value) {
                    sanitized.add(sanitizeRpcValue(item));
                }
                return sanitized;
            }
            return value;
        }

        /**
         * 搜索Files。
         *
         * @param args 工具或命令参数。
         * @return 返回Files结果。
         */
        private Map<String, Object> searchFiles(Map<String, Object> args) throws Exception {
            String pattern = getString(args, "pattern", "");
            String target = getString(args, "target", "content");
            String relativePath = StrUtil.blankToDefault(getString(args, "path", "."), ".");
            String fileGlob = getString(args, "file_glob", null);
            int limit = Math.max(1, Math.min(getInt(args, "limit", 50), 200));
            int offset = Math.max(0, getInt(args, "offset", 0));
            if (StrUtil.isBlank(pattern)) {
                return errorMap("pattern is required");
            }
            assertSearchPathSafe(relativePath);
            Path base = resolveContainedPath(relativePath);
            if (!Files.exists(base)) {
                return errorMap("path does not exist: " + safePath(relativePath));
            }
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            List<Path> files = listFiles(base);
            PathMatcher matcher =
                    StrUtil.isBlank(fileGlob)
                            ? null
                            : Paths.get(workDir).getFileSystem().getPathMatcher("glob:" + fileGlob);
            int skipped = 0;
            for (Path file : files) {
                Path rel = Paths.get(workDir).toAbsolutePath().normalize().relativize(file);
                String relText = rel.toString().replace('\\', '/');
                if (isExecuteCodeStagingPath(relText)) {
                    continue;
                }
                if (matcher != null && !matcher.matches(rel)) {
                    continue;
                }
                if ("files".equalsIgnoreCase(target)) {
                    if (!relText.toLowerCase(Locale.ROOT)
                            .contains(pattern.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    if (skipped++ < offset) {
                        continue;
                    }
                    matches.add(matchMap(relText, null, null));
                } else {
                    String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    int index =
                            content.toLowerCase(Locale.ROOT)
                                    .indexOf(pattern.toLowerCase(Locale.ROOT));
                    if (index < 0) {
                        continue;
                    }
                    if (skipped++ < offset) {
                        continue;
                    }
                    matches.add(
                            matchMap(
                                    relText,
                                    Integer.valueOf(lineNumber(content, index)),
                                    previewLine(content, index)));
                }
                if (matches.size() >= limit) {
                    break;
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("matches", matches);
            result.put("count", Integer.valueOf(matches.size()));
            result.put("offset", Integer.valueOf(offset));
            result.put("limit", Integer.valueOf(limit));
            return result;
        }

        /**
         * 执行Web搜索相关逻辑。
         *
         * @param args 工具或命令参数。
         * @return 返回Web搜索结果。
         */
        private Map<String, Object> webSearch(Map<String, Object> args) throws Exception {
            String query = getString(args, "query", "");
            int limit = Math.max(1, Math.min(getInt(args, "limit", 5), 20));
            if (StrUtil.isBlank(query)) {
                return errorMap("query is required");
            }
            Document doc =
                    websearchTool.websearch(
                            query,
                            Integer.valueOf(limit),
                            "fallback",
                            "auto",
                            Integer.valueOf(maxStdoutChars()));
            Map<String, Object> result = normalizeWebSearchDocument(doc, query, limit);
            result.put("status", "success");
            return result;
        }

        /**
         * 执行WebFetch相关逻辑。
         *
         * @param args 工具或命令参数。
         * @return 返回Web Fetch结果。
         */
        private Map<String, Object> webFetch(Map<String, Object> args) throws Exception {
            String url = getString(args, "url", "");
            if (StrUtil.isBlank(url)) {
                return errorMap("url is required");
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("url", url);
            try {
                approveManagedWebfetchUrl(url);
                Document doc =
                        webfetchTool.webfetch(
                                url,
                                getString(args, "format", "markdown"),
                                Integer.valueOf(getInt(args, "timeout", 120)));
                result.put("title", StrUtil.blankToDefault(doc == null ? null : doc.getTitle(), url));
                result.put("content", doc == null ? "" : StrUtil.nullToEmpty(doc.getContent()));
                result.put("error", null);
                result.put("status", "success");
            } catch (Exception e) {
                result.put("title", url);
                result.put("content", "");
                result.put("error", safeErrorText(e));
                result.put("status", "error");
            }
            return result;
        }

        /**
         * 为 execute_code 托管 webfetch 的单次 URL 调用预置外部网络审批。
         *
         * @param url 待获取的 URL。
         */
        private void approveManagedWebfetchUrl(String url) {
            if (securityPolicyService == null) {
                return;
            }
            SecurityPolicyService.UrlVerdict hardBoundary =
                    securityPolicyService.checkReturnedUrl(url);
            if (hardBoundary.isAllowed()) {
                SecurityPolicyService.approveUrlPolicyForCurrentThread(
                        "network_external_operation", url);
            }
        }

        /**
         * 规范化Web搜索Document。
         *
         * @param doc doc 参数。
         * @param query 查询参数。
         * @param limit 最大返回数量。
         * @return 返回Web搜索Document结果。
         */
        private Map<String, Object> normalizeWebSearchDocument(
                Document doc, String query, int limit) {
            String content = doc == null ? "" : StrUtil.nullToEmpty(doc.getContent());
            try {
                Object parsed = ONode.deserialize(content, Object.class);
                Map<String, Object> parsedMap = castMap(parsed);
                if (!parsedMap.isEmpty()) {
                    List<Map<String, Object>> web = extractWebResults(parsedMap, limit);
                    if (!web.isEmpty()) {
                        return webSearchResult(web);
                    }
                }
            } catch (Exception e) {
                log.debug(
                        "execute_code websearch document is not structured JSON; using fallback item: {}",
                        safeErrorText(e));
            }
            List<Map<String, Object>> web = new ArrayList<Map<String, Object>>();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("url", doc == null ? "" : StrUtil.nullToEmpty(doc.getUrl()));
            item.put(
                    "title",
                    doc == null
                            ? "Web search: " + query
                            : StrUtil.blankToDefault(doc.getTitle(), "Web search: " + query));
            item.put("description", content);
            web.add(item);
            return webSearchResult(web);
        }

        /**
         * 执行Web搜索结果相关逻辑。
         *
         * @param web Web参数。
         * @return 返回Web搜索结果。
         */
        private Map<String, Object> webSearchResult(List<Map<String, Object>> web) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("data", data);
            return result;
        }

        /**
         * 提取Web Results。
         *
         * @param parsed parsed 参数。
         * @param limit 最大返回数量。
         * @return 返回Web Results结果。
         */
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> extractWebResults(Map<String, Object> parsed, int limit) {
            Object raw = null;
            Object data = parsed.get("data");
            if (data instanceof Map) {
                raw = ((Map<String, Object>) data).get("web");
            }
            if (raw == null) {
                raw = parsed.get("web");
            }
            if (raw == null) {
                raw = parsed.get("results");
            }
            List<Map<String, Object>> web = new ArrayList<Map<String, Object>>();
            if (raw instanceof List) {
                for (Object value : (List<Object>) raw) {
                    if (web.size() >= limit) {
                        break;
                    }
                    Map<String, Object> one = castMap(value);
                    if (one.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("url", firstString(one, "url", "link"));
                    item.put("title", firstString(one, "title", "name"));
                    item.put(
                            "description",
                            firstString(one, "description", "snippet", "content", "text"));
                    web.add(item);
                }
            }
            return web;
        }

        /**
         * 执行assert搜索路径安全相关逻辑。
         *
         * @param path 文件或目录路径。
         */
        private void assertSearchPathSafe(String path) {
            if (securityPolicyService == null) {
                return;
            }
            Map<String, Object> fileArgs = new LinkedHashMap<String, Object>();
            fileArgs.put("dirName", path);
            fileArgs.put("fileName", path);
            SecurityPolicyService.FileVerdict verdict =
                    securityPolicyService.checkFileToolArgs(ToolNameConstants.FILE_LIST, fileArgs);
            if (!verdict.isAllowed()) {
                throw new IllegalArgumentException(
                        blockedFileMessage(ToolNameConstants.FILE_LIST, verdict));
            }
        }

        /**
         * 解析Contained路径。
         *
         * @param path 文件或目录路径。
         * @return 返回解析后的Contained路径。
         */
        private Path resolveContainedPath(String path) throws Exception {
            Path root = Paths.get(workDir).toAbsolutePath().normalize();
            Path resolved = root.resolve(StrUtil.blankToDefault(path, ".")).normalize();
            Path realRoot = root.toRealPath();
            Path real =
                    Files.exists(resolved)
                            ? resolved.toRealPath()
                            : resolved.toAbsolutePath().normalize();
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException("path escapes workspace: " + safePath(path));
            }
            return resolved;
        }

        /**
         * 生成安全展示用的路径。
         *
         * @param path 文件或目录路径。
         * @return 返回safe路径。
         */
        private String safePath(String path) {
            return ToolWorkspacePathSupport.safePath(path);
        }

        /**
         * 列出Files。
         *
         * @param base 基础参数。
         * @return 返回Files列表。
         */
        private List<Path> listFiles(Path base) throws Exception {
            if (Files.isRegularFile(base)) {
                return Collections.singletonList(base);
            }
            final List<Path> files = new ArrayList<Path>();
            Files.walk(base).filter(Files::isRegularFile).limit(10000).forEach(files::add);
            return files;
        }

        /**
         * 执行match映射相关逻辑。
         *
         * @param path 文件或目录路径。
         * @param line 行参数。
         * @param preview 预览参数。
         * @return 返回match Map结果。
         */
        private Map<String, Object> matchMap(String path, Integer line, String preview) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("path", path);
            if (line != null) {
                item.put("line", line);
            }
            if (preview != null) {
                item.put("preview", SecretRedactor.redact(preview, 1000));
            }
            return item;
        }

        /**
         * 执行行Number相关逻辑。
         *
         * @param content 待处理内容。
         * @param index 索引参数。
         * @return 返回line Number结果。
         */
        private int lineNumber(String content, int index) {
            int line = 1;
            for (int i = 0; i < index && i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        /**
         * 执行预览行相关逻辑。
         *
         * @param content 待处理内容。
         * @param index 索引参数。
         * @return 返回preview Line结果。
         */
        private String previewLine(String content, int index) {
            int start = content.lastIndexOf('\n', Math.max(0, index));
            int end = content.indexOf('\n', index);
            if (start < 0) {
                start = 0;
            } else {
                start++;
            }
            if (end < 0) {
                end = content.length();
            }
            return content.substring(start, Math.min(end, start + 500)).trim();
        }

        /**
         * 判断是否存在Pending Requests。
         *
         * @param rpcDir 文件或目录路径参数。
         * @return 如果Pending Requests满足条件则返回 true，否则返回 false。
         */
        private boolean hasPendingRequests(Path rpcDir) {
            return !listRequestFiles(rpcDir).isEmpty();
        }

        /**
         * 判断是否执行Code Staging路径。
         *
         * @param relativePath 文件或目录路径参数。
         * @return 如果执行Code Staging路径满足条件则返回 true，否则返回 false。
         */
        private boolean isExecuteCodeStagingPath(String relativePath) {
            String path = StrUtil.nullToEmpty(relativePath).replace('\\', '/');
            return path.startsWith("execute_code_") || path.contains("/execute_code_");
        }

        /**
         * 确保状态Field。
         *
         * @param map 待读取的映射对象。
         */
        private void ensureStatusField(Map<String, Object> map) {
            if (map == null || map.containsKey("status")) {
                return;
            }
            Object error = map.get("error");
            if (error != null && StrUtil.isNotBlank(String.valueOf(error))) {
                map.put("status", "error");
            }
        }

        /**
         * 确保RPC工具结果拥有Python侧易用的output字段。
         *
         * @param map 待读取的映射对象。
         */
        private void ensureOutputField(Map<String, Object> map) {
            if (map == null || map.containsKey("output")) {
                return;
            }
            Object summary = map.get("summary");
            if (summary != null && StrUtil.isNotBlank(String.valueOf(summary))) {
                map.put("output", String.valueOf(summary));
                return;
            }
            Object preview = map.get("preview");
            if (preview != null && StrUtil.isNotBlank(String.valueOf(preview))) {
                map.put("output", String.valueOf(preview));
                return;
            }
            Object error = map.get("error");
            if (error != null && StrUtil.isNotBlank(String.valueOf(error))) {
                map.put("output", String.valueOf(error));
            }
        }

        /**
         * 列出请求Files。
         *
         * @param rpcDir 文件或目录路径参数。
         * @return 返回请求Files列表。
         */
        private List<Path> listRequestFiles(Path rpcDir) {
            try {
                List<Path> files = new ArrayList<Path>();
                Files.newDirectoryStream(rpcDir, "req_*.json").forEach(files::add);
                Collections.sort(files);
                return files;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        /**
         * 执行cast映射相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回cast Map结果。
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> castMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return new LinkedHashMap<String, Object>();
        }

        /**
         * 提取Seq。
         *
         * @param request 当前请求对象。
         * @return 返回Seq结果。
         */
        private int extractSeq(Path request) {
            String name = request.getFileName().toString();
            try {
                return Integer.parseInt(name.replace("req_", "").replace(".json", ""));
            } catch (Exception e) {
                return 0;
            }
        }

        /**
         * 执行错误映射相关逻辑。
         *
         * @param error 错误参数。
         * @return 返回error Map结果。
         */
        private Map<String, Object> errorMap(String error) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("error", safeText(StrUtil.blankToDefault(error, "Tool execution failed")));
            result.put("status", "error");
            return result;
        }

        /**
         * 生成安全展示用的错误文本。
         *
         * @param e 捕获到的异常。
         * @return 返回safe Error Text结果。
         */
        private String safeErrorText(Exception e) {
            if (e == null) {
                return "Tool execution failed";
            }
            return safeText(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }

        /**
         * 生成安全展示用的文本。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回safe Text结果。
         */
        private String safeText(String value) {
            return SecretRedactor.redact(value, 1000);
        }

        /**
         * 读取String。
         *
         * @param map 待读取的映射对象。
         * @param key 配置键或映射键。
         * @param defaultValue 默认值参数。
         * @return 返回读取到的String。
         */
        private String getString(Map<String, Object> map, String key, String defaultValue) {
            Object value = map == null ? null : map.get(key);
            return value == null ? defaultValue : String.valueOf(value);
        }

        /**
         * 读取Int。
         *
         * @param map 待读取的映射对象。
         * @param key 配置键或映射键。
         * @param defaultValue 默认值参数。
         * @return 返回读取到的Int。
         */
        private int getInt(Map<String, Object> map, String key, int defaultValue) {
            Object value = map == null ? null : map.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception e) {
                return defaultValue;
            }
        }

        /**
         * 读取Boolean。
         *
         * @param map 待读取的映射对象。
         * @param key 配置键或映射键。
         * @param defaultValue 默认值参数。
         * @return 返回读取到的Boolean。
         */
        private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
            Object value = map == null ? null : map.get(key);
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
            if (value == null) {
                return defaultValue;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }

        /**
         * 执行first字符串相关逻辑。
         *
         * @param map 待读取的映射对象。
         * @param key1 key1 参数。
         * @param key2 key2 参数。
         * @return 返回first String结果。
         */
        private String firstString(Map<String, Object> map, String key1, String key2) {
            String value = getString(map, key1, null);
            return StrUtil.isBlank(value) ? getString(map, key2, "") : value;
        }

        /**
         * 执行first字符串相关逻辑。
         *
         * @param map 待读取的映射对象。
         * @param key1 key1 参数。
         * @param key2 key2 参数。
         * @param key3 key3 参数。
         * @param key4 key4 参数。
         * @return 返回first String结果。
         */
        private String firstString(
                Map<String, Object> map, String key1, String key2, String key3, String key4) {
            String value = getString(map, key1, null);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
            value = getString(map, key2, null);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
            value = getString(map, key3, null);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
            return getString(map, key4, "");
        }

        /**
         * 删除Quietly。
         *
         * @param path 文件或目录路径。
         */
        private void deleteQuietly(Path path) {
            if (path == null || !Files.exists(path)) {
                return;
            }
            try {
                if (Files.isDirectory(path)) {
                    try (java.util.stream.Stream<Path> children = Files.list(path)) {
                        children.forEach(
                                child -> {
                                    deleteQuietly(child);
                                });
                    }
                }
                Files.deleteIfExists(path);
            } catch (Exception e) {
                log.debug(
                        "execute_code staging cleanup failed for {}, error={}",
                        path,
                        safeErrorText(e));
            }
        }
    }

    /** 承载安全Python技能相关状态和辅助逻辑。 */
    public static class SafePythonSkill extends PythonTalent {
        /** 注入安全策略服务，用于调用对应业务能力。 */
        private final SecurityPolicyService securityPolicyService;

        /**
         * 创建Safe Python技能实例，并注入运行所需依赖。
         *
         * @param workDir 命令执行工作目录。
         * @param pythonCommand python命令参数。
         * @param securityPolicyService 安全策略服务依赖。
         */
        public SafePythonSkill(
                String workDir, String pythonCommand, SecurityPolicyService securityPolicyService) {
            super(workDir, pythonCommand);
            this.securityPolicyService = securityPolicyService;
        }

        /**
         * 执行当前回调或工具调用。
         *
         * @param code code 参数。
         * @param timeout 超时时间或等待上限。
         * @return 返回执行结果。
         */
        @Override
        @ToolMapping(name = "execute_python", description = "执行 Python 代码，并返回标准输出。")
        public String execute(
                @Param("code") String code,
                @Param(
                                name = "timeout",
                                required = false,
                                defaultValue = "120000",
                                description = "可选超时时间，单位为毫秒")
                        Integer timeout) {
            assertSafe(ToolNameConstants.EXECUTE_PYTHON, code, securityPolicyService);
            return SecretRedactor.redact(super.execute(code, timeout), 20000);
        }
    }

    /** 承载安全Nodejs技能相关状态和辅助逻辑。 */
    public static class SafeNodejsSkill extends NodejsTalent {
        /** 注入安全策略服务，用于调用对应业务能力。 */
        private final SecurityPolicyService securityPolicyService;

        /**
         * 创建Safe Nodejs技能实例，并注入运行所需依赖。
         *
         * @param workDir 命令执行工作目录。
         * @param securityPolicyService 安全策略服务依赖。
         */
        public SafeNodejsSkill(String workDir, SecurityPolicyService securityPolicyService) {
            super(workDir);
            this.securityPolicyService = securityPolicyService;
        }

        /**
         * 执行当前回调或工具调用。
         *
         * @param code code 参数。
         * @param timeout 超时时间或等待上限。
         * @return 返回执行结果。
         */
        @Override
        @ToolMapping(name = "execute_js", description = "执行 Node.js JavaScript 代码，并返回标准输出。")
        public String execute(
                @Param("code") String code,
                @Param(
                                name = "timeout",
                                required = false,
                                defaultValue = "120000",
                                description = "可选超时时间，单位为毫秒")
                        Integer timeout) {
            assertSafe(ToolNameConstants.EXECUTE_JS, code, securityPolicyService);
            return SecretRedactor.redact(super.execute(code, timeout), 20000);
        }
    }

    /**
     * 执行assert安全相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    static void assertSafe(
            String toolName, String code, SecurityPolicyService securityPolicyService) {
        assertSafe(toolName, toolName, code, securityPolicyService, true);
    }

    /**
     * 执行assert安全ForManagedBackground相关逻辑。
     *
     * @param toolName 工具名称。
     * @param code code 参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    static void assertSafeForManagedBackground(
            String toolName, String code, SecurityPolicyService securityPolicyService) {
        assertSafe(toolName, toolName, code, securityPolicyService, false);
    }

    /**
     * 执行assert安全With审批工具相关逻辑。
     *
     * @param approvalToolName 审批工具名称参数。
     * @param ruleToolName rule工具名称参数。
     * @param code code 参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    static void assertSafeWithApprovalTool(
            String approvalToolName,
            String ruleToolName,
            String code,
            SecurityPolicyService securityPolicyService) {
        assertSafe(approvalToolName, ruleToolName, code, securityPolicyService, true);
    }

    /**
     * 执行assert安全ForManagedBackgroundWith审批工具相关逻辑。
     *
     * @param approvalToolName 审批工具名称参数。
     * @param ruleToolName rule工具名称参数。
     * @param code code 参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    static void assertSafeForManagedBackgroundWithApprovalTool(
            String approvalToolName,
            String ruleToolName,
            String code,
            SecurityPolicyService securityPolicyService) {
        assertSafe(approvalToolName, ruleToolName, code, securityPolicyService, false);
    }

    /**
     * 执行assert安全相关逻辑。
     *
     * @param approvalToolName 审批工具名称参数。
     * @param ruleToolName rule工具名称参数。
     * @param code code 参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @param rejectForegroundPatterns reject前台进程Patterns参数。
     */
    private static void assertSafe(
            String approvalToolName,
            String ruleToolName,
            String code,
            SecurityPolicyService securityPolicyService,
            boolean rejectForegroundPatterns) {
        if (securityPolicyService != null) {
            AppConfig appConfig = appConfigFrom(securityPolicyService);
            if (isFileGuardrailEnabled(appConfig)) {
                SecurityPolicyService.FileVerdict fileVerdict =
                        securityPolicyService.checkCommandPaths(code);
                if (!fileVerdict.isAllowed()) {
                    if (fileVerdict.isApprovalRequired()) {
                        throw new IllegalArgumentException(
                                "APPROVAL_REQUIRED: "
                                        + fileVerdict.getMessage()
                                        + " path="
                                        + redactPath(fileVerdict.getPath(), 400)
                                        + "。请先在对话审批该单次操作。");
                    }
                    throw new IllegalArgumentException(
                            blockedFileMessage(approvalToolName, fileVerdict));
                }
            }
            if (isUrlGuardrailEnabled(appConfig)) {
                SecurityPolicyService.UrlVerdict urlVerdict =
                        securityPolicyService.checkCommandUrls(code);
                if (!urlVerdict.isAllowed()) {
                    if (urlVerdict.isApprovalRequired()) {
                        throw new IllegalArgumentException(
                                "APPROVAL_REQUIRED: "
                                        + urlVerdict.getMessage()
                                        + " url="
                                        + SecretRedactor.maskUrl(urlVerdict.getUrl())
                                        + "。请先在对话审批该单次操作。");
                    }
                    throw new IllegalArgumentException(blockedUrlMessage(urlVerdict));
                }
            }
        }

        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, appConfigFrom(securityPolicyService), securityPolicyService);
        DangerousCommandApprovalService.DetectionResult hardline =
                approvalService.detectHardline(ruleToolName, code);
        if (hardline != null) {
            throw new IllegalArgumentException(blockedHardlineMessage(approvalToolName, hardline));
        }
        if (rejectForegroundPatterns) {
            String foregroundGuidance =
                    approvalService.foregroundBackgroundGuidance(ruleToolName, code);
            if (foregroundGuidance != null) {
                throw new IllegalArgumentException(foregroundGuidance);
            }
        }
        if (isSoftDangerousRuleBypassEnabled(approvalService)) {
            return;
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(ruleToolName, code);
        if (dangerous != null) {
            if (DangerousCommandApprovalService.consumeCurrentThreadApproval(
                    approvalToolName, code)) {
                return;
            }
            throw new IllegalArgumentException(
                    blockedDangerousMessage(approvalToolName, dangerous));
        }
    }

    /**
     * 执行assert安全执行CodeScript相关逻辑。
     *
     * @param code code 参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    static void assertSafeExecuteCodeScript(
            String code, SecurityPolicyService securityPolicyService) {
        String scriptForPreflight =
                stripEscapedControlSequences(stripManagedFileToolPathLiterals(code));
        String scriptForUrlPreflight = stripManagedWebfetchUrlLiterals(code);
        if (securityPolicyService != null) {
            AppConfig appConfig = appConfigFrom(securityPolicyService);
            if (isFileGuardrailEnabled(appConfig)) {
                SecurityPolicyService.FileVerdict fileVerdict =
                        securityPolicyService.checkCommandPaths(scriptForPreflight);
                if (!fileVerdict.isAllowed()) {
                    throw new IllegalArgumentException(
                            blockedFileMessage(ToolNameConstants.EXECUTE_CODE, fileVerdict));
                }
            }
            if (isUrlGuardrailEnabled(appConfig)) {
                SecurityPolicyService.UrlVerdict urlVerdict =
                        securityPolicyService.checkCommandUrls(scriptForUrlPreflight);
                if (!urlVerdict.isAllowed()) {
                    throw new IllegalArgumentException(blockedUrlMessage(urlVerdict));
                }
            }
        }

        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, appConfigFrom(securityPolicyService), securityPolicyService);
        DangerousCommandApprovalService.DetectionResult hardline =
                approvalService.detectHardline(ToolNameConstants.EXECUTE_PYTHON, code);
        if (hardline != null) {
            throw new IllegalArgumentException(
                    blockedHardlineMessage(ToolNameConstants.EXECUTE_CODE, hardline));
        }
        String foregroundGuidance =
                approvalService.foregroundBackgroundGuidance(
                        ToolNameConstants.EXECUTE_PYTHON, code);
        if (foregroundGuidance != null) {
            throw new IllegalArgumentException(foregroundGuidance);
        }
        if (isSoftDangerousRuleBypassEnabled(approvalService)) {
            return;
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(ToolNameConstants.EXECUTE_PYTHON, code);
        if (dangerous != null) {
            if (DangerousCommandApprovalService.consumeCurrentThreadApproval(
                    ToolNameConstants.EXECUTE_CODE, code)) {
                return;
            }
            throw new IllegalArgumentException(
                    blockedDangerousMessage(ToolNameConstants.EXECUTE_CODE, dangerous));
        }
    }

    /**
     * 剥离Managed文件工具路径Literals。
     *
     * @param code code 参数。
     * @return 返回strip Managed文件工具路径Literals结果。
     */
    private static String stripManagedFileToolPathLiterals(String code) {
        String value = StrUtil.nullToEmpty(code);
        Matcher matcher = MANAGED_FILE_TOOL_CALL.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            String replacement =
                    matcher.group().replace(matcher.group(2), "__managed_file_tool_path__");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 剥离Managed webfetch工具URL Literals。
     *
     * @param code code 参数。
     * @return 返回剥离托管 webfetch URL 后的脚本文本。
     */
    private static String stripManagedWebfetchUrlLiterals(String code) {
        String value = StrUtil.nullToEmpty(code);
        Matcher matcher = MANAGED_WEBFETCH_TOOL_CALL.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            String replacement =
                    matcher.group().replace(matcher.group(2), "__managed_webfetch_url__");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 剥离 Python 字符串内的转义控制序列，避免路径预检把 ANSI 字面量误判成 Windows 绝对路径。
     *
     * @param code code 参数。
     * @return 返回剥离转义控制序列后的脚本文本。
     */
    private static String stripEscapedControlSequences(String code) {
        return PYTHON_ESCAPED_CONTROL_SEQUENCE
                .matcher(StrUtil.nullToEmpty(code))
                .replaceAll("__control__");
    }

    /**
     * 执行阻断文件消息相关逻辑。
     *
     * @param toolName 工具名称。
     * @param verdict 判定参数。
     * @return 返回blocked文件消息结果。
     */
    private static String blockedFileMessage(
            String toolName, SecurityPolicyService.FileVerdict verdict) {
        return "BLOCKED: 文件安全策略阻止访问："
                + verdict.getMessage()
                + "\n工具："
                + toolName
                + "\n路径："
                + redactPath(verdict.getPath(), 400)
                + "\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。";
    }

    /**
     * 脱敏代码执行安全拒绝消息中的敏感路径。
     *
     * @param path 原始路径。
     * @param maxLength 最大展示长度。
     * @return 返回脱敏后的路径。
     */
    private static String redactPath(String path, int maxLength) {
        return SecretRedactor.redactSensitivePaths(SecretRedactor.redact(path, maxLength));
    }

    /**
     * 执行阻断URL消息相关逻辑。
     *
     * @param verdict 判定参数。
     * @return 返回blocked URL消息结果。
     */
    private static String blockedUrlMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretRedactor.maskUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    /**
     * 执行阻断Hardline消息相关逻辑。
     *
     * @param toolName 工具名称。
     * @param detection detection 参数。
     * @return 返回blocked Hardline消息结果。
     */
    private static String blockedHardlineMessage(
            String toolName, DangerousCommandApprovalService.DetectionResult detection) {
        return "BLOCKED: 该 "
                + toolName
                + " 调用命中硬阻断安全规则："
                + StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey())
                + "。请改用更小、更可审计的安全操作。";
    }

    /**
     * 执行阻断Dangerous消息相关逻辑。
     *
     * @param toolName 工具名称。
     * @param detection detection 参数。
     * @return 返回blocked Dangerous消息结果。
     */
    private static String blockedDangerousMessage(
            String toolName, DangerousCommandApprovalService.DetectionResult detection) {
        return "BLOCKED: 该 "
                + toolName
                + " 调用命中危险命令安全规则："
                + StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey())
                + "。直接执行入口没有审批上下文，请改用可审批的 Agent 工具调用流程或拆成更安全的操作。";
    }

    /** 判断全局工具安全策略是否允许跳过可审批危险规则；hardline 和前台保护仍在此前执行。 */
    private static boolean isSoftDangerousRuleBypassEnabled(
            DangerousCommandApprovalService approvalService) {
        return approvalService != null && "bypass".equals(approvalService.guardrailMode());
    }

    /**
     * 读取进程Text。
     *
     * @param inputStream 输入流参数。
     * @param maxChars maxChars 参数。
     * @return 返回读取到的进程Text。
     */
    private static String readProcessText(java.io.InputStream inputStream, int maxChars) {
        try (InputStreamReader reader =
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
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
            return "系统失败: "
                    + SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            1000);
        }
    }

    /**
     * 返回代码执行工具默认使用的 Python 命令，供诊断探针和工具注册复用同一运行时选择。
     *
     * @return Windows 使用 python，其他平台优先使用 python3。
     */
    public static String defaultPythonCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "python"
                : "python3";
    }

    /**
     * 执行默认MaxStdoutChars相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回默认Max Stdout Chars结果。
     */
    private static int defaultMaxStdoutChars(AppConfig appConfig) {
        int value = DEFAULT_MAX_STDOUT_CHARS;
        if (appConfig != null && appConfig.getTask() != null) {
            value = appConfig.getTask().getToolOutputInlineLimit();
        }
        return Math.max(256, value);
    }

    /**
     * 执行应用配置From相关逻辑。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回app配置From结果。
     */
    private static AppConfig appConfigFrom(SecurityPolicyService securityPolicyService) {
        return securityPolicyService == null ? null : securityPolicyService.getAppConfig();
    }

    /** 判断文件路径预检是否启用；默认 strict，只有显式 bypass 才跳过。 */
    static boolean isFileGuardrailEnabled(AppConfig appConfig) {
        return !"bypass".equals(fileGuardrailMode(appConfig));
    }

    /** 判断 URL 预检是否启用；默认 strict，只有显式 bypass 才跳过。 */
    static boolean isUrlGuardrailEnabled(AppConfig appConfig) {
        return !"bypass".equals(urlGuardrailMode(appConfig));
    }

    /** 获取文件路径预检模式，避免空配置时改变默认安全行为。 */
    private static String fileGuardrailMode(AppConfig appConfig) {
        return appConfig == null || appConfig.getSecurity() == null
                ? "strict"
                : StrUtil.blankToDefault(appConfig.getSecurity().getFileGuardrailMode(), "strict")
                        .trim()
                        .toLowerCase(Locale.ROOT);
    }

    /** 获取 URL 预检模式，避免空配置时改变默认安全行为。 */
    private static String urlGuardrailMode(AppConfig appConfig) {
        return appConfig == null || appConfig.getSecurity() == null
                ? "strict"
                : StrUtil.blankToDefault(appConfig.getSecurity().getUrlGuardrailMode(), "strict")
                        .trim()
                        .toLowerCase(Locale.ROOT);
    }
}
