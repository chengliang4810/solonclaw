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
    /** ReAct 拦截器桥接失败时使用的日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HookBridgeInterceptor.class);

    /** 插件钩子注册表，承接 Solon AI ReAct 生命周期事件。 */
    private final AgentHookRegistry hookRegistry;

    /**
     * 创建 Solon AI ReAct 到插件钩子的桥接器。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     */
    public HookBridgeInterceptor(AgentHookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * 在工具调用前触发 pre_tool_call 钩子。
     *
     * @param trace ReAct 执行轨迹。
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
     * 在工具调用返回观察结果后触发 post_tool_call 钩子。
     *
     * @param trace ReAct 执行轨迹。
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     * @param durationMs 工具执行耗时，单位毫秒。
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
     * 在模型请求前触发 pre_api_request 钩子。
     *
     * @param trace ReAct 执行轨迹。
     * @param req Solon AI 模型请求描述。
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
     * 在模型响应后触发 post_api_request 钩子。
     *
     * @param trace ReAct 执行轨迹。
     * @param resp Solon AI 模型响应。
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
     * 从 ReAct 轨迹中提取会话标识。
     *
     * @return 轨迹绑定的会话 ID，缺失时返回空字符串。
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
         * 创建工具调用阻断异常。
         *
         * @param message 钩子返回的阻断原因。
         */
        public ToolCallBlockedException(String message) {
            super(message);
        }
    }
}
