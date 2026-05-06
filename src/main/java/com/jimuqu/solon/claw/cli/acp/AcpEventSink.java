package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures agent events for ACP response payloads. */
public class AcpEventSink implements ConversationEventSink {
    private final StringBuilder assistant = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final List<Map<String, Object>> updates = new ArrayList<Map<String, Object>>();
    private int toolSequence;
    private boolean failed;
    private String error;

    @Override
    public void onAssistantDelta(String delta) {
        String text = StrUtil.nullToEmpty(delta);
        assistant.append(text);
        if (StrUtil.isNotBlank(text)) {
            Map<String, Object> update = new LinkedHashMap<String, Object>();
            update.put("session_update", "agent_message_chunk");
            update.put("type", "agent_message_chunk");
            update.put("content", textBlock(text));
            updates.add(update);
        }
    }

    @Override
    public void onReasoningDelta(String delta) {
        String text = StrUtil.nullToEmpty(delta);
        reasoning.append(text);
        if (StrUtil.isNotBlank(text)) {
            Map<String, Object> update = new LinkedHashMap<String, Object>();
            update.put("session_update", "agent_thought_chunk");
            update.put("type", "agent_thought_chunk");
            update.put("content", textBlock(text));
            updates.add(update);
        }
    }

    @Override
    public void onToolStarted(String toolName, Map<String, Object> args) {
        String toolCallId = nextToolCallId(toolName);
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("session_update", "tool_call_start");
        update.put("type", "tool_call_start");
        update.put("tool_call_id", toolCallId);
        update.put("toolCallId", toolCallId);
        update.put("tool_name", StrUtil.blankToDefault(toolName, "tool"));
        update.put("toolName", StrUtil.blankToDefault(toolName, "tool"));
        update.put("kind", toolKind(toolName));
        update.put("title", toolTitle(toolName, args));
        update.put("args", args == null ? new LinkedHashMap<String, Object>() : args);
        updates.add(update);
    }

    @Override
    public void onToolCompleted(String toolName, String result, long durationMs) {
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("session_update", "tool_call_update");
        update.put("type", "tool_call_update");
        update.put("tool_name", StrUtil.blankToDefault(toolName, "tool"));
        update.put("toolName", StrUtil.blankToDefault(toolName, "tool"));
        update.put("kind", toolKind(toolName));
        update.put("status", "completed");
        update.put("duration_ms", Long.valueOf(durationMs));
        update.put("durationMs", Long.valueOf(durationMs));
        update.put("content", textBlock(truncate(result, 5000)));
        updates.add(update);
    }

    @Override
    public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
        if (assistant.length() == 0 && StrUtil.isNotBlank(finalReply)) {
            assistant.append(finalReply);
            Map<String, Object> update = new LinkedHashMap<String, Object>();
            update.put("session_update", "agent_message_chunk");
            update.put("type", "agent_message_chunk");
            update.put("content", textBlock(finalReply));
            updates.add(update);
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

    public List<Map<String, Object>> updates() {
        return new ArrayList<Map<String, Object>>(updates);
    }

    private String nextToolCallId(String toolName) {
        toolSequence += 1;
        return "tc-" + StrUtil.blankToDefault(toolName, "tool").replaceAll("[^A-Za-z0-9_.-]+", "-")
                + "-"
                + toolSequence;
    }

    private String toolKind(String toolName) {
        String name = StrUtil.nullToEmpty(toolName);
        if ("read_file".equals(name) || "skill_view".equals(name) || "skills_list".equals(name)) {
            return "read";
        }
        if ("write_file".equals(name) || "patch".equals(name) || "skill_manage".equals(name)) {
            return "edit";
        }
        if ("search_files".equals(name) || "session_search".equals(name)) {
            return "search";
        }
        if ("terminal".equals(name)
                || "execute_shell".equals(name)
                || "process".equals(name)
                || "execute_code".equals(name)
                || "delegate_task".equals(name)) {
            return "execute";
        }
        if ("web_search".equals(name) || "web_extract".equals(name)) {
            return "fetch";
        }
        return "other";
    }

    private String toolTitle(String toolName, Map<String, Object> args) {
        String name = StrUtil.blankToDefault(toolName, "tool");
        Object command = args == null ? null : args.get("command");
        Object path = args == null ? null : args.get("path");
        Object query = args == null ? null : args.get("query");
        if (command != null && StrUtil.isNotBlank(String.valueOf(command))) {
            return name + ": " + truncate(String.valueOf(command), 100);
        }
        if (path != null && StrUtil.isNotBlank(String.valueOf(path))) {
            return name + ": " + String.valueOf(path);
        }
        if (query != null && StrUtil.isNotBlank(String.valueOf(query))) {
            return name + ": " + truncate(String.valueOf(query), 100);
        }
        return name;
    }

    private Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", StrUtil.nullToEmpty(text));
        return block;
    }

    private String truncate(String value, int limit) {
        String text = StrUtil.nullToEmpty(value);
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 40))
                + "\n... ("
                + text.length()
                + " chars total, truncated)";
    }
}
