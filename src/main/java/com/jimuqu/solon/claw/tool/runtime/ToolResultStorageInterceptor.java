package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.IdSupport;
import java.util.List;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 承载工具结果StorageInterceptor相关状态和辅助逻辑。 */
public class ToolResultStorageInterceptor implements ReActInterceptor {
    /** EXTRA工具CALL标识PREFIX的统一常量值。 */
    private static final String EXTRA_TOOL_CALL_ID_PREFIX =
            "solonclaw.tool_result_storage.tool_call_id.";

    /** 注入storage服务，用于调用对应业务能力。 */
    private final ToolResultStorageService storageService;

    /** 记录工具结果StorageInterceptor中的运行标识。 */
    private final String runId;

    /**
     * 创建工具结果Storage Interceptor实例，并注入运行所需依赖。
     *
     * @param storageService storage服务依赖。
     */
    public ToolResultStorageInterceptor(ToolResultStorageService storageService) {
        this(storageService, null);
    }

    /**
     * 创建工具结果Storage Interceptor实例，并注入运行所需依赖。
     *
     * @param storageService storage服务依赖。
     * @param runId 运行标识。
     */
    public ToolResultStorageInterceptor(ToolResultStorageService storageService, String runId) {
        this.storageService = storageService;
        this.runId = runId;
    }

    /**
     * 响应原因事件。
     *
     * @param trace trace 参数。
     * @param response 模型响应。
     * @param message 平台消息或错误消息。
     * @param durationMs 推理耗时。
     */
    @Override
    public void onReasonEnd(
            ReActTrace trace, ChatResponse response, AssistantMessage message, long durationMs) {
        if (storageService != null) {
            storageService.resetTurnBudget();
        }
        captureToolCallIds(trace, message);
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
        if (storageService == null || trace == null) {
            return;
        }
        String toolName = exchanger == null ? null : exchanger.getToolName();
        String result = ReActToolObservationSupport.get(trace, exchanger);
        String resolvedRunId = runId;
        if (StrUtil.isBlank(resolvedRunId)
                && trace.getSession() != null
                && StrUtil.isNotBlank(trace.getSession().getSessionId())) {
            resolvedRunId = trace.getSession().getSessionId();
        }
        if (StrUtil.isBlank(resolvedRunId)) {
            resolvedRunId = "global";
        }
        String callId = resolveToolCallId(trace, toolName);
        ToolResultStorageService.StoredResult stored =
                storageService.observe(toolName, result, resolvedRunId, callId);
        ReActToolObservationSupport.set(trace, exchanger, stored.getObservation());
    }

    /**
     * 执行capture工具Call标识相关逻辑。
     *
     * @param trace trace 参数。
     * @param message 平台消息或错误消息。
     */
    private void captureToolCallIds(ReActTrace trace, AssistantMessage message) {
        if (trace == null || message == null || message.getToolCalls() == null) {
            return;
        }
        int base = Math.max(0, trace.getToolCallCount());
        List<ToolCall> calls = message.getToolCalls();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (call == null || StrUtil.isBlank(call.getId())) {
                continue;
            }
            trace.setExtra(EXTRA_TOOL_CALL_ID_PREFIX + key(call.getName(), base + i), call.getId());
        }
    }

    /**
     * 解析工具Call标识。
     *
     * @param trace trace 参数。
     * @param toolName 工具名称。
     * @return 返回解析后的工具Call标识。
     */
    private String resolveToolCallId(ReActTrace trace, String toolName) {
        int completed = Math.max(0, trace.getToolCallCount());
        String captured = trace.getExtraAs(EXTRA_TOOL_CALL_ID_PREFIX + key(toolName, completed));
        if (StrUtil.isBlank(captured)) {
            captured = trace.getExtraAs(EXTRA_TOOL_CALL_ID_PREFIX + key(toolName, completed - 1));
        }
        if (StrUtil.isNotBlank(captured)) {
            return captured;
        }
        return toolName + "-" + completed + "-" + IdSupport.newId();
    }

    /**
     * 执行键相关逻辑。
     *
     * @param toolName 工具名称。
     * @param index 索引参数。
     * @return 返回键结果。
     */
    private String key(String toolName, int index) {
        return StrUtil.blankToDefault(toolName, "unknown") + "." + Math.max(0, index);
    }
}
