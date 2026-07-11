package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** SessionSearchTools 实现。 */
@RequiredArgsConstructor
public class SessionSearchTools {
    /** discovery/browse 工具结果中标题的最大字符数，避免长标题重复挤占工具上下文。 */
    private static final int COMPACT_TITLE_LIMIT = 80;

    /** discovery/browse 工具结果中命中文本的最大字符数，确保 limit=5 时通常可直接内联。 */
    private static final int COMPACT_TEXT_LIMIT = 280;

    /** discovery/browse 工具结果中标识类字段的最大字符数，避免异常长 ID 影响输出预算。 */
    private static final int COMPACT_ID_LIMIT = 160;

    /** 可执行证据路径的匹配规则，用于避免会话搜索摘要把文件路径裁剪成不可读取的半截文本。 */
    private static final Pattern ACTIONABLE_PATH =
            Pattern.compile(
                    "(?<![A-Za-z0-9_.:/\\\\-])((?:[A-Za-z]:[\\\\/]|\\.{1,2}[\\\\/]|[A-Za-z0-9_.-]+[\\\\/])[^\\s\"'<>|;?#，。；：]+(?:[\\\\/][^\\s\"'<>|;?#，。；：]+)*)(?![A-Za-z0-9_.:/\\\\-])");

    /** 注入会话搜索服务，用于调用对应业务能力。 */
    private final SessionSearchService sessionSearchService;

    /** 记录会话搜索中的来源键。 */
    private final String sourceKey;

    /**
     * 执行会话搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回会话搜索结果。
     */
    public String sessionSearch(
            @Param(name = "query", description = "检索主题；为空时列出最近会话", required = false) String query,
            @Param(name = "limit", description = "结果条数，默认 3，最大 10", required = false) Integer limit)
            throws Exception {
        return sessionSearch(query, limit, null, null, null, null, null, null);
    }

    /**
     * 执行会话搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @param sort discovery 时间排序。
     * @param sessionId 当前会话标识。
     * @param aroundMessageId around消息标识。
     * @param window 锚点两侧窗口大小。
     * @param roleFilter discovery 消息角色过滤。
     * @param profile 目标 Profile。
     * @return 返回会话搜索结果。
     */
    @ToolMapping(
            name = "session_search",
            description =
                    "Search historical sessions, scroll around a message, or browse recent sessions. Returns metadata, snippets and focused summaries.")
    public String sessionSearch(
            @Param(
                            name = "query",
                            description = "discovery 模式检索主题；为空且无锚点时 browse 最近会话",
                            required = false)
                    String query,
            @Param(name = "limit", description = "discovery 结果条数，默认 3，最大 10", required = false)
                    Integer limit,
            @Param(name = "sort", description = "discovery 排序：newest 或 oldest", required = false)
                    String sort,
            @Param(name = "session_id", description = "scroll/read 模式目标会话 ID", required = false)
                    String sessionId,
            @Param(name = "around_message_id", description = "scroll 模式锚点消息 ID", required = false)
                    String aroundMessageId,
            @Param(name = "window", description = "锚点两侧各返回的消息数，默认 5，范围 1..20", required = false)
                    Integer window,
            @Param(name = "role_filter", description = "discovery 角色过滤，逗号分隔", required = false)
                    String roleFilter,
            @Param(name = "profile", description = "可选目标 Profile 名称", required = false)
                    String profile)
            throws Exception {
        try {
            SessionSearchQuery searchQuery = new SessionSearchQuery();
            searchQuery.setSourceKey(sourceKey);
            searchQuery.setQuery(query);
            searchQuery.setSessionId(sessionId);
            searchQuery.setAroundMessageId(aroundMessageId);
            searchQuery.setLimit(limit == null ? 3 : limit.intValue());
            searchQuery.setSort(sort);
            searchQuery.setWindow(window == null ? 5 : window.intValue());
            searchQuery.setRoleFilter(roleFilter);
            searchQuery.setProfile(profile);
            List<SessionSearchEntry> sessions = sessionSearchService.search(searchQuery);
            for (SessionSearchEntry session : sessions) {
                redact(session);
            }
            if (sessionId != null
                    && sessionId.trim().length() > 0
                    && (aroundMessageId == null || aroundMessageId.trim().length() == 0)) {
                return ONode.serialize(compactReadResults(sessions));
            }
            if (aroundMessageId != null && aroundMessageId.trim().length() > 0) {
                return ONode.serialize(compactScrollResults(sessions));
            }
            return ONode.serialize(compactDiscoveryResults(sessions));
        } catch (Exception e) {
            return ToolResultEnvelope.error(
                            SecretRedactor.redact(
                                    e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage(),
                                    1000))
                    .toJson();
        }
    }

