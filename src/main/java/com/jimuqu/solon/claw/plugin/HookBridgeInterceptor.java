package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.plugin.hook.HookResult;
import com.jimuqu.solon.claw.tool.runtime.ReActToolObservationSupport;
import java.util.HashMap;
import java.util.Map;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.message.ChatMessage;
import reactor.core.publisher.Flux;
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
     * @param exchanger 工具交换对象，承载工具名称、参数和结果。
     */
    @Override
    public void onAction(ReActTrace trace, ToolExchanger exchanger) {
        String toolName = exchanger == null ? null : exchanger.getToolName();
        Map<String, Object> args = exchanger == null ? null : exchanger.getArgs();
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
     * @param exchanger 工具交换对象，承载工具名称、参数和结果。
     * @param message 工具消息。
     * @param error 工具执行异常。
     * @param durationMs 工具执行耗时，单位毫秒。
     */
    @Override
    public void onObservation(
            ReActTrace trace,
            ToolExchanger exchanger,
            ChatMessage message,
            Throwable error,
            long durationMs) {
        Map<String, Object> hookArgs = new HashMap<>();
        String toolName = exchanger == null ? null : exchanger.getToolName();
        hookArgs.put("tool_name", toolName);
        hookArgs.put("result", ReActToolObservationSupport.get(trace, exchanger));
        hookArgs.put("duration_ms", durationMs);
        hookArgs.put("session_id", sessionId(trace));
        hookRegistry.invoke(AgentHookName.POST_TOOL_CALL, hookArgs);
    }

    /**
     * 在模型请求前触发 pre_api_request 钩子。
     *
     * @param request 模型请求。
     * @param chain 后续模型调用链。
     * @return 返回模型响应。
     */
    @Override
    public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws java.io.IOException {
        invokeApiHook(AgentHookName.PRE_API_REQUEST);
        try {
            return chain.doIntercept(request);
        } finally {
            invokeApiHook(AgentHookName.POST_API_REQUEST);
        }
    }

    /**
     * 在流式模型请求前后触发 API 请求钩子。
     *
     * @param request 模型请求。
     * @param chain 后续流式调用链。
     * @return 返回流式模型响应。
     */
    @Override
    public Flux<ChatResponse> interceptStream(ChatRequest request, StreamChain chain) {
        invokeApiHook(AgentHookName.PRE_API_REQUEST);
        return chain.doIntercept(request).doFinally(signal -> invokeApiHook(AgentHookName.POST_API_REQUEST));
    }

    /** 触发不依赖 trace 的模型请求钩子。 */
    private void invokeApiHook(String hookName) {
        Map<String, Object> hookArgs = new HashMap<>();
        hookRegistry.invoke(hookName, hookArgs);
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
