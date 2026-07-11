package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台搜索相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardSearchController {
    /** 注入会话搜索服务，用于调用对应业务能力。 */
    private final SessionSearchService sessionSearchService;

    /** 解析请求指定的 Profile；旧单元测试构造路径为空时保持当前搜索。 */
    @Inject(required = false)
    private DashboardProfileContext profileContext;

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
        try {
            return DashboardResponse.ok(searchData(context));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "SEARCH_BAD_REQUEST", e);
        }
    }

    /** 执行当前或显式 Profile 的只读会话搜索。 */
    private Map<String, Object> searchData(Context context) throws Exception {
        DashboardProfileContext.Scope scope = resolve(context);
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
        if (scope != null && !scope.isCurrent()) {
            query.setProfile(scope.getName());
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<SessionSearchEntry> entries =
                scope != null
                                && !scope.isCurrent()
                                && !Files.isRegularFile(
                                        scope.getHome().resolve("data").resolve("state.db"))
                        ? new ArrayList<SessionSearchEntry>()
                        : sessionSearchService.search(query);
        for (SessionSearchEntry entry : entries) {
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
            if (scope != null && !scope.isCurrent()) {
                row.put("profile", scope.getName());
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("results", rows);
        result.put("tokenizer", "fts5/cjk-ngram-fallback");
        return result;
    }

    /** 解析并校验 query.profile，空值和 current 保持旧行为。 */
    private DashboardProfileContext.Scope resolve(Context context) {
        String requested = DashboardProfileContext.requestedProfile(context);
        if (profileContext == null) {
            if (requested == null
                    || requested.trim().length() == 0
                    || "current".equalsIgnoreCase(requested.trim())) {
                return null;
            }
            throw new IllegalArgumentException("Dashboard Profile scope is unavailable.");
        }
        return profileContext.resolve(requested);
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