    /**
     * 将 discovery/browse 模式结果压缩成单一文本字段，避免 title/summary/snippet/matchPreview 重复导致工具结果落盘。
     *
     * @param sessions discovery 或 browse 模式检索结果。
     * @return 返回压缩后的结果。
     */
    private List<Map<String, Object>> compactDiscoveryResults(List<SessionSearchEntry> sessions) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        if (sessions == null) {
            return results;
        }
        for (SessionSearchEntry session : sessions) {
            if (session == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            putIfNotBlank(item, "mode", redact(session.getMode(), 50));
            putIfNotBlank(
                    item, "sessionId", compactRedact(session.getSessionId(), COMPACT_ID_LIMIT));
            putIfNotBlank(item, "title", compactRedact(session.getTitle(), COMPACT_TITLE_LIMIT));
            putIfNotBlank(
                    item, "messageId", compactRedact(session.getMessageId(), COMPACT_ID_LIMIT));
            putIfNotBlank(
                    item,
                    "platformMessageId",
                    compactRedact(session.getPlatformMessageId(), COMPACT_ID_LIMIT));
            putIfNotBlank(item, "runId", compactRedact(session.getRunId(), COMPACT_ID_LIMIT));
            putIfNotBlank(item, "toolName", compactRedact(session.getToolName(), COMPACT_ID_LIMIT));
            item.put("score", Long.valueOf(session.getScore()));
            if (session.getUpdatedAt() > 0L) {
                item.put("updatedAt", Long.valueOf(session.getUpdatedAt()));
            }
            putIfNotBlank(item, "text", compactDiscoveryText(session));
            results.add(item);
        }
        return results;
    }

    /**
     * 选择 discovery/browse 条目的代表文本，只保留一个脱敏字段给模型判断命中原因。
     *
     * @param session 检索结果条目。
     * @return 返回压缩后的命中文本。
     */
    private String compactDiscoveryText(SessionSearchEntry session) {
        String value = session.getMatchPreview();
        if (value == null || value.length() == 0) {
            value = session.getSnippet();
        }
        if (value == null || value.length() == 0) {
            value = session.getSummary();
        }
        if (value == null || value.length() == 0) {
            value = session.getTitle();
        }
        return compactDiscoveryText(value, COMPACT_TEXT_LIMIT);
    }

    /**
     * 将 scroll 模式结果压缩为模型可直接判断的最小结构，避免 title/summary/snippet 重复导致锚点被预览截断。
     *
     * @param sessions scroll 模式检索结果。
     * @return 返回压缩后的结果。
     */
    private List<Map<String, Object>> compactScrollResults(List<SessionSearchEntry> sessions) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        if (sessions == null) {
            return results;
        }
        for (SessionSearchEntry session : sessions) {
            if (session == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("mode", session.getMode());
            item.put("sessionId", session.getSessionId());
            item.put("messageId", session.getMessageId());
            item.put("anchor", Boolean.valueOf(session.isAnchor()));
            putIfNotBlank(item, "role", redact(session.getRole(), 50));
            item.put("messagesBefore", Integer.valueOf(session.getMessagesBefore()));
            item.put("messagesAfter", Integer.valueOf(session.getMessagesAfter()));
            item.put("text", scrollText(session));
            results.add(item);
        }
        return results;
    }

