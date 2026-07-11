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
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供工具Call循环防护相关业务能力，封装调用方不需要感知的运行细节。 */
public class ToolCallLoopGuardrailService {
    /** 记录工具循环防护中的可降级异常，日志不包含工具参数或结果正文。 */
    private static final Logger log = LoggerFactory.getLogger(ToolCallLoopGuardrailService.class);

    /** 状态键的统一常量值。 */
    private static final String STATE_KEY = "solonclaw.tool_loop_guardrail.state";

    /** HALT决策EXTRA键的统一常量值。 */
    public static final String HALT_DECISION_EXTRA_KEY =
            "solonclaw.tool_loop_guardrail.halt_decision";

    /** OTHER工具CALLEPOCH的统一常量值。 */
    private static final ThreadLocal<Integer> OTHER_TOOL_CALL_EPOCH = new ThreadLocal<Integer>();

    /** IDEMPOTENT工具的统一常量值。 */
    private static final Set<String> IDEMPOTENT_TOOLS =
            Collections.unmodifiableSet(
                    new HashSet<String>(
                            Arrays.asList(
                                    ToolNameConstants.FILE_READ,
                                    ToolNameConstants.READ_FILE,
                                    ToolNameConstants.FILE_LIST,
                                    ToolNameConstants.CODESEARCH,
                                    ToolNameConstants.SEARCH_FILES,
                                    ToolNameConstants.WEBSEARCH,
                                    ToolNameConstants.WEBFETCH,
                                    ToolNameConstants.SESSION_SEARCH,
                                    "browser_snapshot",
                                    "browser_console",
                                    "browser_get_images",
                                    ToolNameConstants.CONFIG_GET,
                                    "config_env_probe",
                                    ToolNameConstants.SECURITY_AUDIT,
                                    "mcp_filesystem_read_file",
                                    "mcp_filesystem_read_text_file",
                                    "mcp_filesystem_read_multiple_files",
                                    "mcp_filesystem_list_directory",
                                    "mcp_filesystem_list_directory_with_sizes",
                                    "mcp_filesystem_directory_tree",
                                    "mcp_filesystem_get_file_info",
                                    "mcp_filesystem_search_files")));

