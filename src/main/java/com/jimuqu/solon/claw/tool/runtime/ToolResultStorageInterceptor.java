package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.IdSupport;
import java.util.List;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** Replaces oversized tool observations with a persisted-result envelope. */
public class ToolResultStorageInterceptor implements ReActInterceptor {
    private static final String EXTRA_TOOL_CALL_ID_PREFIX =
            "solonclaw.tool_result_storage.tool_call_id.";

    private final ToolResultStorageService storageService;
    private final String runId;

    public ToolResultStorageInterceptor(ToolResultStorageService storageService) {
        this(storageService, null);
    }

    public ToolResultStorageInterceptor(ToolResultStorageService storageService, String runId) {
        this.storageService = storageService;
        this.runId = runId;
    }

    @Override
    public void onReason(ReActTrace trace, AssistantMessage message) {
        if (storageService != null) {
            storageService.resetTurnBudget();
        }
        captureToolCallIds(trace, message);
    }

    @Override
    public void onObservation(ReActTrace trace, String toolName, String result, long durationMs) {
        if (storageService == null || trace == null) {
            return;
        }
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
        trace.setLastObservation(stored.getObservation());
    }

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

    private String key(String toolName, int index) {
        return StrUtil.blankToDefault(toolName, "unknown") + "." + Math.max(0, index);
    }
}
