package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 会话消息 NDJSON 辅助工具。 */
public final class MessageSupport {
    /** 模型可能内嵌在正文中的闭合思考块。 */
    private static final Pattern THINK_BLOCK_PATTERN =
            Pattern.compile(
                    "<\\s*(think|thinking|reasoning|thought|reasoning_scratchpad)\\b[^>]*>[\\s\\S]*?</\\s*\\1\\s*>",
                    Pattern.CASE_INSENSITIVE);

    /** 位于文本起始或新行后的未闭合思考块；从标签起均不可对用户展示。 */
    private static final Pattern UNTERMINATED_THINK_PATTERN =
            Pattern.compile(
                    "(?:^|\\R)[ \\t]*<\\s*(?:think|thinking|reasoning|thought|reasoning_scratchpad)\\b[^>]*>[\\s\\S]*$",
                    Pattern.CASE_INSENSITIVE);

    /** 清理闭合块后残留的孤立思考标签。 */
    private static final Pattern ORPHAN_THINK_TAG_PATTERN =
            Pattern.compile(
                    "</?\\s*(?:think|thinking|reasoning|thought|reasoning_scratchpad)\\s*>\\s*",
                    Pattern.CASE_INSENSITIVE);

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
     * 构造失败切换前的安全会话快照：没有新增工具结果时整轮回滚，有新增工具结果时仅保留到最后一条完整 TOOL。
     *
     * @param previousNdjson 当前候选执行前的会话快照。
     * @param currentNdjson 当前候选执行后的会话快照。
     * @return 可交给备用模型继续的 NDJSON；解析失败时返回原快照。
     */
    public static String safeFallbackNdjson(String previousNdjson, String currentNdjson) {
        try {
            List<ChatMessage> previous = loadMessages(previousNdjson);
            List<ChatMessage> current = loadMessages(currentNdjson);
            int previousToolCount = countRole(previous, ChatRole.TOOL);
            if (countRole(current, ChatRole.TOOL) <= previousToolCount) {
                return previousNdjson;
            }
            for (int i = current.size() - 1; i >= 0; i--) {
                if (current.get(i).getRole() == ChatRole.TOOL) {
                    return toNdjson(new ArrayList<ChatMessage>(current.subList(0, i + 1)));
                }
            }
        } catch (Exception ignored) {
            // 无法证明工具结果完整时必须整轮回滚，避免备用模型基于损坏历史重放副作用。
        }
        return previousNdjson;
    }

