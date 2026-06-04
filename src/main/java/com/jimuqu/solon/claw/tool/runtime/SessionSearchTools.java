package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** SessionSearchTools 实现。 */
@RequiredArgsConstructor
public class SessionSearchTools {
    private final SessionSearchService sessionSearchService;
    private final String sourceKey;

    public String sessionSearch(
            @Param(name = "query", description = "检索主题；为空时列出最近会话", required = false) String query,
            @Param(name = "limit", description = "结果条数，默认 3，最大 5", required = false) Integer limit)
            throws Exception {
        return sessionSearch(query, null, null, limit);
    }

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
            return ONode.serialize(sessions);
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

    private String redact(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
