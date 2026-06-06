package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

/** 提供会话Artifact相关业务能力，封装调用方不需要感知的运行细节。 */
public class SessionArtifactService {
    /** 默认RECAPEXCHANGES的统一常量值。 */
    private static final int DEFAULT_RECAP_EXCHANGES = 10;

    /** 最大用户LEN的统一常量值。 */
    private static final int MAX_USER_LEN = 300;

    /** 最大ASSISTANTLEN的统一常量值。 */
    private static final int MAX_ASSISTANT_LEN = 200;

    /** 最大ASSISTANTLINES的统一常量值。 */
    private static final int MAX_ASSISTANT_LINES = 3;

    /** 注入storage服务，用于调用对应业务能力。 */
    private final SessionArtifactStorageService storageService;

    /** 创建会话Artifact服务实例。 */
    public SessionArtifactService() {
        this(new SessionArtifactStorageService());
    }

    /**
     * 创建会话Artifact服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SessionArtifactService(AppConfig appConfig) {
        this(new SessionArtifactStorageService(appConfig));
    }

    /**
     * 创建会话Artifact服务实例，并注入运行所需依赖。
     *
     * @param storageService storage服务依赖。
     */
    public SessionArtifactService(SessionArtifactStorageService storageService) {
        this.storageService =
                storageService == null ? new SessionArtifactStorageService() : storageService;
    }

    /**
     * 执行recap相关逻辑。
     *
     * @param session 会话参数。
     * @param maxExchanges maxExchanges 参数。
     * @return 返回recap结果。
     */
    public Map<String, Object> recap(SessionRecord session, int maxExchanges) throws Exception {
        List<ChatMessage> messages =
                MessageSupport.loadMessages(session == null ? null : session.getNdjson());
        int safeMax = maxExchanges <= 0 ? DEFAULT_RECAP_EXCHANGES : Math.min(maxExchanges, 50);
        List<Map<String, Object>> entries = recapEntries(messages, safeMax);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", session == null ? null : session.getSessionId());
        result.put("title", session == null ? null : session.getTitle());
        result.put("message_count", Integer.valueOf(messages.size()));
        result.put("displayed", Integer.valueOf(entries.size()));
        result.put("entries", entries);
        result.put("text", formatRecapText(entries, messages.size()));
        return result;
    }

    /**
     * 执行recap文本相关逻辑。
     *
     * @param session 会话参数。
     * @param maxExchanges maxExchanges 参数。
     * @return 返回recap Text结果。
     */
    public String recapText(SessionRecord session, int maxExchanges) throws Exception {
        return String.valueOf(recap(session, maxExchanges).get("text"));
    }

    /**
     * 执行trajectory相关逻辑。
     *
     * @param session 会话参数。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回trajectory结果。
     */
    public Map<String, Object> trajectory(
            SessionRecord session, String userQuery, boolean completed) throws Exception {
        List<ChatMessage> messages =
                MessageSupport.loadMessages(session == null ? null : session.getNdjson());
        List<Map<String, Object>> conversations =
                toTrajectoryConversations(messages, userQuery, completed);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", session == null ? null : session.getSessionId());
        result.put("title", session == null ? null : session.getTitle());
        result.put("completed", Boolean.valueOf(completed));
        result.put("model", session == null ? null : session.getLastResolvedModel());
        result.put("provider", session == null ? null : session.getLastResolvedProvider());
        result.put("conversations", conversations);
        return result;
    }

    /**
     * 执行trajectoryJSON相关逻辑。
     *
     * @param session 会话参数。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回trajectory JSON结果。
     */
    public String trajectoryJson(SessionRecord session, String userQuery, boolean completed)
            throws Exception {
        return ONode.serialize(trajectory(session, userQuery, completed));
    }

    /**
     * 保存Trajectory。
     *
     * @param session 会话参数。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回Trajectory结果。
     */
    public Map<String, Object> saveTrajectory(
            SessionRecord session, String userQuery, boolean completed) throws Exception {
        return storageService.appendTrajectory(
                trajectory(session, userQuery, completed), completed);
    }