    /** 将 read 模式结果组装为带 Profile 归属和截断元数据的单一响应对象。 */
    private Map<String, Object> compactReadResults(List<SessionSearchEntry> sessions) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("success", Boolean.TRUE);
        response.put("mode", "read");
        if (sessions == null || sessions.isEmpty()) {
            response.put("success", Boolean.FALSE);
            response.put("error", "session_id not found");
            response.put("messages", new ArrayList<Map<String, Object>>());
            return response;
        }
        SessionSearchEntry first = sessions.get(0);
        putIfNotBlank(response, "profile", redact(first.getProfile(), 64));
        putIfNotBlank(response, "session_id", redact(first.getSessionId(), COMPACT_ID_LIMIT));
        Map<String, Object> sessionMeta = new LinkedHashMap<String, Object>();
        if (first.getCreatedAt() > 0L) {
            sessionMeta.put("when", Long.valueOf(first.getCreatedAt()));
        }
        putIfNotBlank(sessionMeta, "source", redact(first.getChannel(), 400));
        putIfNotBlank(sessionMeta, "model", redact(first.getSessionModel(), 200));
        putIfNotBlank(sessionMeta, "title", compactRedact(first.getTitle(), COMPACT_TITLE_LIMIT));
        response.put("session_meta", sessionMeta);
        response.put("message_count", Integer.valueOf(first.getMessageCount()));
        response.put("truncated", Boolean.valueOf(first.isTruncated()));
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        for (SessionSearchEntry session : sessions) {
            if (session == null) {
                continue;
            }
            if (session.getMessageCount() == 0 && session.getMessageId() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            putIfNotBlank(item, "id", redact(session.getMessageId(), COMPACT_ID_LIMIT));
            putIfNotBlank(item, "role", redact(session.getRole(), 50));
            item.put("content", readText(session));
            if (session.getMessageTimestamp() > 0L) {
                item.put("timestamp", Long.valueOf(session.getMessageTimestamp()));
            }
            putIfNotBlank(item, "tool_name", redact(session.getMessageToolName(), 200));
            putIfNotBlank(item, "tool_call_id", redact(session.getToolCallId(), 200));
            if (session.getToolCallsJson() != null && session.getToolCallsJson().length() > 0) {
                item.put("tool_calls", parseJsonValue(session.getToolCallsJson()));
            }
            messages.add(item);
        }
        response.put("messages", messages);
        if (first.isTruncated()) {
            response.put(
                    "message",
                    "Session has "
                            + first.getMessageCount()
                            + " messages; showing first 20 + last 10. Pass around_message_id to scroll the middle.");
        }
        return response;
    }

