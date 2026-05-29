package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.IdSupport;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;

/** Replaces oversized tool observations with a persisted-result envelope. */
public class ToolResultStorageInterceptor implements ReActInterceptor {
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
        String callId =
                toolName + "-" + Math.max(0, trace.getToolCallCount()) + "-" + IdSupport.newId();
        ToolResultStorageService.StoredResult stored =
                storageService.observe(toolName, result, resolvedRunId, callId);
        trace.setLastObservation(stored.getObservation());
    }
}