    /** 统计指定角色消息数量。 */
    private static int countRole(List<ChatMessage> messages, ChatRole role) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message != null && message.getRole() == role) {
                count++;
            }
        }
        return count;
    }

    /**
     * 逐行恢复当前 NDJSON 消息。
     *
     * @param ndjson 原始会话快照。
     * @return 返回解析后的消息列表。
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
        repairs += dropDuplicateAdjacentAssistantToolCalls(messages);
        if (!preserveUnansweredToolCalls) {
            repairs += dropUnansweredAssistantToolCalls(messages);
        }
        repairs += dropDuplicateAssistantToolPreambles(messages);
        repairs += dropEmptyAssistantMessages(messages);
        repairs += mergeConsecutiveTextUsers(messages);
        return repairs;
    }

    /**
     * 清理当前压缩摘要残留，避免摘要作为普通消息再次进入模型上下文或 Dashboard 对话流。
     *
     * @param messages 会话消息列表。
     * @param compressedSummary 当前会话独立保存的压缩摘要。
     * @return 返回被清理的消息数量。
     */
    public static int dropCurrentSummaryArtifacts(
            List<ChatMessage> messages, String compressedSummary) {
        if (messages == null || messages.isEmpty() || StrUtil.isBlank(compressedSummary)) {
            return 0;
        }
        int repairs = 0;
        List<ChatMessage> filtered = new ArrayList<ChatMessage>(messages.size());
        for (ChatMessage message : messages) {
            if (isCurrentSummaryArtifactMessage(message)) {
                repairs++;
            } else {
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
     * 判断单条消息是否为当前压缩摘要残留；用户消息始终保留，避免误删真实输入。
     *
     * @param message 待检查的会话消息。
     * @return 如果消息应从活跃上下文中移除则返回 true。
     */
    private static boolean isCurrentSummaryArtifactMessage(ChatMessage message) {
        if (message == null || message.getRole() == ChatRole.USER) {
            return false;
        }
        if (CompressionConstants.isCurrentSummaryArtifact(message.getContent())) {
            return true;
        }
        if (message instanceof AssistantMessage) {
            AssistantMessage assistant = (AssistantMessage) message;
            return CompressionConstants.isCurrentSummaryArtifact(assistant.getResultContent())
                    || CompressionConstants.isCurrentSummaryArtifact(assistant.getReasoning());
        }
        return false;
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
     * 清理相邻重复的 assistant tool_call，避免流式聚合和会话快照同时写入同一工具调用。
     *
     * @param messages 会话消息列表。
     * @return 返回清理数量。
     */
    private static int dropDuplicateAdjacentAssistantToolCalls(List<ChatMessage> messages) {
        int repairs = 0;
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage previous = messages.get(i - 1);
            ChatMessage current = messages.get(i);
            if (!sameAssistantToolCalls(previous, current)) {
                continue;
            }
            AssistantMessage previousAssistant = (AssistantMessage) previous;
            AssistantMessage currentAssistant = (AssistantMessage) current;
            if (assistantInformationScore(currentAssistant)
                    > assistantInformationScore(previousAssistant)) {
                messages.set(i - 1, currentAssistant);
            }
            messages.remove(i);
            repairs++;
            i--;
        }
        return repairs;
    }

    /**
     * 判断两条消息是否是同一批 assistant 工具调用。
     *
     * @param previous 已存在的消息。
     * @param current 当前待检查消息。
     * @return 如果工具调用签名完全一致则返回 true。
     */
    public static boolean sameAssistantToolCalls(ChatMessage previous, ChatMessage current) {
        if (!(previous instanceof AssistantMessage) || !(current instanceof AssistantMessage)) {
            return false;
        }
        List<ToolCall> previousCalls = ((AssistantMessage) previous).getToolCalls();
        List<ToolCall> currentCalls = ((AssistantMessage) current).getToolCalls();
        if (previousCalls == null
                || currentCalls == null
                || previousCalls.isEmpty()
                || previousCalls.size() != currentCalls.size()) {
            return false;
        }
        for (int i = 0; i < previousCalls.size(); i++) {
            if (!sameToolCall(previousCalls.get(i), currentCalls.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 比较工具调用签名；相同签名代表同一个模型工具调用，不能在上下文里重复出现。
     *
     * @param previous 已存在的工具调用。
     * @param current 当前待检查工具调用。
     * @return 如果签名一致则返回 true。
     */
    private static boolean sameToolCall(ToolCall previous, ToolCall current) {
        if (previous == null || current == null) {
            return false;
        }
        return StrUtil.equals(previous.getIndex(), current.getIndex())
                && StrUtil.equals(previous.getId(), current.getId())
                && StrUtil.equals(previous.getName(), current.getName())
                && StrUtil.equals(previous.getArgumentsStr(), current.getArgumentsStr())
                && StrUtil.equals(
                        String.valueOf(previous.getArguments()),
                        String.valueOf(current.getArguments()));
    }

    /**
     * 计算 assistant 工具调用消息的信息量，去重时优先保留内容更完整的一条。
     *
     * @param message assistant 消息。
     * @return 返回可比较的信息量分数。
     */
    public static int assistantInformationScore(AssistantMessage message) {
        if (message == null) {
            return 0;
        }
        return StrUtil.nullToEmpty(message.getContent()).length()
                + StrUtil.nullToEmpty(message.getResultContent()).length()
                + StrUtil.nullToEmpty(message.getReasoning()).length()
                + (message.getContentRaw() == null ? 0 : 1)
                + (message.getToolCallsRaw() == null ? 0 : message.getToolCallsRaw().size());
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
            if (shouldDropPrunedAssistant(assistant, keptCalls)) {
                messages.remove(i);
                i--;
                repairs++;
                continue;
            }
            List<Map> keptRawCalls = filterRawToolCalls(assistant.getToolCallsRaw(), answered);
            messages.set(i, rebuildAssistantAfterToolPrune(assistant, keptCalls, keptRawCalls));
        }
        return repairs;
    }

    /**
     * 清理流式工具调用前置文本重复写入：同一可见文本只保留携带 tool_call 的 assistant。
     *
     * @param messages 消息列表。
     * @return 返回清理数量。
     */
    private static int dropDuplicateAssistantToolPreambles(List<ChatMessage> messages) {
        int repairs = 0;
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage current = messages.get(i);
            ChatMessage previous = messages.get(i - 1);
            if (!(current instanceof AssistantMessage) || !(previous instanceof AssistantMessage)) {
                continue;
            }
            AssistantMessage currentAssistant = (AssistantMessage) current;
            AssistantMessage previousAssistant = (AssistantMessage) previous;
            if (!hasToolCalls(currentAssistant)
                    || hasToolCalls(previousAssistant)
                    || !sameVisibleContent(previousAssistant, currentAssistant)) {
                continue;
            }
            messages.remove(i - 1);
            repairs++;
            i--;
        }
        return repairs;
    }

    /**
     * 判断 assistant 是否携带工具调用。
     *
     * @param message assistant 消息。
     * @return 如果存在工具调用则返回 true。
     */
    private static boolean hasToolCalls(AssistantMessage message) {
        return message != null
                && message.getToolCalls() != null
                && !message.getToolCalls().isEmpty();
    }

    /**
     * 判断两条 assistant 的用户可见文本是否一致。
     *
     * @param left 左侧消息。
     * @param right 右侧消息。
     * @return 如果文本相同则返回 true。
     */
    public static boolean sameVisibleContent(AssistantMessage left, AssistantMessage right) {
        String leftText = StrUtil.nullToEmpty(left.getContent()).trim();
        String rightText = StrUtil.nullToEmpty(right.getContent()).trim();
        return StrUtil.isNotBlank(leftText) && StrUtil.equals(leftText, rightText);
    }

    /**
     * 判断剪枝后是否应删除 assistant 消息，避免只剩推理标签的空 assistant 破坏模型协议。
     *
     * @param assistant 原始 assistant 消息。
     * @param keptCalls 保留下来的工具调用。
     * @return 如果剪枝后不再有可发送内容则返回 true。
     */
    private static boolean shouldDropPrunedAssistant(
            AssistantMessage assistant, List<ToolCall> keptCalls) {
        if (keptCalls != null && !keptCalls.isEmpty()) {
            return false;
        }
        return StrUtil.isBlank(assistant.getResultContent());
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
     * 删除历史中没有可见正文、没有推理内容、也没有工具调用的 assistant 占位消息，避免兼容 OpenAI 协议的模型拒绝请求。
     *
     * <p>工具调用修复可能会把重复 assistant tool_call 剪成只剩 {@code <think>...</think>} 的内部思考消息；这类消息再次发送给模型时没有可见
     * content，也没有 tool_calls，应当从历史上下文中移除。
     *
     * @param messages 会话消息列表。
     * @return 返回删除数量。
     */
    private static int dropEmptyAssistantMessages(List<ChatMessage> messages) {
        int repairs = 0;
        List<ChatMessage> filtered = new ArrayList<ChatMessage>(messages.size());
        for (ChatMessage message : messages) {
            if (isEmptyAssistantMessage(message)) {
                repairs++;
                continue;
            }
            filtered.add(message);
        }
        if (repairs > 0) {
            messages.clear();
            messages.addAll(filtered);
        }
        return repairs;
    }

    /**
     * 判断 assistant 消息是否没有任何可发送给模型的有效内容。
     *
     * @param message 待检查消息。
     * @return 如果是空 assistant 占位消息则返回 true。
     */
    private static boolean isEmptyAssistantMessage(ChatMessage message) {
        if (!(message instanceof AssistantMessage)) {
            return false;
        }
        AssistantMessage assistant = (AssistantMessage) message;
        if ((assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty())
                || (assistant.getToolCallsRaw() != null
                        && !assistant.getToolCallsRaw().isEmpty())) {
            return false;
        }
        if (StrUtil.isNotBlank(assistant.getResultContent())) {
            return false;
        }
        return StrUtil.isBlank(visibleAssistantContent(assistant.getContent()));
    }

    /**
     * 从助手消息中读取可用文本，优先使用模型聚合后的结果正文。
     *
     * @param assistantMessage 助手消息。
     * @return 去除首尾空白后的助手文本。
     */
    public static String assistantText(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return "";
        }
        if (StrUtil.isNotBlank(assistantMessage.getResultContent())) {
            return visibleText(assistantMessage.getResultContent());
        }
        return visibleText(assistantMessage.getContent());
    }

    /** 清理用户可见文本中的思考块，保留标签外的正式答复。 */
    public static String visibleText(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String value = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        value = UNTERMINATED_THINK_PATTERN.matcher(value).replaceAll("");
        value = ORPHAN_THINK_TAG_PATTERN.matcher(value).replaceAll("");
        return value.trim();
    }

    /**
     * 生成可安全持久化的助手消息副本，剥离供应商混入正文或原始载荷的私有推理内容。
     *
     * <p>工具调用与搜索结果必须保留，确保恢复后的工具协议序列仍然完整。
     *
     * @param message 原始助手消息。
     * @return 不含私有推理载荷的持久化消息。
     */
    public static AssistantMessage assistantForPersistence(AssistantMessage message) {
        if (message == null) {
            return null;
        }
        return new AssistantMessage(
                visibleText(message.getContent()),
                false,
                null,
                message.getToolCallsRaw(),
                message.getToolCalls(),
                message.getSearchResultsRaw());
    }

    /**
     * 提取 assistant 正文中可见给用户的部分，去掉模型历史里遗留的内部思考块。
     *
     * @param content assistant 原始正文。
     * @return 返回去除 think 块后的可见正文。
     */
    private static String visibleAssistantContent(String content) {
        return visibleText(content);
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
