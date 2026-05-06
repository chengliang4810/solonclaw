package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;

/** Per-turn guardrail for repeated failed or non-progressing tool calls. */
public class ToolCallLoopGuardrailService {
    private static final String STATE_KEY = "solonclaw.tool_loop_guardrail.state";
    private static final Set<String> IDEMPOTENT_TOOLS =
            Collections.unmodifiableSet(
                    new HashSet<String>(
                            Arrays.asList(
                                    ToolNameConstants.FILE_READ,
                                    "read_file",
                                    ToolNameConstants.FILE_LIST,
                                    ToolNameConstants.CODESEARCH,
                                    "search_files",
                                    ToolNameConstants.WEBSEARCH,
                                    "web_search",
                                    ToolNameConstants.WEBFETCH,
                                    "web_extract",
                                    ToolNameConstants.SESSION_SEARCH,
                                    ToolNameConstants.CONFIG_GET,
                                    ToolNameConstants.SECURITY_AUDIT,
                                    "mcp_filesystem_read_file",
                                    "mcp_filesystem_read_text_file",
                                    "mcp_filesystem_read_multiple_files",
                                    "mcp_filesystem_list_directory",
                                    "mcp_filesystem_list_directory_with_sizes",
                                    "mcp_filesystem_directory_tree",
                                    "mcp_filesystem_get_file_info",
                                    "mcp_filesystem_search_files")));
    private static final Set<String> MUTATING_TOOLS =
            Collections.unmodifiableSet(
                    new HashSet<String>(
                            Arrays.asList(
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.TERMINAL,
                                    ToolNameConstants.EXECUTE_CODE,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS,
                                    ToolNameConstants.FILE_WRITE,
                                    "write_file",
                                    ToolNameConstants.FILE_DELETE,
                                    ToolNameConstants.PATCH,
                                    ToolNameConstants.TODO,
                                    ToolNameConstants.MEMORY,
                                    ToolNameConstants.SKILL_MANAGE,
                                    ToolNameConstants.SEND_MESSAGE,
                                    ToolNameConstants.CRONJOB,
                                    ToolNameConstants.DELEGATE_TASK,
                                    ToolNameConstants.PROCESS,
                                    ToolNameConstants.CONFIG_SET,
                                    ToolNameConstants.CONFIG_SET_SECRET,
                                    ToolNameConstants.CONFIG_REFRESH,
                                    ToolNameConstants.KANBAN_COMPLETE,
                                    ToolNameConstants.KANBAN_BLOCK,
                                    ToolNameConstants.KANBAN_HEARTBEAT,
                                    ToolNameConstants.KANBAN_COMMENT,
                                    ToolNameConstants.KANBAN_CREATE,
                                    ToolNameConstants.KANBAN_LINK,
                                    ToolNameConstants.KANBAN_UNLINK)));

    private final AppConfig appConfig;

    public ToolCallLoopGuardrailService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public ReActInterceptor buildInterceptor() {
        return new GuardrailInterceptor(resolveConfig());
    }

    private Config resolveConfig() {
        AppConfig.ReActConfig react =
                appConfig == null || appConfig.getReact() == null
                        ? new AppConfig.ReActConfig()
                        : appConfig.getReact();
        return new Config(
                react.isToolLoopWarningsEnabled(),
                react.isToolLoopHardStopEnabled(),
                Math.max(1, react.getToolLoopExactFailureWarnAfter()),
                Math.max(1, react.getToolLoopExactFailureBlockAfter()),
                Math.max(1, react.getToolLoopSameToolFailureWarnAfter()),
                Math.max(1, react.getToolLoopSameToolFailureHaltAfter()),
                Math.max(1, react.getToolLoopNoProgressWarnAfter()),
                Math.max(1, react.getToolLoopNoProgressBlockAfter()));
    }

    public static class GuardrailInterceptor implements ReActInterceptor {
        private final Config config;

        GuardrailInterceptor(Config config) {
            this.config = config;
        }

        @Override
        public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
            if (trace == null || toolName == null) {
                return;
            }
            State state = state(trace);
            Signature signature = signature(toolName, args);
            Decision decision = state.beforeCall(toolName, signature, config);
            state.currentCalls.put(toolName, signature);
            trace.setExtra(argsKey(toolName), args == null ? Collections.emptyMap() : args);
            if (!decision.shouldHalt()) {
                return;
            }
            trace.setLastObservation(syntheticResult(decision));
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(haltFinalAnswer(decision), false);
            if (trace.getContext() != null) {
                trace.getContext().interrupt();
            }
        }

