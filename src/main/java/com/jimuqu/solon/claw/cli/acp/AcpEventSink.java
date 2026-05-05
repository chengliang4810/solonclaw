package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import java.util.Map;

/** Captures agent events for ACP response payloads. */
public class AcpEventSink implements ConversationEventSink {
    private final StringBuilder assistant = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private boolean failed;
    private String error;

    @Override
    public void onAssistantDelta(String delta) {
        assistant.append(StrUtil.nullToEmpty(delta));
    }

    @Override
    public void onReasoningDelta(String delta) {
        reasoning.append(StrUtil.nullToEmpty(delta));
    }

    @Override
    public void onToolStarted(String toolName, Map<String, Object> args) {
        if (assistant.length() > 0) {
            assistant.append('\n');
        }
        assistant.append("[tool.start] ").append(StrUtil.blankToDefault(toolName, "tool"));
    }

    @Override
    public void onToolCompleted(String toolName, String result, long durationMs) {
        if (assistant.length() > 0) {
            assistant.append('\n');
        }
        assistant.append("[tool.done] ")
                .append(StrUtil.blankToDefault(toolName, "tool"))
                .append(" ")
                .append(durationMs)
                .append("ms");
    }

    @Override
    public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
        if (assistant.length() == 0 && StrUtil.isNotBlank(finalReply)) {
            assistant.append(finalReply);
        }
    }

    @Override
    public void onRunFailed(String sessionId, Throwable error) {
        failed = true;
        this.error = error == null ? "unknown" : error.getMessage();
    }

    public String assistantText() {
        return assistant.toString();
    }

    public String reasoningText() {
        return reasoning.toString();
    }

    public boolean isFailed() {
        return failed;
    }

    public String getError() {
        return error;
    }
}
