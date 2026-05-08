package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
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

    @ToolMapping(
            name = "session_search",
            description =
                    "Search historical sessions or list recent sessions. Returns metadata, preview and focused summary.")
    public String sessionSearch(
            @Param(name = "query", description = "检索主题；为空时列出最近会话", required = false) String query,
            @Param(name = "limit", description = "结果条数，默认 3，最大 5", required = false) Integer limit)
            throws Exception {
        try {
            List<SessionSearchEntry> sessions =
                    sessionSearchService.search(sourceKey, query, limit == null ? 3 : limit.intValue());
            return ONode.serialize(sessions);
        } catch (Exception e) {
            return ToolResultEnvelope.error(
                            SecretRedactor.redact(
                                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                                    1000))
                    .toJson();
        }
    }
}
