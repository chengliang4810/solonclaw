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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.skills.sys.NodejsSkill;
import org.noear.solon.ai.skills.sys.PythonSkill;

/** Solon AI code execution skills wrapped with local safety checks. */
public class SolonClawCodeExecutionSkills {
    private static final int DEFAULT_EXECUTE_CODE_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_MAX_STDOUT_CHARS = 50000;
    private static final int MAX_STDERR_CHARS = 10000;
    private static final String[] SAFE_ENV_PREFIXES =
            new String[] {
                "PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "LANG", "LC_", "TERM",
                "TMPDIR", "TMP", "TEMP", "SHELL", "LOGNAME", "XDG_", "PYTHONPATH",
                "VIRTUAL_ENV", "CONDA", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"
            };
    private static final String[] SECRET_ENV_SUBSTRINGS =
            new String[] {"KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"};

    private SolonClawCodeExecutionSkills() {}

    public static class SafeExecuteCodeTool {
        private final String workDir;
        private final String pythonCommand;
        private final SecurityPolicyService securityPolicyService;
        private final AppConfig appConfig;
        private final SolonClawFileStateTracker fileStateTracker;
        private final SolonClawFileReadWriteSkill fileSkill;
        private final SolonClawPatchTools patchTools;
        private final SolonClawShellSkill shellSkill;
        private final SolonClawWebTools.SafeWebsearchTool websearchTool;
        private final SolonClawWebTools.SafeWebfetchTool webfetchTool;

        public SafeExecuteCodeTool(
                String workDir,
                String pythonCommand,
                SecurityPolicyService securityPolicyService,
                AppConfig appConfig) {
            this(
                    workDir,
                    pythonCommand,
                    securityPolicyService,
                    appConfig,
                    null,
                    null);
        }

        SafeExecuteCodeTool(
                String workDir,
                String pythonCommand,
                SecurityPolicyService securityPolicyService,
                AppConfig appConfig,
                SolonClawWebTools.SafeWebsearchTool websearchTool,
                SolonClawWebTools.SafeWebfetchTool webfetchTool) {
            this.workDir = checkedWorkDir(workDir);
            this.pythonCommand = StrUtil.blankToDefault(pythonCommand, defaultPythonCommand());
            this.securityPolicyService = securityPolicyService;
            this.appConfig = appConfig;
            this.fileStateTracker = new SolonClawFileStateTracker();
            this.fileSkill =
                    new SolonClawFileReadWriteSkill(
                            this.workDir,
                            securityPolicyService,
                            maxFileReadLines(appConfig),
                            maxFileReadLineLength(appConfig),
                            fileStateTracker);
            this.patchTools = new SolonClawPatchTools(this.workDir, securityPolicyService, fileStateTracker);
            this.shellSkill =
                    new SolonClawShellSkill(
                            this.workDir,
                            appConfig,
                            securityPolicyService,
                            new ProcessRegistry());
            this.websearchTool =
                    websearchTool == null
                            ? new SolonClawWebTools.SafeWebsearchTool(securityPolicyService)
                            : websearchTool;
            this.webfetchTool =
                    webfetchTool == null
                            ? new SolonClawWebTools.SafeWebfetchTool(securityPolicyService)
                            : webfetchTool;
        }

        @ToolMapping(
                name = "execute_code",
                description =
                        "Run a Python script and return a structured JSON result. The solonclaw_tools module exposes web_search, web_extract, read_file, write_file, search_files, patch and terminal for multi-step local processing.")
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

