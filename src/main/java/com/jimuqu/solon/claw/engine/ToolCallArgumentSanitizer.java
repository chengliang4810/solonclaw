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

/** 承载工具Call参数清理器相关状态和辅助逻辑。 */
public final class ToolCallArgumentSanitizer {
    /** CORRUPTIONMARKER的统一常量值。 */
    public static final String CORRUPTION_MARKER =
            "[Tool call arguments were corrupted or truncated; arguments were replaced with {}.]";

    /** 创建工具Call参数清理器实例。 */
    private ToolCallArgumentSanitizer() {}

    /**
     * 执行清理相关逻辑。
     *
     * @param messages messages 参数。
     * @return 返回清理结果。
     */
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
            messages.set(
                    i,
                    rebuildAssistantWithSanitizedToolCalls((AssistantMessage) message, corrupted));
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

    /**
     * 清理Assistant。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Assistant结果。
     */
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

    /**
     * 执行rebuildAssistantWithSanitized工具Calls相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param corrupted corrupted 参数。
     * @return 返回rebuild Assistant With Sanitized工具Calls结果。
     */
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

    /**
     * 清理Structured工具Calls。
     *
     * @param toolCalls 工具Calls参数。
     * @param corrupted corrupted 参数。
     * @return 返回Structured工具Calls结果。
     */
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

    /**
     * 判断是否Valid JSON Object。
     *
     * @param text 待处理文本。
     * @return 如果Valid JSON Object满足条件则返回 true，否则返回 false。
     */
    private static boolean isValidJsonObject(String text) {
        try {
            return ONode.deserialize(text, Object.class) instanceof Map;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否Matching工具消息。
     *
     * @param message 平台消息或错误消息。
     * @param toolCallId 工具Call标识。
     * @return 如果Matching工具消息满足条件则返回 true，否则返回 false。
     */
    private static boolean isMatchingToolMessage(ChatMessage message, String toolCallId) {
        return message instanceof ToolMessage
                && StrUtil.equals(((ToolMessage) message).getToolCallId(), toolCallId);
    }

    /**
     * 执行prependMarker相关逻辑。
     *
     * @param original original 参数。
     * @return 返回prepend Marker结果。
     */
    private static ToolMessage prependMarker(ToolMessage original) {
        String content = StrUtil.nullToEmpty(original.getContent());
        if (StrUtil.startWith(content, CORRUPTION_MARKER)) {
            return original;
        }
        String next =
                StrUtil.isBlank(content) ? CORRUPTION_MARKER : CORRUPTION_MARKER + "\n" + content;
        return ChatMessage.ofTool(next, original.getName(), original.getToolCallId());
    }

    /**
     * 执行工具Call标识相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回工具Call标识。
     */
    private static String toolCallId(Map raw) {
        Object id = raw.get("id");
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 执行工具名称相关逻辑。
     *
     * @param functionMap function映射参数。
     * @return 返回工具名称结果。
     */
    private static String toolName(Map functionMap) {
        Object name = functionMap.get("name");
        return name == null ? "tool" : String.valueOf(name);
    }

    /** 承载CorruptedCall相关状态和辅助逻辑。 */
    private static class CorruptedCall {
        /** 记录CorruptedCall中的工具Call标识。 */
        private final String toolCallId;

        /** 记录CorruptedCall中的工具名称。 */
        private final String toolName;

        /**
         * 创建Corrupted Call实例，并注入运行所需依赖。
         *
         * @param toolCallId 工具Call标识。
         * @param toolName 工具名称。
         */
        private CorruptedCall(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = StrUtil.blankToDefault(toolName, "tool");
        }
    }
}