        @Override
        public void onObservation(ReActTrace trace, String toolName, String result, long durationMs) {
            if (trace == null || toolName == null) {
                return;
            }
            State state = state(trace);
            Signature signature = state.currentCalls.get(toolName);
            if (signature == null) {
                signature = signature(toolName, argsFor(trace, toolName));
            }
            String original = StrUtil.nullToEmpty(trace.getLastObservation());
            if (StrUtil.isEmpty(original)) {
                original = StrUtil.nullToEmpty(result);
            }
            Decision decision = state.afterCall(toolName, signature, original, config);
            if (decision.shouldWarn()) {
                trace.setLastObservation(appendGuidance(original, decision));
            }
            if (decision.shouldHalt()) {
                String rewritten = appendGuidance(original, decision);
                trace.setLastObservation(rewritten);
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer(haltFinalAnswer(decision), false);
                if (trace.getContext() != null) {
                    trace.getContext().interrupt();
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> argsFor(ReActTrace trace, String toolName) {
            Object value = trace.getExtra(argsKey(toolName));
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return Collections.emptyMap();
        }

        private static State state(ReActTrace trace) {
            State state = trace.getExtraAs(STATE_KEY);
            if (state == null) {
                state = new State();
                trace.setExtra(STATE_KEY, state);
            }
            return state;
        }
    }

    private static class State {
        private final Map<Signature, Integer> exactFailureCounts =
                new HashMap<Signature, Integer>();
        private final Map<String, Integer> sameToolFailureCounts = new HashMap<String, Integer>();
        private final Map<Signature, NoProgressRecord> noProgress =
                new HashMap<Signature, NoProgressRecord>();
        private final Map<String, Signature> currentCalls = new HashMap<String, Signature>();

        private Decision beforeCall(String toolName, Signature signature, Config config) {
            if (!config.hardStopEnabled) {
                return Decision.allow(toolName, signature);
            }
            int exactCount = intValue(exactFailureCounts.get(signature));
            if (exactCount >= config.exactFailureBlockAfter) {
                return Decision.block(
                        "repeated_exact_failure_block",
                        toolName,
                        exactCount,
                        "已阻断 "
                                + toolName
                                + "：相同参数的工具调用已失败 "
                                + exactCount
                                + " 次。停止原样重试，改用其他方案或向用户说明阻塞点。",
                        signature);
            }
            if (isIdempotent(toolName)) {
                NoProgressRecord record = noProgress.get(signature);
                if (record != null && record.count >= config.noProgressBlockAfter) {
                    return Decision.block(
                            "idempotent_no_progress_block",
                            toolName,
                            record.count,
                            "已阻断 "
                                    + toolName
                                    + "：这个只读调用已连续 "
                                    + record.count
                                    + " 次返回相同结果。使用已有结果或更换查询条件。",
                            signature);
                }
            }
            return Decision.allow(toolName, signature);
        }

        private Decision afterCall(
                String toolName, Signature signature, String result, Config config) {
            boolean failed = classifyFailure(toolName, result);
            if (failed) {
                int exactCount = intValue(exactFailureCounts.get(signature)) + 1;
                exactFailureCounts.put(signature, Integer.valueOf(exactCount));
                noProgress.remove(signature);
                int sameCount = intValue(sameToolFailureCounts.get(toolName)) + 1;
                sameToolFailureCounts.put(toolName, Integer.valueOf(sameCount));

                if (config.hardStopEnabled && sameCount >= config.sameToolFailureHaltAfter) {
                    return Decision.halt(
                            "same_tool_failure_halt",
                            toolName,
                            sameCount,
                            toolName
                                    + " 本轮已失败 "
                                    + sameCount
                                    + " 次。停止继续重试同一失败路径，改用其他方案。",
                            signature);
                }
                if (config.warningsEnabled && exactCount >= config.exactFailureWarnAfter) {
                    return Decision.warn(
                            "repeated_exact_failure_warning",
                            toolName,
                            exactCount,
                            toolName
                                    + " 已使用相同参数失败 "
                                    + exactCount
                                    + " 次。这看起来像工具循环；请检查错误并改变策略，不要原样重试。",
                            signature);
                }
                if (config.warningsEnabled && sameCount >= config.sameToolFailureWarnAfter) {
                    return Decision.warn(
                            "same_tool_failure_warning",
                            toolName,
                            sameCount,
                            toolName
                                    + " 本轮已失败 "
                                    + sameCount
                                    + " 次。这看起来像工具循环；再次调用前请改变方案。",
                            signature);
                }
                return Decision.allow(toolName, signature);
            }

            exactFailureCounts.remove(signature);
            sameToolFailureCounts.remove(toolName);
            if (!isIdempotent(toolName)) {
                noProgress.remove(signature);
                return Decision.allow(toolName, signature);
            }

            String resultHash = sha256(canonicalResult(result));
            NoProgressRecord previous = noProgress.get(signature);
            int repeatCount =
                    previous != null && StrUtil.equals(previous.resultHash, resultHash)
                            ? previous.count + 1
                            : 1;
            noProgress.put(signature, new NoProgressRecord(resultHash, repeatCount));
            if (config.warningsEnabled && repeatCount >= config.noProgressWarnAfter) {
                return Decision.warn(
                        "idempotent_no_progress_warning",
                        toolName,
                        repeatCount,
                        toolName
                                + " 已连续 "
                                + repeatCount
                                + " 次返回相同结果。使用已有结果或更换查询条件，不要原样重复调用。",
                        signature);
            }
            return Decision.allow(toolName, signature);
        }
    }

    private static class Config {
        private final boolean warningsEnabled;
        private final boolean hardStopEnabled;
        private final int exactFailureWarnAfter;
        private final int exactFailureBlockAfter;
        private final int sameToolFailureWarnAfter;
        private final int sameToolFailureHaltAfter;
        private final int noProgressWarnAfter;
        private final int noProgressBlockAfter;

        private Config(
                boolean warningsEnabled,
                boolean hardStopEnabled,
                int exactFailureWarnAfter,
                int exactFailureBlockAfter,
                int sameToolFailureWarnAfter,
                int sameToolFailureHaltAfter,
                int noProgressWarnAfter,
                int noProgressBlockAfter) {
            this.warningsEnabled = warningsEnabled;
            this.hardStopEnabled = hardStopEnabled;
            this.exactFailureWarnAfter = exactFailureWarnAfter;
            this.exactFailureBlockAfter = exactFailureBlockAfter;
            this.sameToolFailureWarnAfter = sameToolFailureWarnAfter;
            this.sameToolFailureHaltAfter = sameToolFailureHaltAfter;
            this.noProgressWarnAfter = noProgressWarnAfter;
            this.noProgressBlockAfter = noProgressBlockAfter;
        }
    }

    private static class Signature {
        private final String toolName;
        private final String argsHash;

        private Signature(String toolName, String argsHash) {
            this.toolName = StrUtil.nullToEmpty(toolName);
            this.argsHash = StrUtil.nullToEmpty(argsHash);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Signature)) {
                return false;
            }
            Signature that = (Signature) other;
            return StrUtil.equals(toolName, that.toolName) && StrUtil.equals(argsHash, that.argsHash);
        }

        @Override
        public int hashCode() {
            int result = toolName.hashCode();
            result = 31 * result + argsHash.hashCode();
            return result;
        }
    }

