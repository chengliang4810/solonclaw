package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.plugin.hook.HookResult;
import java.util.HashMap;
import java.util.Map;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 桥接 Solon AI ReActInterceptor 到 AgentHookRegistry。 */
public class HookBridgeInterceptor implements ReActInterceptor {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(HookBridgeInterceptor.class);

    /** 记录钩子BridgeInterceptor中的钩子注册表。 */
    private final AgentHookRegistry hookRegistry;

    /**
     * 创建钩子Bridge Interceptor实例，并注入运行所需依赖。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     */
    public HookBridgeInterceptor(AgentHookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * 响应Action事件。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     */
    @Override
    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        Map<String, Object> hookArgs = new HashMap<>();
        hookArgs.put("tool_name", toolName);
        hookArgs.put("args", args);
        hookArgs.put("session_id", sessionId(trace));
        HookResult result = hookRegistry.invokeWithResult(AgentHookName.PRE_TOOL_CALL, hookArgs);
        if (result != null && result.isBlock()) {
            throw new ToolCallBlockedException(result.getMessage());
        }
    }

    /**
     * 响应观察结果事件。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     * @param durationMs durationMs 参数。
     */
    @Override
    public void onObservation(ReActTrace trace, String toolName, String result, long durationMs) {
        Map<String, Object> hookArgs = new HashMap<>();
        hookArgs.put("tool_name", toolName);
        hookArgs.put("result", result);
        hookArgs.put("duration_ms", durationMs);
        hookArgs.put("session_id", sessionId(trace));
        hookRegistry.invoke(AgentHookName.POST_TOOL_CALL, hookArgs);
    }

    /**
     * 响应模型Start事件。
     *
     * @param trace trace 参数。
     * @param req req 参数。
     */
    @Override
    public void onModelStart(ReActTrace trace, ChatRequestDesc req) {
        Map<String, Object> hookArgs = new HashMap<>();
        hookArgs.put("session_id", sessionId(trace));
        hookArgs.put("agent_name", trace.getAgentName());
        hookArgs.put("step_count", trace.getStepCount());
        hookRegistry.invoke(AgentHookName.PRE_API_REQUEST, hookArgs);
    }

    /**
     * 响应模型End事件。
     *
     * @param trace trace 参数。
     * @param resp resp 参数。
     */
    @Override
    public void onModelEnd(ReActTrace trace, ChatResponse resp) {
        Map<String, Object> hookArgs = new HashMap<>();
        hookArgs.put("session_id", sessionId(trace));
        hookArgs.put("agent_name", trace.getAgentName());
        hookArgs.put("step_count", trace.getStepCount());
        hookRegistry.invoke(AgentHookName.POST_API_REQUEST, hookArgs);
    }

    /**
     * 执行会话标识相关逻辑。
     *
     * @param trace trace 参数。
     * @return 返回会话标识。
     */
    private String sessionId(ReActTrace trace) {
        if (trace.getSession() != null) {
            return trace.getSession().getSessionId();
        }
        return "";
    }

    /** 工具调用被 hook 阻止时抛出的异常。 */
    public static class ToolCallBlockedException extends RuntimeException {
        /**
         * 创建工具Call 块ed Exception实例，并注入运行所需依赖。
         *
         * @param message 平台消息或错误消息。
         */
        public ToolCallBlockedException(String message) {
            super(message);
        }
    }
}
