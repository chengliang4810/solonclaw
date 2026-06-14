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
            @Param(name = "limit", description = "结果条数，默认 3，最大 5", required = false) Integer limit)
            throws Exception {
        return sessionSearch(query, null, null, limit);
    }

    /**
     * 执行会话搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param sessionId 当前会话标识。
     * @param aroundMessageId around消息标识。
     * @param limit 最大返回数量。
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
            @Param(name = "sessionId", description = "scroll 模式目标会话 ID", required = false)
                    String sessionId,
            @Param(name = "aroundMessageId", description = "scroll 模式锚点消息 ID", required = false)
                    String aroundMessageId,
            @Param(name = "limit", description = "结果条数，默认 3，最大 5", required = false) Integer limit)
            throws Exception {
        try {
            SessionSearchQuery searchQuery = new SessionSearchQuery();
            searchQuery.setSourceKey(sourceKey);
            searchQuery.setQuery(query);
            searchQuery.setSessionId(sessionId);
            searchQuery.setAroundMessageId(aroundMessageId);
            searchQuery.setLimit(limit == null ? 3 : limit.intValue());
            List<SessionSearchEntry> sessions = sessionSearchService.search(searchQuery);
            for (SessionSearchEntry session : sessions) {
                redact(session);
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
    private List<Map<String, Object>> compactDiscoveryResults(
            List<SessionSearchEntry> sessions) {
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
            putIfNotBlank(
                    item, "toolName", compactRedact(session.getToolName(), COMPACT_ID_LIMIT));
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
        return compactRedact(value, COMPACT_TEXT_LIMIT);
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
            item.put("text", scrollText(session));
            results.add(item);
        }
        return results;
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
        session.setMatchPreview(redact(session.getMatchPreview(), 2000));
        session.setSummary(redact(session.getSummary(), 4000));
        session.setRunId(redact(session.getRunId(), 200));
        session.setToolName(redact(session.getToolName(), 200));
        session.setChannel(redact(session.getChannel(), 400));
        session.setMode(redact(session.getMode(), 50));
        session.setMessageId(redact(session.getMessageId(), 200));
        session.setPlatformMessageId(redact(session.getPlatformMessageId(), 200));
        session.setSnippet(redact(session.getSnippet(), 2000));
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