    private static class NoProgressRecord {
        private final String resultHash;
        private final int count;

        private NoProgressRecord(String resultHash, int count) {
            this.resultHash = resultHash;
            this.count = count;
        }
    }

    private static class Decision {
        private final String action;
        private final String code;
        private final String toolName;
        private final int count;
        private final String message;
        private final Signature signature;

        private Decision(
                String action,
                String code,
                String toolName,
                int count,
                String message,
                Signature signature) {
            this.action = action;
            this.code = code;
            this.toolName = toolName;
            this.count = count;
            this.message = message;
            this.signature = signature;
        }

        private static Decision allow(String toolName, Signature signature) {
            return new Decision("allow", "allow", toolName, 0, "", signature);
        }

        private static Decision warn(
                String code, String toolName, int count, String message, Signature signature) {
            return new Decision("warn", code, toolName, count, message, signature);
        }

        private static Decision block(
                String code, String toolName, int count, String message, Signature signature) {
            return new Decision("block", code, toolName, count, message, signature);
        }

        private static Decision halt(
                String code, String toolName, int count, String message, Signature signature) {
            return new Decision("halt", code, toolName, count, message, signature);
        }

        private boolean shouldWarn() {
            return "warn".equals(action);
        }

        private boolean shouldHalt() {
            return "block".equals(action) || "halt".equals(action);
        }
    }

