package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
