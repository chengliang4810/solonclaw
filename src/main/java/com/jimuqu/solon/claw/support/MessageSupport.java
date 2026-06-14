package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 会话消息 NDJSON 辅助工具。 */
public final class MessageSupport {
    /** 创建消息辅助实例。 */
    private MessageSupport() {}

    /** 将 NDJSON 反序列化为消息列表。 */
    public static List<ChatMessage> loadMessages(String ndjson) throws IOException {
        if (StrUtil.isBlank(ndjson)) {
            return new ArrayList<ChatMessage>();
        }

        return loadMessagesByLine(ndjson);
    }

    /** 将消息列表序列化为 NDJSON。 */
    public static String toNdjson(List<ChatMessage> messages) throws IOException {
        return ChatMessage.toNdjson(messages);
    }

    /**
     * 直接按 Java 字符串逐行恢复历史消息，避免 Solon AI 整段 NDJSON 读取路径在 Windows 默认字符集下误读 UTF-8 字节。
     *
     * @param ndjson 历史会话 NDJSON。
     * @return 返回恢复后的消息列表。
     */
    private static List<ChatMessage> loadMessagesByLine(String ndjson) throws IOException {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        BufferedReader reader = new BufferedReader(new StringReader(ndjson));
        String line;
        int lineNumber = 0;
        try {
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (StrUtil.isBlank(line)) {
                    continue;
                }
                messages.add(ChatMessage.fromJson(line));
            }
            return messages;
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse session ndjson line " + lineNumber, e);
        }
    }

    /** 修复发给模型前的消息序列，避免孤儿 tool 消息或连续 user 消息破坏协议。 */
    public static int repairMessageSequence(List<ChatMessage> messages) {
        return repairMessageSequence(messages, false);
    }

    /**
     * 修复发给模型前的消息序列。
     *
     * @param messages 消息列表。
     * @param preserveUnansweredToolCalls 是否保留尚未回答的assistant tool_call。
     * @return 返回修复数量。
     */
    public static int repairMessageSequence(
            List<ChatMessage> messages, boolean preserveUnansweredToolCalls) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int repairs = dropStrayToolMessages(messages);
        if (!preserveUnansweredToolCalls) {
            repairs += dropUnansweredAssistantToolCalls(messages);
        }
        repairs += mergeConsecutiveTextUsers(messages);
        return repairs;
    }

    /** 统计消息数量。 */
    public static int countMessages(String ndjson) throws IOException {
        return loadMessages(ndjson).size();
    }

    /** 获取最近一条用户消息。 */
    public static String getLastUserMessage(String ndjson) throws IOException {
        List<ChatMessage> messages = loadMessages(ndjson);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == ChatRole.USER) {
                return message.getContent();
            }
        }

        return null;
    }

    /** 删除最后一轮用户交互，用于 `/undo` 与 `/retry`。 */
    public static String removeLastTurn(String ndjson) throws IOException {
        List<ChatMessage> messages = loadMessages(ndjson);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == ChatRole.SYSTEM) {
                continue;
            }

            messages.remove(i);
            if (message.getRole() == ChatRole.USER) {
                break;
            }
        }

        return toNdjson(messages);
    }

    /**
     * 执行dropStray工具Messages相关逻辑。
     *
     * @param messages messages 参数。
     * @return 返回drop Stray工具Messages结果。
     */
    private static int dropStrayToolMessages(List<ChatMessage> messages) {
        int repairs = 0;
        Set<String> knownToolCallIds = new HashSet<String>();
        List<ChatMessage> filtered = new ArrayList<ChatMessage>(messages.size());
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            ChatRole role = message.getRole();
            if (role == ChatRole.ASSISTANT) {
                knownToolCallIds.clear();
                if (message instanceof AssistantMessage) {
                    List<ToolCall> toolCalls = ((AssistantMessage) message).getToolCalls();
                    if (toolCalls != null) {
                        for (ToolCall toolCall : toolCalls) {
                            if (toolCall != null && StrUtil.isNotBlank(toolCall.getId())) {
                                knownToolCallIds.add(toolCall.getId());
                            }
                        }
                    }
                }
                filtered.add(message);
            } else if (role == ChatRole.TOOL) {
                String toolCallId =
                        message instanceof ToolMessage
                                ? ((ToolMessage) message).getToolCallId()
                                : null;
                if (StrUtil.isNotBlank(toolCallId) && knownToolCallIds.contains(toolCallId)) {
                    filtered.add(message);
                } else {
                    repairs++;
                }
            } else {
                if (role == ChatRole.USER) {
                    knownToolCallIds.clear();
                }
                filtered.add(message);
            }
        }
        if (repairs > 0) {
            messages.clear();
            messages.addAll(filtered);
        }
        return repairs;
    }

    /**
     * 执行dropUnansweredAssistant工具Calls相关逻辑。
     *
     * @param messages messages 参数。
     * @return 返回drop Unanswered Assistant工具Calls结果。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int dropUnansweredAssistantToolCalls(List<ChatMessage> messages) {
        int repairs = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!(message instanceof AssistantMessage)) {
                continue;
            }
            AssistantMessage assistant = (AssistantMessage) message;
            List<ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                continue;
            }
            Set<String> answered = followingToolResultIds(messages, i);
            List<ToolCall> keptCalls = new ArrayList<ToolCall>();
            for (ToolCall toolCall : toolCalls) {
                if (toolCall != null
                        && StrUtil.isNotBlank(toolCall.getId())
                        && answered.contains(toolCall.getId())) {
                    keptCalls.add(toolCall);
                }
            }
            if (keptCalls.size() == toolCalls.size()) {
                continue;
            }
            repairs += toolCalls.size() - keptCalls.size();
            List<Map> keptRawCalls = filterRawToolCalls(assistant.getToolCallsRaw(), answered);
            messages.set(i, rebuildAssistantAfterToolPrune(assistant, keptCalls, keptRawCalls));
        }
        return repairs;
    }

    /**
     * 执行following工具结果标识相关逻辑。
     *
     * @param messages messages 参数。
     * @param assistantIndex assistant索引参数。
     * @return 返回following工具结果标识。
     */
    private static Set<String> followingToolResultIds(
            List<ChatMessage> messages, int assistantIndex) {
        Set<String> answered = new HashSet<String>();
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            ChatMessage next = messages.get(i);
            if (next == null || next.getRole() == ChatRole.SYSTEM) {
                continue;
            }
            if (next instanceof ToolMessage) {
                String toolCallId = ((ToolMessage) next).getToolCallId();
                if (StrUtil.isNotBlank(toolCallId)) {
                    answered.add(toolCallId);
                }
                continue;
            }
            break;
        }
        return answered;
    }

    /**
     * 执行过滤器原始工具Calls相关逻辑。
     *
     * @param rawCalls 原始Calls参数。
     * @param answered answered 参数。
     * @return 返回filter原始工具Calls结果。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Map> filterRawToolCalls(List<Map> rawCalls, Set<String> answered) {
        if (rawCalls == null || rawCalls.isEmpty()) {
            return rawCalls;
        }
        List<Map> kept = new ArrayList<Map>();
        for (Map raw : rawCalls) {
            String id = rawToolCallId(raw);
            if (StrUtil.isNotBlank(id) && answered.contains(id)) {
                kept.add(raw);
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    /**
     * 执行原始工具Call标识相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回原始工具Call标识。
     */
    private static String rawToolCallId(Map raw) {
        if (raw == null) {
            return null;
        }
        Object id = raw.get("id");
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 执行rebuildAssistantAfter工具Prune相关逻辑。
     *
     * @param assistant assistant 参数。
     * @param keptCalls keptCalls 参数。
     * @param keptRawCalls kept原始Calls参数。
     * @return 返回rebuild Assistant After工具Prune结果。
     */
    private static AssistantMessage rebuildAssistantAfterToolPrune(
            AssistantMessage assistant, List<ToolCall> keptCalls, List<Map> keptRawCalls) {
        boolean demoteThinking = hasThinkingSignature(assistant.getContentRaw());
        String content =
                demoteThinking ? demotedThinkingContent(assistant) : assistant.getContent();
        Object contentRaw = demoteThinking ? null : assistant.getContentRaw();
        return new AssistantMessage(
                content,
                false,
                contentRaw,
                keptRawCalls == null || keptRawCalls.isEmpty() ? null : keptRawCalls,
                keptCalls == null || keptCalls.isEmpty() ? null : keptCalls,
                assistant.getSearchResultsRaw());
    }

    /**
     * 判断是否存在Thinking签名。
     *
     * @param contentRaw content原始参数。
     * @return 如果Thinking签名满足条件则返回 true，否则返回 false。
     */
    private static boolean hasThinkingSignature(Object contentRaw) {
        if (!(contentRaw instanceof Map)) {
            return false;
        }
        Object signature = ((Map<?, ?>) contentRaw).get("thinkingSignature");
        return signature instanceof String && StrUtil.isNotBlank((String) signature);
    }

    /**
     * 执行demoted思考Content相关逻辑。
     *
     * @param assistant assistant 参数。
     * @return 返回demoted Thinking Content结果。
     */
    private static String demotedThinkingContent(AssistantMessage assistant) {
        String content = StrUtil.nullToEmpty(assistant.getContent());
        String reasoning = StrUtil.nullToEmpty(assistant.getReasoning()).trim();
        String visible = StrUtil.nullToEmpty(assistant.getResultContent()).trim();
        if (StrUtil.isBlank(reasoning)) {
            return visible;
        }
        if (StrUtil.isBlank(visible)) {
            return reasoning;
        }
        return reasoning + "\n\n" + visible;
    }

    /**
     * 合并Consecutive Text Users。
     *
     * @param messages messages 参数。
     * @return 返回Consecutive Text Users结果。
     */
    private static int mergeConsecutiveTextUsers(List<ChatMessage> messages) {
        int repairs = 0;
        List<ChatMessage> merged = new ArrayList<ChatMessage>(messages.size());
        for (ChatMessage message : messages) {
            if (!merged.isEmpty()
                    && message instanceof UserMessage
                    && merged.get(merged.size() - 1) instanceof UserMessage
                    && canMergeUser((UserMessage) merged.get(merged.size() - 1))
                    && canMergeUser((UserMessage) message)) {
                UserMessage previous = (UserMessage) merged.remove(merged.size() - 1);
                merged.add(
                        ChatMessage.ofUser(mergeText(previous.getContent(), message.getContent())));
                repairs++;
            } else {
                merged.add(message);
            }
        }
        if (repairs > 0) {
            messages.clear();
            messages.addAll(merged);
        }
        return repairs;
    }

    /**
     * 判断是否可以Merge用户。
     *
     * @param message 平台消息或错误消息。
     * @return 如果Merge用户满足条件则返回 true，否则返回 false。
     */
    private static boolean canMergeUser(UserMessage message) {
        if (message == null
                || (message.getMetadata() != null && !message.getMetadata().isEmpty())) {
            return false;
        }
        List<ContentBlock> blocks = message.getBlocks();
        return blocks == null || blocks.size() <= 1;
    }

    /**
     * 合并Text。
     *
     * @param previous previous 参数。
     * @param current current 参数。
     * @return 返回Text结果。
     */
    private static String mergeText(String previous, String current) {
        String left = StrUtil.nullToEmpty(previous);
        String right = StrUtil.nullToEmpty(current);
        if (StrUtil.isBlank(left)) {
            return right;
        }
        if (StrUtil.isBlank(right)) {
            return left;
        }
        return left + "\n\n" + right;
    }
}