                Path staging = Files.createTempDirectory(new File(workDir).toPath(), "execute_code_");
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
                    builder.environment().put("JIMUQU_RPC_DIR", rpcDir.toString());
                    Process process = builder.start();
                    process.getOutputStream().close();
                    AtomicBoolean rpcAccepting = new AtomicBoolean(true);
                    CompletableFuture<Void> rpcFuture =
                            CompletableFuture.runAsync(
                                    () -> runRpcLoop(rpcDir, toolCallsMade, rpcAccepting));
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
                    rpcAccepting.set(false);
                    try {
                        rpcFuture.get(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                    String stdoutText = cleanOutput(stdout.get(3, TimeUnit.SECONDS), maxStdoutChars());
                    String stderrText = cleanOutput(stderr.get(3, TimeUnit.SECONDS), MAX_STDERR_CHARS);
                    int exitCode = finished ? process.exitValue() : -1;
                    Map<String, Object> result =
                            baseExecuteCodeResult(
                                    status, stdoutText, toolCallsMade.get(), started);
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
                        toolCallsMade.get(),
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
            return SecretRedactor.redact(TerminalAnsiSanitizer.stripAnsi(value));
        }

        private double durationSeconds(long started) {
            double value = (System.nanoTime() - started) / 1000000000.0d;
            return Math.round(value * 100.0d) / 100.0d;
        }

        private void writeSolonClawToolsStub(Path target) throws Exception {
            String source =
                    "import json, os, shlex, time\n"
                            + "\n"
                            + "_seq = 0\n"
                            + "_rpc_dir = os.environ.get('JIMUQU_RPC_DIR')\n"
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
                            + "        raise RuntimeError('JIMUQU_RPC_DIR is not configured')\n"
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
                            + "    raise RuntimeError(name + ' is not available in jimuqu-agent execute_code yet. Use normal tool calls instead.')\n"
                            + "\n"
                            + "def web_search(query, limit=5): return _call('web_search', {'query': query, 'limit': limit})\n"
                            + "def web_extract(urls): return _call('web_extract', {'urls': urls})\n"
                            + "def read_file(path, offset=1, limit=500): return _call('read_file', {'path': path, 'offset': offset, 'limit': limit})\n"
                            + "def write_file(path, content): return _call('write_file', {'path': path, 'content': content})\n"
                            + "def search_files(pattern, target='content', path='.', file_glob=None, limit=50, offset=0, output_mode='content', context=0): return _call('search_files', {'pattern': pattern, 'target': target, 'path': path, 'file_glob': file_glob, 'limit': limit, 'offset': offset, 'output_mode': output_mode, 'context': context})\n"
                            + "def patch(path=None, old_string=None, new_string=None, replace_all=False, mode='replace', patch=None): return _call('patch', {'path': path, 'old_string': old_string, 'new_string': new_string, 'replace_all': replace_all, 'mode': mode, 'patch': patch})\n"
                            + "def terminal(command, timeout=None, workdir=None, **kwargs): return _call('terminal', {'command': command, 'timeout': timeout, 'workdir': workdir})\n";
            Files.write(target, source.getBytes(StandardCharsets.UTF_8));
        }

        private void runRpcLoop(
                Path rpcDir, AtomicInteger toolCallsMade, AtomicBoolean accepting) {
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
                } catch (Exception ignored) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void handleRpcRequest(Path rpcDir, Path request, AtomicInteger toolCallsMade) {
            String response;
            int seq = 0;
            try {
                Map<String, Object> payload =
                        castMap(
                                ONode.deserialize(
                                        new String(Files.readAllBytes(request), StandardCharsets.UTF_8),
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
                response = ONode.serialize(errorMap(e.getMessage()));
                seq = extractSeq(request);
            }
            try {
                Path result = rpcDir.resolve(String.format(Locale.ROOT, "res_%06d.json", seq));
                Path temp = rpcDir.resolve(result.getFileName().toString() + ".tmp");
                Files.write(temp, response.getBytes(StandardCharsets.UTF_8));
                Files.move(temp, result, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(request);
            } catch (Exception ignored) {
            }
        }

        private String dispatchRpcTool(String toolName, Map<String, Object> args) {
            try {
                if ("read_file".equals(toolName)) {
                    return normalizeToolResult(
                            fileSkill.read(
                                    getString(args, "path", null),
                                    Integer.valueOf(getInt(args, "offset", 1)),
                                    Integer.valueOf(getInt(args, "limit", 500))));
                }
                if ("write_file".equals(toolName)) {
                    return normalizeToolResult(
                            fileSkill.write(
                                    getString(args, "path", null),
                                    getString(args, "content", "")));
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
                    return ONode.serialize(searchFiles(args));
                }
                if ("web_search".equals(toolName)) {
                    return ONode.serialize(webSearch(args));
                }
                if ("web_extract".equals(toolName)) {
                    return ONode.serialize(webExtract(args));
                }
                return ONode.serialize(
                        errorMap(
                                "Tool '"
                                        + toolName
                                        + "' is not available in execute_code. Available: patch, read_file, search_files, terminal, web_extract, web_search, write_file"));
            } catch (Exception e) {
                return ONode.serialize(errorMap(e.getMessage()));
            }
        }

        private String normalizeToolResult(String result) {
            String value = StrUtil.nullToEmpty(result);
            try {
                Object parsed = ONode.deserialize(value, Object.class);
                if (parsed instanceof Map) {
                    Map<String, Object> map = castMap(parsed);
                    ensureStatusField(map);
                    return ONode.serialize(map);
                }
                if (parsed instanceof List) {
                    return ONode.serialize(parsed);
                }
            } catch (Exception ignored) {
            }
            Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
            wrapped.put("output", value);
            wrapped.put("result", value);
            wrapped.put("status", "success");
            wrapped.put("success", Boolean.TRUE);
            return ONode.serialize(wrapped);
        }

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
                return errorMap("path does not exist: " + relativePath);
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
                    if (!relText.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    if (skipped++ < offset) {
                        continue;
                    }
                    matches.add(matchMap(relText, null, null));
                } else {
                    String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    int index = content.toLowerCase(Locale.ROOT).indexOf(pattern.toLowerCase(Locale.ROOT));
                    if (index < 0) {
                        continue;
                    }
                    if (skipped++ < offset) {
                        continue;
                    }
                    matches.add(matchMap(relText, Integer.valueOf(lineNumber(content, index)), previewLine(content, index)));
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
            result.put("success", Boolean.TRUE);
            return result;
        }

        private Map<String, Object> webExtract(Map<String, Object> args) throws Exception {
            Object rawUrls = args == null ? null : args.get("urls");
            List<String> urls = stringList(rawUrls);
            if (urls.isEmpty()) {
                return errorMap("urls is required");
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (String url : urls) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("url", url);
                try {
                    Document doc = webfetchTool.webfetch(url, "markdown", Integer.valueOf(120));
                    item.put("title", StrUtil.blankToDefault(doc == null ? null : doc.getTitle(), url));
                    item.put("content", doc == null ? "" : StrUtil.nullToEmpty(doc.getContent()));
                    item.put("error", null);
                } catch (Exception e) {
                    item.put("title", url);
                    item.put("content", "");
                    item.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                items.add(item);
            }
            result.put("results", items);
            result.put("status", "success");
            result.put("success", Boolean.TRUE);
            return result;
        }

        private Map<String, Object> normalizeWebSearchDocument(Document doc, String query, int limit) {
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
            } catch (Exception ignored) {
            }
            List<Map<String, Object>> web = new ArrayList<Map<String, Object>>();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("url", doc == null ? "" : StrUtil.nullToEmpty(doc.getUrl()));
            item.put("title", doc == null ? "Web search: " + query : StrUtil.blankToDefault(doc.getTitle(), "Web search: " + query));
            item.put("description", content);
            web.add(item);
            return webSearchResult(web);
        }

        private Map<String, Object> webSearchResult(List<Map<String, Object>> web) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("data", data);
            return result;
        }

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
                    item.put("description", firstString(one, "description", "snippet", "content", "text"));
                    web.add(item);
                }
            }
            return web;
        }

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
                throw new IllegalArgumentException(blockedFileMessage(ToolNameConstants.FILE_LIST, verdict));
            }
        }

        private Path resolveContainedPath(String path) throws Exception {
            Path root = Paths.get(workDir).toAbsolutePath().normalize();
            Path resolved = root.resolve(StrUtil.blankToDefault(path, ".")).normalize();
            Path realRoot = root.toRealPath();
            Path real =
                    Files.exists(resolved)
                            ? resolved.toRealPath()
                            : resolved.toAbsolutePath().normalize();
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException("path escapes workspace: " + path);
            }
            return resolved;
        }

        private List<Path> listFiles(Path base) throws Exception {
            if (Files.isRegularFile(base)) {
                return Collections.singletonList(base);
            }
            final List<Path> files = new ArrayList<Path>();
            Files.walk(base)
                    .filter(Files::isRegularFile)
                    .limit(10000)
                    .forEach(files::add);
            return files;
        }

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

        private int lineNumber(String content, int index) {
            int line = 1;
            for (int i = 0; i < index && i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

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

        private boolean hasPendingRequests(Path rpcDir) {
            return !listRequestFiles(rpcDir).isEmpty();
        }

        private boolean isExecuteCodeStagingPath(String relativePath) {
            String path = StrUtil.nullToEmpty(relativePath).replace('\\', '/');
            return path.startsWith("execute_code_") || path.contains("/execute_code_");
        }

        private void ensureStatusField(Map<String, Object> map) {
            if (map == null || map.containsKey("status")) {
                return;
            }
            Object success = map.get("success");
            if (Boolean.TRUE.equals(success)) {
                map.put("status", "success");
                return;
            }
            if (Boolean.FALSE.equals(success)) {
                map.put("status", "error");
                return;
            }
            Object error = map.get("error");
            if (error != null && StrUtil.isNotBlank(String.valueOf(error))) {
                map.put("status", "error");
                map.put("success", Boolean.FALSE);
            }
        }

        private List<Path> listRequestFiles(Path rpcDir) {
            try {
                List<Path> files = new ArrayList<Path>();
                Files.newDirectoryStream(rpcDir, "req_*.json")
                        .forEach(files::add);
                Collections.sort(files);
                return files;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> castMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return new LinkedHashMap<String, Object>();
        }

        private int extractSeq(Path request) {
            String name = request.getFileName().toString();
            try {
                return Integer.parseInt(name.replace("req_", "").replace(".json", ""));
            } catch (Exception e) {
                return 0;
            }
        }

        private Map<String, Object> errorMap(String error) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("error", StrUtil.blankToDefault(error, "Tool execution failed"));
            result.put("status", "error");
            result.put("success", Boolean.FALSE);
            return result;
        }

        private String getString(Map<String, Object> map, String key, String defaultValue) {
            Object value = map == null ? null : map.get(key);
            return value == null ? defaultValue : String.valueOf(value);
        }

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

        @SuppressWarnings("unchecked")
        private List<String> stringList(Object value) {
            List<String> result = new ArrayList<String>();
            if (value instanceof List) {
                for (Object item : (List<Object>) value) {
                    if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                        result.add(String.valueOf(item));
                    }
                }
                return result;
            }
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                result.add(String.valueOf(value));
            }
            return result;
        }

        private String firstString(Map<String, Object> map, String key1, String key2) {
            String value = getString(map, key1, null);
            return StrUtil.isBlank(value) ? getString(map, key2, "") : value;
        }

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

        private static int maxFileReadLines(AppConfig appConfig) {
            return appConfig == null || appConfig.getTask() == null
                    ? 2000
                    : appConfig.getTask().getToolOutputMaxLines();
        }

        private static int maxFileReadLineLength(AppConfig appConfig) {
            return appConfig == null || appConfig.getTask() == null
                    ? 2000
                    : appConfig.getTask().getToolOutputMaxLineLength();
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
        assertSafe(toolName, code, securityPolicyService, true);
    }

    static void assertSafeForManagedBackground(
            String toolName, String code, SecurityPolicyService securityPolicyService) {
        assertSafe(toolName, code, securityPolicyService, false);
    }

    private static void assertSafe(
            String toolName,
            String code,
            SecurityPolicyService securityPolicyService,
            boolean rejectForegroundPatterns) {
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
        if (rejectForegroundPatterns) {
            String foregroundGuidance = approvalService.foregroundBackgroundGuidance(toolName, code);
            if (foregroundGuidance != null) {
                throw new IllegalArgumentException(foregroundGuidance);
            }
        }
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(toolName, code);
        if (dangerous != null) {
            throw new IllegalArgumentException(blockedDangerousMessage(toolName, dangerous));
        }
    }

    static void assertSafeExecuteCodeScript(
            String code, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService != null) {
            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkCommandUrls(code);
            if (!urlVerdict.isAllowed()) {
                throw new IllegalArgumentException(blockedUrlMessage(urlVerdict));
            }
        }

        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(null, securityPolicyService);
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
        DangerousCommandApprovalService.DetectionResult dangerous =
                approvalService.detect(ToolNameConstants.EXECUTE_PYTHON, code);
        if (dangerous != null) {
            throw new IllegalArgumentException(
                    blockedDangerousMessage(ToolNameConstants.EXECUTE_CODE, dangerous));
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