    private static Signature signature(String toolName, Map<String, Object> args) {
        return new Signature(toolName, sha256(canonicalArgs(args)));
    }

    private static String canonicalArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        return ONode.serialize(canonicalValue(args));
    }

    private static String canonicalResult(String result) {
        String value = StrUtil.nullToEmpty(result);
        try {
            Object parsed = ONode.deserialize(value, Object.class);
            if (parsed instanceof Map || parsed instanceof Iterable) {
                return ONode.serialize(canonicalValue(parsed));
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object canonicalValue(Object value) {
        if (value instanceof Map) {
            Map<?, ?> input = (Map<?, ?>) value;
            Map<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                sorted.put(String.valueOf(entry.getKey()), canonicalValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<?> input = (List<?>) value;
            java.util.ArrayList<Object> normalized = new java.util.ArrayList<Object>(input.size());
            for (Object item : input) {
                normalized.add(canonicalValue(item));
            }
            return normalized;
        }
        if (value instanceof Iterable) {
            java.util.ArrayList<Object> normalized = new java.util.ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                normalized.add(canonicalValue(item));
            }
            return normalized;
        }
        return value;
    }

    private static boolean classifyFailure(String toolName, String result) {
        String value = StrUtil.nullToEmpty(result);
        Object parsedObject = null;
        try {
            parsedObject = ONode.deserialize(value, Object.class);
        } catch (Exception ignored) {
        }
        if (parsedObject instanceof Map) {
            Map<?, ?> parsed = (Map<?, ?>) parsedObject;
            Object exitCode = parsed.get("exit_code");
            if (exitCode instanceof Number && ((Number) exitCode).intValue() != 0) {
                return true;
            }
            Object success = parsed.get("success");
            if (success instanceof Boolean && !((Boolean) success).booleanValue()) {
                return true;
            }
            String status = parsed.get("status") == null ? null : String.valueOf(parsed.get("status"));
            if (StrUtil.equalsIgnoreCase(status, "error")
                    || StrUtil.equalsIgnoreCase(status, "failed")) {
                return true;
            }
            Object error = parsed.get("error");
            if (error != null && StrUtil.isNotBlank(String.valueOf(error))) {
                return true;
            }
        }
        String lower = StrUtil.subPre(value, 500).toLowerCase();
        return value.startsWith("Error")
                || value.startsWith("Invalid arguments")
                || value.startsWith("Execution error")
                || lower.contains("\"error\"")
                || lower.contains("\"failed\"");
    }

    private static String appendGuidance(String result, Decision decision) {
        String label = decision.shouldHalt() ? "工具循环硬停" : "工具循环提醒";
        return StrUtil.nullToEmpty(result)
                + "\n\n["
                + label
                + ": "
                + decision.code
                + "; count="
                + decision.count
                + "; "
                + decision.message
                + "]";
    }

    private static String syntheticResult(Decision decision) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", "error");
        map.put("success", Boolean.FALSE);
        map.put("error", decision.message);
        map.put("guardrail", metadata(decision));
        return ONode.serialize(map);
    }

    private static Map<String, Object> metadata(Decision decision) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("action", decision.action);
        map.put("code", decision.code);
        map.put("tool_name", decision.toolName);
        map.put("count", Integer.valueOf(decision.count));
        if (decision.signature != null) {
            Map<String, String> signature = new LinkedHashMap<String, String>();
            signature.put("tool_name", decision.signature.toolName);
            signature.put("args_hash", decision.signature.argsHash);
            map.put("signature", signature);
        }
        return map;
    }

    private static String haltFinalAnswer(Decision decision) {
        return "已停止重复工具调用。"
                + decision.message
                + "请基于已有信息继续说明，或等待用户提供新的输入。";
    }

    private static boolean isIdempotent(String toolName) {
        if (MUTATING_TOOLS.contains(toolName)) {
            return false;
        }
        return IDEMPOTENT_TOOLS.contains(toolName);
    }

    private static String argsKey(String toolName) {
        return "solonclaw.tool_loop_guardrail.args." + StrUtil.blankToDefault(toolName, "unknown");
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(StrUtil.nullToEmpty(value).hashCode());
        }
    }
}
