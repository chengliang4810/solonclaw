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
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 Dashboard 全局搜索只读工具，支持会话、运行和工具调用过滤。 */
public class SearchManageTools {
    /** 会话搜索服务，用于复用 Dashboard 搜索入口背后的检索能力。 */
    private final SessionSearchService sessionSearchService;

    /**
     * 创建 Dashboard 搜索管理工具。
     *
     * @param sessionSearchService 会话搜索服务。
     */
    public SearchManageTools(SessionSearchService sessionSearchService) {
        this.sessionSearchService = sessionSearchService;
    }

    /**
     * 按 Dashboard 搜索条件检索历史会话、运行和工具调用。
     *
     * @param query 关键词。
     * @param sourceKey 来源键。
     * @param sessionId 会话 ID。
     * @param runId 运行 ID。
     * @param toolName 工具名。
     * @param channel 渠道名。
     * @param timeFrom 起始时间戳。
     * @param timeTo 截止时间戳。
     * @param summarize 是否生成摘要。
     * @param limit 最大返回数量。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "search_manage",
            description =
                    "Inspect dashboard search results across sessions, runs and tool calls with filters.")
    public String searchManage(
            @Param(name = "query", required = false, description = "Search keyword") String query,
            @Param(name = "sourceKey", required = false, description = "Source key") String sourceKey,
            @Param(name = "sessionId", required = false, description = "Session id") String sessionId,
            @Param(name = "runId", required = false, description = "Run id") String runId,
            @Param(name = "toolName", required = false, description = "Tool name filter") String toolName,
            @Param(name = "channel", required = false, description = "Channel or platform filter")
                    String channel,
            @Param(name = "timeFrom", required = false, description = "Start timestamp millis")
                    Long timeFrom,
            @Param(name = "timeTo", required = false, description = "End timestamp millis")
                    Long timeTo,
            @Param(name = "summarize", required = false, defaultValue = "false", description = "Summarize matched sessions")
                    Boolean summarize,
            @Param(name = "limit", required = false, defaultValue = "10", description = "Max rows")
                    Integer limit) {
        try {
            if (sessionSearchService == null) {
                return ToolResultEnvelope.error("search service unavailable").toJson();
            }
            Map<String, Object> result =
                    search(query, sourceKey, sessionId, runId, toolName, channel, timeFrom, timeTo, summarize, limit);
            return ToolResultEnvelope.ok("Dashboard 搜索完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行 Dashboard 搜索查询。
     *
     * @param query 关键词。
     * @param sourceKey 来源键。
     * @param sessionId 会话 ID。
     * @param runId 运行 ID。
     * @param toolName 工具名。
     * @param channel 渠道名。
     * @param timeFrom 起始时间戳。
     * @param timeTo 截止时间戳。
     * @param summarize 是否生成摘要。
     * @param limit 最大返回数量。
     * @return 返回对齐 Dashboard 搜索接口的结果。
     */
    private Map<String, Object> search(
            String query,
            String sourceKey,
            String sessionId,
            String runId,
            String toolName,
            String channel,
            Long timeFrom,
            Long timeTo,
            Boolean summarize,
            Integer limit)
            throws Exception {
        SessionSearchQuery searchQuery = new SessionSearchQuery();
        searchQuery.setSourceKey(sourceKey);
        searchQuery.setSessionId(sessionId);
        searchQuery.setRunId(runId);
        searchQuery.setToolName(toolName);
        searchQuery.setChannel(channel);
        searchQuery.setQuery(query);
        searchQuery.setTimeFrom(timeFrom == null ? 0L : timeFrom.longValue());
        searchQuery.setTimeTo(timeTo == null ? 0L : timeTo.longValue());
        searchQuery.setSummarize(Boolean.TRUE.equals(summarize));
        searchQuery.setLimit(limit == null ? 10 : Math.min(Math.max(1, limit.intValue()), 50));

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (SessionSearchEntry entry : sessionSearchService.search(searchQuery)) {
            rows.add(row(entry));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("results", rows);
        result.put("tokenizer", "fts5/cjk-ngram-fallback");
        return result;
    }

    /**
     * 转换单条搜索结果，并执行与 Dashboard 一致的低敏脱敏。
     *
     * @param entry 搜索命中条目。
     * @return 返回结果行。
     */
    private Map<String, Object> row(SessionSearchEntry entry) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("session_id", safe(entry.getSessionId(), 200));
        row.put("branch_name", safe(entry.getBranchName(), 400));
        row.put("title", safe(entry.getTitle(), 400));
        row.put("updated_at", Long.valueOf(entry.getUpdatedAt()));
        row.put("match_preview", safe(entry.getMatchPreview(), 2000));
        row.put("summary", safe(entry.getSummary(), 4000));
        row.put("run_id", safe(entry.getRunId(), 200));
        row.put("tool_name", safe(entry.getToolName(), 200));
        row.put("channel", safe(entry.getChannel(), 400));
        return row;
    }

    /**
     * 脱敏并限制文本长度。
     *
     * @param value 原始文本。
     * @param maxLength 最大长度。
     * @return 返回低敏文本。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
