package com.jimuqu.solon.claw.engine;

import cn.hutool.core.collection.CollUtil;
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

/** 修复历史消息中损坏的工具调用参数，避免压缩或重放时把非法 JSON 再次交给模型运行链路。 */
public final class ToolCallArgumentSanitizer {
    /** 写入工具消息的修复标记，用于让后续压缩和诊断知道参数已被替换为安全空对象。 */
    public static final String CORRUPTION_MARKER =
            "[Tool call arguments were corrupted or truncated; arguments were replaced with {}.]";

    /** 工具类只提供静态修复方法，不允许实例化。 */
    private ToolCallArgumentSanitizer() {}

    /**
     * 扫描消息列表并修复 Assistant 工具调用中的非法 JSON 参数。
     *
     * @param messages 待就地修复的会话消息列表。
     * @return 返回被修复的工具调用数量。
     */
    public static int sanitize(List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
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
            if (CollUtil.isEmpty(corrupted)) {
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
     * 修复单条 Assistant 消息中的原始工具调用参数。
     *
     * @return 返回被判定为损坏的工具调用信息。
     */
    @SuppressWarnings("unchecked")
    private static List<CorruptedCall> sanitizeAssistant(AssistantMessage message) {
        List<CorruptedCall> corrupted = new ArrayList<CorruptedCall>();
        List<Map> rawCalls = message.getToolCallsRaw();
        if (CollUtil.isEmpty(rawCalls)) {
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
            if (!(arguments instanceof String)) {
                continue;
            }
            String text = (String) arguments;
            if (StrUtil.isEmpty(text)) {
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
     * 用修复后的原始工具调用重新创建 Assistant 消息，同时同步结构化 ToolCall。
     *
     * @param message 原始 Assistant 消息。
     * @param corrupted 已修复的工具调用信息。
     * @return 返回保持其他字段不变的新 Assistant 消息。
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
     * 同步修复结构化 ToolCall 参数，确保 raw 与 structured 两套表示一致。
     *
     * @param toolCalls Assistant 消息中的结构化工具调用。
     * @param corrupted 已修复的原始工具调用信息。
     * @return 返回修复后的结构化工具调用列表；无需修复时复用原列表。
     */
    private static List<ToolCall> sanitizeStructuredToolCalls(
            List<ToolCall> toolCalls, List<CorruptedCall> corrupted) {
        if (CollUtil.isEmpty(toolCalls)) {
            return toolCalls;
        }
        Set<String> corruptedIds = new HashSet<String>();
        for (CorruptedCall call : corrupted) {
            if (StrUtil.isNotBlank(call.toolCallId)) {
                corruptedIds.add(call.toolCallId);
            }
        }
        if (CollUtil.isEmpty(corruptedIds)) {
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
     * 判断工具参数文本是否为 JSON 对象；数组或标量都不能作为工具参数根结构。
     *
     * @param text 待处理文本。
     * @return 文本可解析为 Map 时返回 true。
     */
    private static boolean isValidJsonObject(String text) {
        try {
            return ONode.deserialize(text, Object.class) instanceof Map;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断下一条消息是否是当前工具调用对应的 ToolMessage。
     *
     * @param toolCallId 工具Call标识。
     * @return ToolMessage 的 tool_call_id 匹配时返回 true。
     */
    private static boolean isMatchingToolMessage(ChatMessage message, String toolCallId) {
        return message instanceof ToolMessage
                && StrUtil.equals(((ToolMessage) message).getToolCallId(), toolCallId);
    }

    /**
     * 在原工具结果前追加修复标记，避免重复插入同一个标记。
     *
     * @param original 原始 ToolMessage。
     * @return 返回带修复标记的 ToolMessage。
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
     * 从 Solon AI 原始工具调用 Map 中读取工具调用标识。
     *
     * @param raw 原始输入值。
     * @return 返回工具调用标识；缺失时返回 null。
     */
    private static String toolCallId(Map raw) {
        Object id = raw.get("id");
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 从 function Map 中读取工具名称，缺失时使用通用名称保证 ToolMessage 可构造。
     *
     * @param functionMap function映射参数。
     * @return 返回工具名称。
     */
    private static String toolName(Map functionMap) {
        Object name = functionMap.get("name");
        return name == null ? "tool" : String.valueOf(name);
    }

    /** 记录一次被修复的工具调用，供 ToolMessage 标记和结构化 ToolCall 同步使用。 */
    private static class CorruptedCall {
        /** 被修复工具调用的 id，用于定位后续 ToolMessage。 */
        private final String toolCallId;

        /** 被修复工具调用的名称，缺失时会回退为 tool。 */
        private final String toolName;

        /**
         * 创建被修复工具调用记录。
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
