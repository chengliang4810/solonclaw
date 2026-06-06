package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台搜索相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardSearchController {
    /** 注入会话搜索服务，用于调用对应业务能力。 */
    private final SessionSearchService sessionSearchService;

    /**
     * 创建控制台搜索控制器实例，并注入运行所需依赖。
     *
     * @param sessionSearchService 会话搜索服务依赖。
     */
    public DashboardSearchController(SessionSearchService sessionSearchService) {
        this.sessionSearchService = sessionSearchService;
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回搜索结果。
     */
    @Mapping(value = "/api/search", method = MethodType.GET)
    public Map<String, Object> search(Context context) throws Exception {
        SessionSearchQuery query = new SessionSearchQuery();
        query.setSourceKey(first(context.param("sourceKey"), context.param("source")));
        query.setSessionId(context.param("sessionId"));
        query.setRunId(context.param("runId"));
        query.setToolName(context.param("toolName"));
        query.setChannel(first(context.param("channel"), context.param("platform")));
        query.setQuery(context.param("q"));
        query.setTimeFrom(asLong(context.param("timeFrom")));
        query.setTimeTo(asLong(context.param("timeTo")));
        query.setSummarize(Boolean.parseBoolean(context.param("summarize")));
        query.setLimit(context.paramAsInt("limit", 10));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (SessionSearchEntry entry : sessionSearchService.search(query)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("session_id", safe(entry.getSessionId(), 200));
            row.put("branch_name", safe(entry.getBranchName(), 400));
            row.put("title", safe(entry.getTitle(), 400));
            row.put("updated_at", entry.getUpdatedAt());
            row.put("match_preview", safe(entry.getMatchPreview(), 2000));
            row.put("summary", safe(entry.getSummary(), 4000));
            row.put("run_id", safe(entry.getRunId(), 200));
            row.put("tool_name", safe(entry.getToolName(), 200));
            row.put("channel", safe(entry.getChannel(), 400));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("results", rows);
        result.put("tokenizer", "fts5/cjk-ngram-fallback");
        return DashboardResponse.ok(result);
    }

    /**
     * 执行first相关逻辑。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回first结果。
     */
    private String first(String left, String right) {
        return left == null || left.trim().length() == 0 ? right : left;
    }

    /**
     * 执行as长整型相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Long结果。
     */
    private long asLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe结果。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