    /** MUTATING工具的统一常量值。 */
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
                                    ToolNameConstants.WRITE_FILE,
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
                                    "browser_click",
                                    "browser_type",
                                    "browser_press",
                                    "browser_scroll",
                                    "browser_navigate")));

    /** 注入应用配置，用于工具Call循环防护。 */
    private final AppConfig appConfig;

    /**
     * 创建工具Call循环防护服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public ToolCallLoopGuardrailService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 构建Interceptor。
     *
     * @return 返回创建好的Interceptor。
     */
    public ReActInterceptor buildInterceptor() {
        return new GuardrailInterceptor(resolveConfig());
    }

    /**
     * 解析配置。
     *
     * @return 返回解析后的配置。
     */
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

    /** 承载防护Interceptor相关状态和辅助逻辑。 */
    public static class GuardrailInterceptor implements ReActInterceptor {
        /** 记录防护Interceptor中的配置。 */
        private final Config config;

        /**
         * 创建防护Interceptor实例，并注入运行所需依赖。
         *
         * @param config 当前模块使用的配置对象。
         */
        GuardrailInterceptor(Config config) {
            this.config = config;
        }

        /**
         * 响应Action事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         */
        @Override
        public void onAction(ReActTrace trace, ToolExchanger exchanger) {
            String toolName = exchanger == null ? null : exchanger.getToolName();
            Map<String, Object> args = exchanger == null ? null : exchanger.getArgs();
            if (trace == null || toolName == null) {
                return;
            }
            notifyFileReadDedupIfOtherTool(toolName);
            State state = state(trace);
            Signature signature = signature(toolName, args);
            Decision decision = state.beforeCall(toolName, signature, config);
            state.currentCalls.put(toolName, signature);
            trace.setExtra(argsKey(toolName), args == null ? Collections.emptyMap() : args);
            if (!decision.shouldHalt()) {
                return;
            }
            trace.setExtra(HALT_DECISION_EXTRA_KEY, metadata(decision));
            ReActToolObservationSupport.set(trace, exchanger, syntheticResult(decision));
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer(haltFinalAnswer(decision), false);
            if (trace.getContext() != null) {
                trace.getContext().interrupt();
            }
        }

        /**
         * 响应观察结果事件。
         *
         * @param trace trace 参数。
         * @param exchanger 工具交换对象。
         * @param message 工具消息。
         * @param error 工具异常。
         * @param durationMs durationMs 参数。
         */
        @Override
        public void onObservation(
                ReActTrace trace,
                ToolExchanger exchanger,
                ChatMessage message,
                Throwable error,
                long durationMs) {
            String toolName = exchanger == null ? null : exchanger.getToolName();
            if (trace == null || toolName == null) {
                return;
            }
            State state = state(trace);
            Signature signature = state.currentCalls.get(toolName);
            if (signature == null) {
                signature = signature(toolName, argsFor(trace, toolName));
            }
            String original = ReActToolObservationSupport.get(trace, exchanger);
            Decision decision = state.afterCall(toolName, signature, original, config);
            if (decision.shouldWarn()) {
                ReActToolObservationSupport.set(
                        trace, exchanger, appendGuidance(original, decision));
            }
            if (decision.shouldHalt()) {
                String rewritten = appendGuidance(original, decision);
                trace.setExtra(HALT_DECISION_EXTRA_KEY, metadata(decision));
                ReActToolObservationSupport.set(trace, exchanger, rewritten);
                trace.setRoute(Agent.ID_END);
                trace.setFinalAnswer(haltFinalAnswer(decision), false);
                if (trace.getContext() != null) {
                    trace.getContext().interrupt();
                }
            }
        }

        /**
         * Agent 本轮结束时清理线程本地的文件读取去重 epoch，避免线程复用时携带上一轮工具状态。
         *
         * @param trace trace 参数。
         */
        @Override
        public void onAgentEnd(ReActTrace trace) {
            OTHER_TOOL_CALL_EPOCH.remove();
        }

        /**
         * 执行参数For相关逻辑。
         *
         * @param trace trace 参数。
         * @param toolName 工具名称。
         * @return 返回参数For结果。
         */
        @SuppressWarnings("unchecked")
        private static Map<String, Object> argsFor(ReActTrace trace, String toolName) {
            Object value = trace.getExtra(argsKey(toolName));
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return Collections.emptyMap();
        }

        /**
         * 执行状态相关逻辑。
         *
         * @param trace trace 参数。
         * @return 返回状态。
         */
        private static State state(ReActTrace trace) {
            State state = trace.getExtraAs(STATE_KEY);
            if (state == null) {
                state = new State();
                trace.setExtra(STATE_KEY, state);
            }
            return state;
        }
    }

    /**
     * 执行other工具CallEpoch相关逻辑。
     *
     * @return 返回other工具Call Epoch结果。
     */
    public static int otherToolCallEpoch() {
        Integer value = OTHER_TOOL_CALL_EPOCH.get();
        return value == null ? 0 : value.intValue();
    }

    /**
     * 执行notify文件ReadDedupIfOther工具相关逻辑。
     *
     * @param toolName 工具名称。
     */
    public static void notifyFileReadDedupIfOtherTool(String toolName) {
        if (isFileReadTool(toolName)) {
            return;
        }
        OTHER_TOOL_CALL_EPOCH.set(Integer.valueOf(otherToolCallEpoch() + 1));
    }

    /** 表示状态数据，在服务、仓储和接口之间传递。 */
    private static class State {
        /** 保存精确FailureCounts映射，便于按键快速查询。 */
        private final Map<Signature, Integer> exactFailureCounts =
                new HashMap<Signature, Integer>();

        /** 保存same工具FailureCounts映射，便于按键快速查询。 */
        private final Map<String, Integer> sameToolFailureCounts = new HashMap<String, Integer>();

        /** 保存noProgress映射，便于按键快速查询。 */
        private final Map<Signature, NoProgressRecord> noProgress =
                new HashMap<Signature, NoProgressRecord>();

        /** 保存当前Calls映射，便于按键快速查询。 */
        private final Map<String, Signature> currentCalls = new HashMap<String, Signature>();

        /**
         * 执行beforeCall相关逻辑。
         *
         * @param toolName 工具名称。
         * @param signature 请求携带的签名值。
         * @param config 当前模块使用的配置对象。
         * @return 返回before Call结果。
         */
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

        /**
         * 执行afterCall相关逻辑。
         *
         * @param toolName 工具名称。
         * @param signature 请求携带的签名值。
         * @param result 结果响应或执行结果。
         * @param config 当前模块使用的配置对象。
         * @return 返回after Call结果。
         */
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
                            toolName + " 本轮已失败 " + sameCount + " 次。停止继续重试同一失败路径，改用其他方案。",
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
                            toolName + " 本轮已失败 " + sameCount + " 次。这看起来像工具循环；再次调用前请改变方案。",
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
                        toolName + " 已连续 " + repeatCount + " 次返回相同结果。使用已有结果或更换查询条件，不要原样重复调用。",
                        signature);
            }
            return Decision.allow(toolName, signature);
        }
    }

    /** 承载配置并集中创建运行组件。 */
    private static class Config {
        /** 是否启用warnings启用状态。 */
        private final boolean warningsEnabled;

        /** 是否启用hardStop启用状态。 */
        private final boolean hardStopEnabled;

        /** 记录配置中的精确FailureWarnAfter。 */
        private final int exactFailureWarnAfter;

        /** 记录配置中的精确Failure阻断After。 */
        private final int exactFailureBlockAfter;

        /** 记录配置中的same工具FailureWarnAfter。 */
        private final int sameToolFailureWarnAfter;

        /** 记录配置中的same工具FailureHaltAfter。 */
        private final int sameToolFailureHaltAfter;

        /** 记录配置中的noProgressWarnAfter。 */
        private final int noProgressWarnAfter;

        /** 记录配置中的noProgress阻断After。 */
        private final int noProgressBlockAfter;

        /**
         * 创建配置实例，并注入运行所需依赖。
         *
         * @param warnings启用 warnings启用状态开关值。
         * @param hardStop启用 hardStop启用状态开关值。
         * @param exactFailureWarnAfter 精确FailureWarnAfter参数。
         * @param exactFailure块After 精确Failure阻断After参数。
         * @param sameToolFailureWarnAfter same工具FailureWarnAfter参数。
         * @param sameToolFailureHaltAfter same工具FailureHaltAfter参数。
         * @param noProgressWarnAfter noProgressWarnAfter 参数。
         * @param noProgress块After noProgress阻断After参数。
         */
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

    /** 承载签名相关状态和辅助逻辑。 */
    private static class Signature {
        /** 记录签名中的工具名称。 */
        private final String toolName;

        /** 记录签名中的参数哈希。 */
        private final String argsHash;

        /**
         * 创建签名实例，并注入运行所需依赖。
         *
         * @param toolName 工具名称。
         * @param argsHash args哈希参数。
         */
        private Signature(String toolName, String argsHash) {
            this.toolName = StrUtil.nullToEmpty(toolName);
            this.argsHash = StrUtil.nullToEmpty(argsHash);
        }

        /**
         * 判断两个对象是否表示同一业务值。
         *
         * @param other 待比较对象。
         * @return 返回equals结果。
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Signature)) {
                return false;
            }
            Signature that = (Signature) other;
            return StrUtil.equals(toolName, that.toolName)
                    && StrUtil.equals(argsHash, that.argsHash);
        }

        /**
         * 计算当前对象的哈希值。
         *
         * @return 返回hash Code结果。
         */
        @Override
        public int hashCode() {
            int result = toolName.hashCode();
            result = 31 * result + argsHash.hashCode();
            return result;
        }
    }

    /** 表示NoProgress数据，在服务、仓储和接口之间传递。 */
    private static class NoProgressRecord {
        /** 记录NoProgress中的结果哈希。 */
        private final String resultHash;

        /** 记录NoProgress中的次数。 */
        private final int count;

        /**
         * 创建No Progress记录实例，并注入运行所需依赖。
         *
         * @param resultHash 结果哈希响应或执行结果。
         * @param count count 参数。
         */
        private NoProgressRecord(String resultHash, int count) {
            this.resultHash = resultHash;
            this.count = count;
        }
    }

    /** 表示决策结果，携带调用方后续判断所需信息。 */
    private static class Decision {
        /** 记录决策中的action。 */
        private final String action;

        /** 记录决策中的code。 */
        private final String code;

        /** 记录决策中的工具名称。 */
        private final String toolName;

        /** 记录决策中的次数。 */
        private final int count;

        /** 记录决策中的消息。 */
        private final String message;

        /** 记录决策中的签名。 */
        private final Signature signature;

        /**
         * 创建Decision实例，并注入运行所需依赖。
         *
         * @param action 操作参数。
         * @param code code 参数。
         * @param toolName 工具名称。
         * @param count count 参数。
         * @param message 平台消息或错误消息。
         * @param signature 请求携带的签名值。
         */
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

        /**
         * 执行allow相关逻辑。
         *
         * @param toolName 工具名称。
         * @param signature 请求携带的签名值。
         * @return 返回allow结果。
         */
        private static Decision allow(String toolName, Signature signature) {
            return new Decision("allow", "allow", toolName, 0, "", signature);
        }

        /**
         * 执行warn相关逻辑。
         *
         * @param code code 参数。
         * @param toolName 工具名称。
         * @param count count 参数。
         * @param message 平台消息或错误消息。
         * @param signature 请求携带的签名值。
         * @return 返回warn结果。
         */
        private static Decision warn(
                String code, String toolName, int count, String message, Signature signature) {
            return new Decision("warn", code, toolName, count, message, signature);
        }

        /**
         * 执行阻断相关逻辑。
         *
         * @param code code 参数。
         * @param toolName 工具名称。
         * @param count count 参数。
         * @param message 平台消息或错误消息。
         * @param signature 请求携带的签名值。
         * @return 返回block结果。
         */
        private static Decision block(
                String code, String toolName, int count, String message, Signature signature) {
            return new Decision("block", code, toolName, count, message, signature);
        }

        /**
         * 执行halt相关逻辑。
         *
         * @param code code 参数。
         * @param toolName 工具名称。
         * @param count count 参数。
         * @param message 平台消息或错误消息。
         * @param signature 请求携带的签名值。
         * @return 返回halt结果。
         */
        private static Decision halt(
                String code, String toolName, int count, String message, Signature signature) {
            return new Decision("halt", code, toolName, count, message, signature);
        }

        /**
         * 判断是否需要Warn。
         *
         * @return 如果Warn满足条件则返回 true，否则返回 false。
         */
        private boolean shouldWarn() {
            return "warn".equals(action);
        }

        /**
         * 判断是否需要Halt。
         *
         * @return 如果Halt满足条件则返回 true，否则返回 false。
         */
        private boolean shouldHalt() {
            return "block".equals(action) || "halt".equals(action);
        }
    }

    /**
     * 执行签名相关逻辑。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回签名结果。
     */
    private static Signature signature(String toolName, Map<String, Object> args) {
        return new Signature(toolName, sha256(canonicalArgs(args)));
    }

    /**
     * 执行规范参数相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回规范参数结果。
     */
    private static String canonicalArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        return ONode.serialize(canonicalValue(args));
    }

    /**
     * 执行规范结果相关逻辑。
     *
     * @param result 结果响应或执行结果。
     * @return 返回规范结果。
     */
    private static String canonicalResult(String result) {
        String value = StrUtil.nullToEmpty(result);
        try {
            Object parsed = ONode.deserialize(value, Object.class);
            if (parsed instanceof Map || parsed instanceof Iterable) {
                return ONode.serialize(canonicalValue(parsed));
            }
        } catch (Exception e) {
            logRecoverableFailure("canonical-result", e);
        }
        return value;
    }

    /**
     * 执行规范值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回规范Value结果。
     */
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

    /**
     * 执行classifyFailure相关逻辑。
     *
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     * @return 返回classify Failure结果。
     */
    private static boolean classifyFailure(String toolName, String result) {
        String value = StrUtil.nullToEmpty(result);
        Object parsedObject = null;
        try {
            parsedObject = ONode.deserialize(value, Object.class);
        } catch (Exception e) {
            logRecoverableFailure("classify-failure", e);
        }
        if (parsedObject instanceof Map) {
            Map<?, ?> parsed = (Map<?, ?>) parsedObject;
            Object exitCode = parsed.get("exit_code");
            if (exitCode instanceof Number && ((Number) exitCode).intValue() != 0) {
                return true;
            }
            String status =
                    parsed.get("status") == null ? null : String.valueOf(parsed.get("status"));
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

    /**
     * 追加Guidance。
     *
     * @param result 结果响应或执行结果。
     * @param decision 决策参数。
     * @return 返回Guidance结果。
     */
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

    /**
     * 执行synthetic结果相关逻辑。
     *
     * @param decision 决策参数。
     * @return 返回synthetic结果。
     */
    private static String syntheticResult(Decision decision) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", "error");
        map.put("error", decision.message);
        map.put("guardrail", metadata(decision));
        return ONode.serialize(map);
    }

    /**
     * 记录可恢复解析失败，避免吞异常同时不泄露工具参数、命令、URL 或结果正文。
     *
     * @param stage 降级阶段。
     * @param error 异常对象。
     */
    private static void logRecoverableFailure(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "tool loop guardrail fallback. stage={} error={}",
                    stage,
                    exceptionSummary(error));
        }
    }

    /**
     * 生成低敏异常摘要，仅保留异常类型供排障定位。
     *
     * @param error 异常对象。
     * @return 返回异常类型摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    /**
     * 执行元数据相关逻辑。
     *
     * @param decision 决策参数。
     * @return 返回元数据结果。
     */
    private static Map<String, Object> metadata(Decision decision) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("action", decision.action);
        map.put("code", decision.code);
        map.put("message", decision.message);
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

    /**
     * 执行halt最终Answer相关逻辑。
     *
     * @param decision 决策参数。
     * @return 返回halt Final Answer结果。
     */
    private static String haltFinalAnswer(Decision decision) {
        return "已停止重复工具调用。" + decision.message + "请基于已有信息继续说明，或等待用户提供新的输入。";
    }

    /**
     * 判断是否Idempotent。
     *
     * @param toolName 工具名称。
     * @return 如果Idempotent满足条件则返回 true，否则返回 false。
     */
    private static boolean isIdempotent(String toolName) {
        if (MUTATING_TOOLS.contains(toolName)) {
            return false;
        }
        return IDEMPOTENT_TOOLS.contains(toolName);
    }

    /**
     * 判断是否文件Read工具。
     *
     * @param toolName 工具名称。
     * @return 如果文件Read工具满足条件则返回 true，否则返回 false。
     */
    private static boolean isFileReadTool(String toolName) {
        return ToolNameConstants.FILE_READ.equals(toolName)
                || ToolNameConstants.READ_FILE.equals(toolName);
    }

    /**
     * 执行参数键相关逻辑。
     *
     * @param toolName 工具名称。
     * @return 返回参数键结果。
     */
    private static String argsKey(String toolName) {
        return "solonclaw.tool_loop_guardrail.args." + StrUtil.blankToDefault(toolName, "unknown");
    }

    /**
     * 执行int值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回int Value结果。
     */
    private static int intValue(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    /**
     * 执行sha256相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回sha256结果。
     */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes =
                    digest.digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
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
