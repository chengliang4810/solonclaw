package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** Repairs corrupted assistant tool-call arguments in persisted history. */
public final class ToolCallArgumentSanitizer {
    public static final String CORRUPTION_MARKER =
            "[Tool call arguments were corrupted or truncated; arguments were replaced with {}.]";

    private ToolCallArgumentSanitizer() {}

    public static int sanitize(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int repaired = 0;
        int i = 0;
        while (i < messages.size()) {
            ChatMessage message = messages.get(i);
            if (!(message instanceof AssistantMessage)) {
                i++;
                continue;
            }
            List<CorruptedCall> corrupted = sanitizeAssistant((AssistantMessage) message);
            if (corrupted.isEmpty()) {
                i++;
                continue;
            }
            messages.set(i, rebuildAssistantWithSanitizedToolCalls((AssistantMessage) message, corrupted));
            int insertAt = i + 1;
            for (CorruptedCall call : corrupted) {
                repaired++;
                if (insertAt < messages.size()
                        && isMatchingToolMessage(messages.get(insertAt), call.toolCallId)) {
                    messages.set(insertAt, prependMarker((ToolMessage) messages.get(insertAt)));
                    insertAt++;
                } else {
                    messages.add(
                            insertAt,
                            ChatMessage.ofTool(CORRUPTION_MARKER, call.toolName, call.toolCallId));
                    insertAt++;
                }
            }
            i = insertAt;
        }
        return repaired;
    }

    @SuppressWarnings("unchecked")
    private static List<CorruptedCall> sanitizeAssistant(AssistantMessage message) {
        List<CorruptedCall> corrupted = new ArrayList<CorruptedCall>();
        List<Map> rawCalls = message.getToolCallsRaw();
        if (rawCalls == null || rawCalls.isEmpty()) {
            return corrupted;
        }
        for (Map raw : rawCalls) {
            if (raw == null) {
                continue;
            }
            Object function = raw.get("function");
            if (!(function instanceof Map)) {
                continue;
            }
            Map functionMap = (Map) function;
            Object arguments = functionMap.get("arguments");
            if (arguments == null || !(arguments instanceof String)) {
                continue;
            }
            String text = (String) arguments;
            if (text.length() == 0) {
                functionMap.put("arguments", "{}");
                continue;
            }
            if (isValidJsonObject(text)) {
                continue;
            }
            functionMap.put("arguments", "{}");
            corrupted.add(new CorruptedCall(toolCallId(raw), toolName(functionMap)));
        }
        return corrupted;
    }

    private static AssistantMessage rebuildAssistantWithSanitizedToolCalls(
            AssistantMessage message, List<CorruptedCall> corrupted) {
        return new AssistantMessage(
                message.getContent(),
                message.isThinking(),
                message.getContentRaw(),
                message.getToolCallsRaw(),
                sanitizeStructuredToolCalls(message.getToolCalls(), corrupted),
                message.getSearchResultsRaw());
    }

    private static List<ToolCall> sanitizeStructuredToolCalls(
            List<ToolCall> toolCalls, List<CorruptedCall> corrupted) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return toolCalls;
        }
        Set<String> corruptedIds = new HashSet<String>();
        for (CorruptedCall call : corrupted) {
            if (StrUtil.isNotBlank(call.toolCallId)) {
                corruptedIds.add(call.toolCallId);
            }
        }
        if (corruptedIds.isEmpty()) {
            return toolCalls;
        }
        List<ToolCall> sanitized = new ArrayList<ToolCall>(toolCalls.size());
        for (ToolCall toolCall : toolCalls) {
            if (toolCall != null && corruptedIds.contains(toolCall.getId())) {
                ToolCall replacement =
                        new ToolCall(
                                toolCall.getIndex(),
                                toolCall.getId(),
                                toolCall.getName(),
                                "{}",
                                Collections.<String, Object>emptyMap());
                replacement.setThoughtSignature(toolCall.getThoughtSignature());
                sanitized.add(replacement);
            } else {
                sanitized.add(toolCall);
            }
        }
        return sanitized;
    }

    private static boolean isValidJsonObject(String text) {
        try {
            return ONode.deserialize(text, Object.class) instanceof Map;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isMatchingToolMessage(ChatMessage message, String toolCallId) {
        return message instanceof ToolMessage
                && StrUtil.equals(((ToolMessage) message).getToolCallId(), toolCallId);
    }

    private static ToolMessage prependMarker(ToolMessage original) {
        String content = StrUtil.nullToEmpty(original.getContent());
        if (StrUtil.startWith(content, CORRUPTION_MARKER)) {
            return original;
        }
        String next =
                StrUtil.isBlank(content)
                        ? CORRUPTION_MARKER
                        : CORRUPTION_MARKER + "\n" + content;
        return ChatMessage.ofTool(next, original.getName(), original.getToolCallId());
    }

    private static String toolCallId(Map raw) {
        Object id = raw.get("id");
        return id == null ? null : String.valueOf(id);
    }

    private static String toolName(Map functionMap) {
        Object name = functionMap.get("name");
        return name == null ? "tool" : String.valueOf(name);
    }

    private static class CorruptedCall {
        private final String toolCallId;
        private final String toolName;

        private CorruptedCall(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = StrUtil.blankToDefault(toolName, "tool");
        }
    }
}