    /**
     * 执行recapEntries相关逻辑。
     *
     * @param messages messages 参数。
     * @param maxExchanges maxExchanges 参数。
     * @return 返回recap Entries结果。
     */
    private List<Map<String, Object>> recapEntries(List<ChatMessage> messages, int maxExchanges) {
        List<Map<String, Object>> displayable = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : messages) {
            if (message == null
                    || message.getRole() == ChatRole.SYSTEM
                    || message.getRole() == ChatRole.TOOL) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            if (message.getRole() == ChatRole.USER) {
                entry.put("role", "user");
                entry.put("content", truncate(oneLine(message.getContent()), MAX_USER_LEN));
                displayable.add(entry);
            } else if (message.getRole() == ChatRole.ASSISTANT) {
                entry.put("role", "assistant");
                entry.put("content", assistantRecap((AssistantMessage) message));
                displayable.add(entry);
            }
        }

        int maxItems = Math.max(1, maxExchanges * 2);
        if (displayable.size() <= maxItems) {
            return displayable;
        }
        return new ArrayList<Map<String, Object>>(
                displayable.subList(displayable.size() - maxItems, displayable.size()));
    }

    /**
     * 执行assistantRecap相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回assistant Recap结果。
     */
    private String assistantRecap(AssistantMessage message) {
        String content =
                message == null
                        ? ""
                        : StrUtil.blankToDefault(message.getResultContent(), message.getContent());
        if (message != null
                && message.getToolCalls() != null
                && !message.getToolCalls().isEmpty()) {
            StringBuilder tools = new StringBuilder();
            for (ToolCall call : message.getToolCalls()) {
                if (tools.length() > 0) {
                    tools.append(", ");
                }
                tools.append(call == null ? "unknown" : call.getName());
            }
            content =
                    StrUtil.isBlank(content)
                            ? "[tool calls: " + tools + "]"
                            : content + " [tool calls: " + tools + "]";
        }
        return truncate(limitLines(content, MAX_ASSISTANT_LINES), MAX_ASSISTANT_LEN);
    }

    /**
     * 格式化Recap Text。
     *
     * @param entries entries 参数。
     * @param totalMessages totalMessages 参数。
     * @return 返回Recap Text结果。
     */
    private String formatRecapText(List<Map<String, Object>> entries, int totalMessages) {
        if (entries.isEmpty()) {
            return "当前会话没有可展示的历史消息。";
        }
        StringBuilder buffer = new StringBuilder();
        int hiddenEstimate = Math.max(0, totalMessages - entries.size());
        if (hiddenEstimate > 0) {
            buffer.append("… 已隐藏较早历史消息 ").append(hiddenEstimate).append(" 条\n");
        }
        for (Map<String, Object> entry : entries) {
            String role = String.valueOf(entry.get("role"));
            buffer.append("user".equals(role) ? "用户: " : "助手: ");
            buffer.append(StrUtil.nullToEmpty((String) entry.get("content"))).append('\n');
        }
        return buffer.toString().trim();
    }

    /**
     * 转换为Trajectory Conversations。
     *
     * @param messages messages 参数。
     * @param userQuery 用户查询参数。
     * @param completed completed 参数。
     * @return 返回转换后的Trajectory Conversations。
     */
    private List<Map<String, Object>> toTrajectoryConversations(
            List<ChatMessage> messages, String userQuery, boolean completed) {
        List<Map<String, Object>> trajectory = new ArrayList<Map<String, Object>>();
        trajectory.add(trajectoryEntry("system", trajectorySystemPrompt()));

        int firstUserIndex = findFirstUser(messages);
        String query =
                StrUtil.blankToDefault(
                        userQuery,
                        firstUserIndex >= 0 ? messages.get(firstUserIndex).getContent() : "");
        trajectory.add(trajectoryEntry("human", query));

        int i = firstUserIndex >= 0 ? firstUserIndex + 1 : 0;
        while (i < messages.size()) {
            ChatMessage message = messages.get(i);
            if (message == null || message.getRole() == ChatRole.SYSTEM) {
                i++;
                continue;
            }
            if (message.getRole() == ChatRole.USER) {
                trajectory.add(trajectoryEntry("human", message.getContent()));
            } else if (message.getRole() == ChatRole.ASSISTANT) {
                AssistantMessage assistant = (AssistantMessage) message;
                List<ToolCall> calls = assistant.getToolCalls();
                if (calls != null && !calls.isEmpty()) {
                    trajectory.add(trajectoryEntry("gpt", assistantToolCallContent(assistant)));
                    ToolResponses responses = collectToolResponses(messages, i + 1, calls);
                    if (StrUtil.isNotBlank(responses.value)) {
                        trajectory.add(trajectoryEntry("tool", responses.value));
                        i = responses.nextIndex - 1;
                    }
                } else {
                    trajectory.add(trajectoryEntry("gpt", assistantContent(assistant)));
                }
            } else if (message.getRole() == ChatRole.TOOL) {
                ToolMessage tool = (ToolMessage) message;
                trajectory.add(trajectoryEntry("tool", toolResponseXml(tool, "unknown")));
            }
            i++;
        }

        return trajectory;
    }

    /**
     * 执行trajectoryEntry相关逻辑。
     *
     * @param from from 参数。
     * @param value 待规范化或校验的原始值。
     * @return 返回trajectory Entry结果。
     */
    private Map<String, Object> trajectoryEntry(String from, String value) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("from", from);
        entry.put("value", StrUtil.nullToEmpty(value));
        return entry;
    }

    /**
     * 执行trajectory系统提示词相关逻辑。
     *
     * @return 返回trajectory System提示词结果。
     */
    private String trajectorySystemPrompt() {
        return "You are a function calling AI model. You are provided with function signatures within <tools> </tools> XML tags. "
                + "You may call one or more functions to assist with the user query. If available tools are not relevant, respond in natural language.\n"
                + "<tools>\n[]\n</tools>\n"
                + "Each function call should be enclosed within <tool_call> </tool_call> XML tags.";
    }

    /**
     * 执行assistantContent相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回assistant Content结果。
     */
    private String assistantContent(AssistantMessage message) {
        String content = StrUtil.blankToDefault(message.getResultContent(), message.getContent());
        String reasoning = message.getReasoning();
        if (StrUtil.isBlank(reasoning) && StrUtil.startWithIgnoreCase(content, "<think>")) {
            return content.trim();
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("<think>\n").append(StrUtil.nullToEmpty(reasoning)).append("\n</think>");
        if (StrUtil.isNotBlank(content)) {
            buffer.append('\n').append(removeCompactionPrefix(content));
        }
        return buffer.toString().trim();
    }

    /**
     * 执行assistant工具CallContent相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回assistant工具Call Content结果。
     */
    private String assistantToolCallContent(AssistantMessage message) {
        StringBuilder buffer = new StringBuilder();
        String reasoning = message.getReasoning();
        buffer.append("<think>\n").append(StrUtil.nullToEmpty(reasoning)).append("\n</think>\n");
        String content = StrUtil.blankToDefault(message.getResultContent(), message.getContent());
        if (StrUtil.isNotBlank(content)) {
            buffer.append(removeCompactionPrefix(content)).append('\n');
        }
        for (ToolCall call : message.getToolCalls()) {
            Map<String, Object> callBody = new LinkedHashMap<String, Object>();
            callBody.put("name", call == null ? "unknown" : call.getName());
            callBody.put("arguments", toolArguments(call));
            buffer.append("<tool_call>\n")
                    .append(ONode.serialize(callBody))
                    .append("\n</tool_call>\n");
        }
        return buffer.toString().trim();
    }

    /**
     * 执行工具参数相关逻辑。
     *
     * @param call call 参数。
     * @return 返回工具参数结果。
     */
    private Object toolArguments(ToolCall call) {
        if (call == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (call.getArguments() != null && !call.getArguments().isEmpty()) {
            return call.getArguments();
        }
        String raw = call.getArgumentsStr();
        if (StrUtil.isBlank(raw)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return ONode.deserialize(raw, Object.class);
        } catch (Exception ignored) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("raw", raw);
            return fallback;
        }
    }

    /**
     * 收集工具Responses。
     *
     * @param messages messages 参数。
     * @param startIndex start索引参数。
     * @param calls calls 参数。
     * @return 返回工具Responses结果。
     */
    private ToolResponses collectToolResponses(
            List<ChatMessage> messages, int startIndex, List<ToolCall> calls) {
        StringBuilder buffer = new StringBuilder();
        int index = startIndex;
        int toolIndex = 0;
        while (index < messages.size() && messages.get(index).getRole() == ChatRole.TOOL) {
            ToolMessage tool = (ToolMessage) messages.get(index);
            String name =
                    toolIndex < calls.size() && calls.get(toolIndex) != null
                            ? calls.get(toolIndex).getName()
                            : "unknown";
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(toolResponseXml(tool, name));
            index++;
            toolIndex++;
        }
        return new ToolResponses(buffer.toString(), index);
    }

    /**
     * 执行工具响应Xml相关逻辑。
     *
     * @param tool 工具参数。
     * @param name 名称参数。
     * @return 返回工具响应Xml结果。
     */
    private String toolResponseXml(ToolMessage tool, String name) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String content = tool == null ? "" : tool.getContent();
        ToolResultStorageService.StoredResult stored =
                ToolResultStorageService.describeObservation(content);
        body.put("tool_call_id", tool == null ? "" : tool.getToolCallId());
        body.put("name", StrUtil.blankToDefault(tool == null ? null : tool.getName(), name));
        body.put("content", parseJsonLike(stored.getPreview()));
        if (stored.isTruncated()) {
            body.put("truncated", Boolean.TRUE);
        }
        if (StrUtil.isNotBlank(stored.getResultRef())) {
            body.put("result_ref", stored.getResultRef());
        }
        if (stored.getSizeBytes() > 0L) {
            body.put("size", Long.valueOf(stored.getSizeBytes()));
        }
        return "<tool_response>\n" + ONode.serialize(body) + "\n</tool_response>";
    }

    /**
     * 解析JSON Like。
     *
     * @param content 待处理内容。
     * @return 返回解析后的JSON Like。
     */
    private Object parseJsonLike(String content) {
        String value = StrUtil.nullToEmpty(content).trim();
        if (!value.startsWith("{") && !value.startsWith("[")) {
            return content;
        }
        try {
            return ONode.deserialize(value, Object.class);
        } catch (Exception ignored) {
            return content;
        }
    }

    /**
     * 查找First用户。
     *
     * @param messages messages 参数。
     * @return 返回First用户结果。
     */
    private int findFirstUser(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message != null && message.getRole() == ChatRole.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 移除Compaction Prefix。
     *
     * @param content 待处理内容。
     * @return 返回Compaction Prefix结果。
     */
    private String removeCompactionPrefix(String content) {
        String value = StrUtil.nullToEmpty(content);
        return CompressionConstants.stripSummaryPrefix(value);
    }

    /**
     * 执行one行相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回one Line结果。
     */
    private String oneLine(String value) {
        return StrUtil.nullToEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * 执行限制Lines相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLines maxLines 参数。
     * @return 返回限制Lines结果。
     */
    private String limitLines(String value, int maxLines) {
        String normalized = StrUtil.nullToEmpty(value).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        if (lines.length <= maxLines) {
            return normalized.trim();
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(lines[i]);
        }
        return buffer.append("\n...").toString().trim();
    }

    /**
     * 执行truncate相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回truncate结果。
     */
    private String truncate(String value, int maxLength) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /** 承载工具Responses相关状态和辅助逻辑。 */
    private static class ToolResponses {
        /** 记录工具Responses中的值。 */
        private final String value;

        /** 记录工具Responses中的next索引。 */
        private final int nextIndex;

        /**
         * 创建工具Responses实例，并注入运行所需依赖。
         *
         * @param value 待规范化或校验的原始值。
         * @param nextIndex next索引参数。
         */
        private ToolResponses(String value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }
}
