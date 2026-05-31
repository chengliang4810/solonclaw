package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.IOException;
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
    private MessageSupport() {}

    /** 将 NDJSON 反序列化为消息列表。 */
    public static List<ChatMessage> loadMessages(String ndjson) throws IOException {
        if (StrUtil.isBlank(ndjson)) {
            return new ArrayList<ChatMessage>();
        }

        return new ArrayList<ChatMessage>(ChatMessage.fromNdjson(ndjson));
    }

    /** 将消息列表序列化为 NDJSON。 */
    public static String toNdjson(List<ChatMessage> messages) throws IOException {
        return ChatMessage.toNdjson(messages);
    }

    /** 修复发给模型前的消息序列，避免孤儿 tool 消息或连续 user 消息破坏协议。 */
    public static int repairMessageSequence(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int repairs = dropStrayToolMessages(messages);
        repairs += dropUnansweredAssistantToolCalls(messages);
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

    private static Set<String> followingToolResultIds(List<ChatMessage> messages, int assistantIndex) {
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

    private static String rawToolCallId(Map raw) {
        if (raw == null) {
            return null;
        }
        Object id = raw.get("id");
        return id == null ? null : String.valueOf(id);
    }

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

    private static boolean hasThinkingSignature(Object contentRaw) {
        if (!(contentRaw instanceof Map)) {
            return false;
        }
        Object signature = ((Map<?, ?>) contentRaw).get("thinkingSignature");
        return signature instanceof String && StrUtil.isNotBlank((String) signature);
    }

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
                merged.add(ChatMessage.ofUser(mergeText(previous.getContent(), message.getContent())));
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

    private static boolean canMergeUser(UserMessage message) {
        if (message == null || (message.getMetadata() != null && !message.getMetadata().isEmpty())) {
            return false;
        }
        List<ContentBlock> blocks = message.getBlocks();
        return blocks == null || blocks.size() <= 1;
    }

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