    /** 将可信的内部 JSON 字符串恢复为结构化值；解析失败时保留原文。 */
    private Object parseJsonValue(String value) {
        try {
            return ONode.deserialize(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    /** read 模式保留消息换行和更大的正文预算，避免把整段会话再次压成搜索片段。 */
    private String readText(SessionSearchEntry session) {
        String value = session.getMatchPreview();
        if (value == null) {
            value = session.getSnippet();
        }
        return redact(value == null ? "" : value, 16000);
    }

    /**
     * 选择 scroll 条目的代表文本，优先保留 matchPreview，保证锚点周边内容能进入工具返回。
     *
     * @param session scroll 条目。
     * @return 返回可展示文本。
     */
    private String scrollText(SessionSearchEntry session) {
        String value = session.getMatchPreview();
        if (value == null || value.length() == 0) {
            value = session.getSnippet();
        }
        if (value == null || value.length() == 0) {
            value = session.getSummary();
        }
        return redact(value, 1000);
    }

    /**
     * 脱敏文本中的密钥、令牌和敏感路径。
     *
     * @param session 会话参数。
     */
    private void redact(SessionSearchEntry session) {
        if (session == null) {
            return;
        }
        session.setSessionId(redact(session.getSessionId(), 200));
        session.setBranchName(redact(session.getBranchName(), 400));
        session.setTitle(redact(session.getTitle(), 400));
        session.setMatchPreview(redact(session.getMatchPreview(), 16000));
        session.setSummary(redact(session.getSummary(), 4000));
        session.setRunId(redact(session.getRunId(), 200));
        session.setToolName(redact(session.getToolName(), 200));
        session.setChannel(redact(session.getChannel(), 400));
        session.setMode(redact(session.getMode(), 50));
        session.setMessageId(redact(session.getMessageId(), 200));
        session.setPlatformMessageId(redact(session.getPlatformMessageId(), 200));
        session.setSnippet(redact(session.getSnippet(), 2000));
        session.setRole(redact(session.getRole(), 50));
        session.setProfile(redact(session.getProfile(), 64));
        session.setMessageToolName(redact(session.getMessageToolName(), 200));
        session.setToolCallId(redact(session.getToolCallId(), 200));
        session.setToolCallsJson(redact(session.getToolCallsJson(), 4000));
        session.setSessionModel(redact(session.getSessionModel(), 200));
    }

    /**
     * 脱敏文本中的密钥、令牌和敏感路径。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回redact结果。
     */
    private String redact(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 先脱敏再做硬长度裁剪，保证工具返回的高频文本字段不会因截断说明再次突破预算。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回压缩并脱敏后的文本。
     */
    private String compactRedact(String value, int maxLength) {
        String redacted = redact(value, maxLength);
        if (redacted == null || redacted.length() <= maxLength) {
            return redacted;
        }
        return redacted.substring(0, Math.max(0, maxLength));
    }

    /**
     * 压缩 discovery 命中文本时优先保留完整文件路径，避免模型拿到半截路径后浪费额外工具调用纠错。
     *
     * @param value 待压缩文本。
     * @param maxLength 最大保留字符数。
     * @return 返回路径感知压缩后的文本。
     */
    private String compactDiscoveryText(String value, int maxLength) {
        String redacted = redact(value, Math.max(maxLength, 2000));
        if (redacted == null || redacted.length() <= maxLength) {
            return redacted;
        }
        PathSpan span = firstPathOutsideHardCut(redacted, maxLength);
        if (span == null) {
            return redacted.substring(0, Math.max(0, maxLength));
        }
        return compactAroundSpan(redacted, span.start, span.end, maxLength);
    }

    /**
     * 查找会被普通硬裁剪破坏的第一个文件路径，路径已在脱敏之后判断，敏感路径不会被恢复。
     *
     * @param value 已脱敏文本。
     * @param maxLength 普通硬裁剪长度。
     * @return 返回需要保护的路径范围。
     */
    private PathSpan firstPathOutsideHardCut(String value, int maxLength) {
        Matcher matcher = ACTIONABLE_PATH.matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null || candidate.indexOf("://") >= 0) {
                continue;
            }
            if (matcher.end(1) > maxLength) {
                return new PathSpan(matcher.start(1), matcher.end(1));
            }
        }
        return null;
    }

    /**
     * 围绕关键路径截取上下文，保证返回值仍受工具结果预算约束。
     *
     * @param value 已脱敏文本。
     * @param start 关键片段起始位置。
     * @param end 关键片段结束位置。
     * @param maxLength 最大保留字符数。
     * @return 返回围绕关键片段压缩后的文本。
     */
    private String compactAroundSpan(String value, int start, int end, int maxLength) {
        int safeStart = Math.max(0, Math.min(start, value.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, value.length()));
        int spanLength = safeEnd - safeStart;
        int prefixLength = safeStart > 0 ? 3 : 0;
        int suffixLength = safeEnd < value.length() ? 3 : 0;
        int contextBudget = maxLength - prefixLength - suffixLength - spanLength;
        if (contextBudget < 0) {
            return value.substring(0, Math.max(0, maxLength));
        }
        int left = Math.min(safeStart, contextBudget / 2);
        int right = Math.min(value.length() - safeEnd, contextBudget - left);
        left += Math.min(safeStart - left, contextBudget - left - right);
        int windowStart = safeStart - left;
        int windowEnd = safeEnd + right;
        return (windowStart > 0 ? "..." : "")
                + value.substring(windowStart, windowEnd)
                + (windowEnd < value.length() ? "..." : "");
    }

    /** 记录需要在紧凑摘要中完整保留的文本范围。 */
    private static class PathSpan {
        /** 路径起始位置。 */
        private final int start;

        /** 路径结束位置。 */
        private final int end;

        /**
         * 创建路径范围。
         *
         * @param start 路径起始位置。
         * @param end 路径结束位置。
         */
        private PathSpan(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 仅在值非空时写入 JSON map，减少工具返回中无意义的空字段。
     *
     * @param item 目标 map。
     * @param key 字段名。
     * @param value 字段值。
     */
    private void putIfNotBlank(Map<String, Object> item, String key, String value) {
        if (value != null && value.length() > 0) {
            item.put(key, value);
        }
    }
}
