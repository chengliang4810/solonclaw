package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileView;
import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 会话接口。 */
@Controller
public class DashboardSessionController {
    /** 注入会话服务，用于调用对应业务能力。 */
    private final DashboardSessionService sessionService;

    /** 解析请求选择的 Profile；测试中的旧构造路径为空时保持当前行为。 */
    @Inject(required = false)
    private DashboardProfileContext profileContext;

    /** 列举机器上的 Profile，供只读聚合接口使用。 */
    @Inject(required = false)
    private ProfileManager profileManager;

    /**
     * 创建控制台会话控制器实例，并注入运行所需依赖。
     *
     * @param sessionService 会话服务依赖。
     */
    public DashboardSessionController(DashboardSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 创建显式注入 Profile 依赖的控制器，供嵌入测试和非 Solon 装配场景验证路由语义。
     *
     * @param sessionService 会话服务依赖。
     * @param profileContext Profile 请求上下文。
     * @param profileManager 机器级 Profile 管理器。
     */
    public DashboardSessionController(
            DashboardSessionService sessionService,
            DashboardProfileContext profileContext,
            ProfileManager profileManager) {
        this.sessionService = sessionService;
        this.profileContext = profileContext;
        this.profileManager = profileManager;
    }

    /**
     * 执行sessions相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回sessions结果。
     */
    @Mapping(value = "/api/sessions", method = MethodType.GET)
    public Map<String, Object> sessions(Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 读取当前或显式 Profile 的会话页。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return sessionService.getSessions(
                                listOptions(context, "created", true),
                                scope,
                                hasExplicitProfile(context));
                    }
                });
    }

    /**
     * 聚合机器上全部或单个 Profile 的只读会话列表。
     *
     * @param context 当前请求上下文。
     * @return 带 Profile 归属和分项统计的会话页。
     */
    @Mapping(value = "/api/profiles/sessions", method = MethodType.GET)
    public Map<String, Object> profileSessions(Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 执行跨 Profile 只读聚合。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.getProfilesSessions(
                                listOptions(context, "recent", false),
                                aggregateScopes(context.param("profile")));
                    }
                });
    }

    /**
     * 执行messages相关逻辑。
     *
     * @param id 标识。
     * @return 返回messages结果。
     */
    @Mapping(value = "/api/sessions/{id}/messages", method = MethodType.GET)
    public Map<String, Object> messages(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return sessionService.getSessionMessages(
                                id,
                                scope,
                                optionalInt(context, "limit"),
                                intParam(context, "offset", 0));
                    }
                });
    }

    /**
     * 读取单个会话详情。
     *
     * @param id 会话标识或完整 ID。
     * @param context 当前请求上下文。
     * @return 会话详情。
     */
    @Mapping(value = "/api/sessions/{id}", method = MethodType.GET)
    public Map<String, Object> detail(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 读取当前或目标 Profile 的会话详情。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.getSessionDetail(id, resolve(context, null));
                    }
                });
    }

    /**
     * 执行recap相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回recap结果。
     */
    @Mapping(value = "/api/sessions/{id}/recap", method = MethodType.GET)
    public Map<String, Object> recap(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 读取当前或目标 Profile 的会话回顾。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        int limit = context.paramAsInt("limit", 10);
                        if (isTarget(scope)) {
                            return client(context, scope)
                                    .request(
                                            "GET",
                                            query("limit", String.valueOf(limit)),
                                            "api",
                                            "sessions",
                                            id,
                                            "recap");
                        }
                        return sessionService.recap(id, limit);
                    }
                });
    }

    /**
     * 执行trajectory相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回trajectory结果。
     */
    @Mapping(value = "/api/sessions/{id}/trajectory", method = MethodType.GET)
    public Map<String, Object> trajectory(String id, Context context) throws Exception {
        String userQuery = context.param("user_query");
        if (userQuery == null || userQuery.trim().length() == 0) {
            userQuery = context.param("userQuery");
        }
        boolean completed = !"false".equalsIgnoreCase(context.param("completed"));
        final String queryText = userQuery;
        final boolean completedValue = completed;
        return safeSession(
                context,
                new SessionAction() {
                    /** 读取当前或目标 Profile 的 trajectory。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        if (isTarget(scope)) {
                            return client(context, scope)
                                    .request(
                                            "GET",
                                            query(
                                                    "user_query",
                                                    queryText,
                                                    "completed",
                                                    String.valueOf(completedValue)),
                                            "api",
                                            "sessions",
                                            id,
                                            "trajectory");
                        }
                        return sessionService.trajectory(id, queryText, completedValue);
                    }
                });
    }

    /**
     * 保存Trajectory。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回Trajectory结果。
     */
    @Mapping(value = "/api/sessions/{id}/trajectory/save", method = MethodType.POST)
    public Map<String, Object> saveTrajectory(String id, Context context) throws Exception {
        String userQuery = context.param("user_query");
        if (userQuery == null || userQuery.trim().length() == 0) {
            userQuery = context.param("userQuery");
        }
        boolean completed = !"false".equalsIgnoreCase(context.param("completed"));
        final String queryText = userQuery;
        final boolean completedValue = completed;
        return safeSession(
                context,
                new SessionAction() {
                    /** 保存当前或目标 Profile 的 trajectory。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        if (isTarget(scope)) {
                            return client(context, scope)
                                    .request(
                                            "POST",
                                            query(
                                                    "user_query",
                                                    queryText,
                                                    "completed",
                                                    String.valueOf(completedValue)),
                                            "api",
                                            "sessions",
                                            id,
                                            "trajectory",
                                            "save");
                        }
                        return sessionService.saveTrajectory(id, queryText, completedValue);
                    }
                });
    }

    /**
     * 执行tree相关逻辑。
     *
     * @param id 标识。
     * @return 返回tree结果。
     */
    @Mapping(value = "/api/sessions/{id}/tree", method = MethodType.GET)
    public Map<String, Object> tree(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 读取当前或目标 Profile 的会话树。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return sessionService.sessionTree(id, scope);
                    }
                });
    }

    /**
     * 执行latestDescendant相关逻辑。
     *
     * @param id 标识。
     * @return 返回latest Descendant结果。
     */
    @Mapping(value = "/api/sessions/{id}/latest-descendant", method = MethodType.GET)
    public Map<String, Object> latestDescendant(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 读取当前或目标 Profile 的最新后代。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return sessionService.latestDescendant(id, scope);
                    }
                });
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回更新结果。
     */
    @Mapping(value = "/api/sessions/{id}", method = MethodType.PATCH)
    public Map<String, Object> update(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        DashboardProfileContext.Scope scope = resolve(context, body);
                        return sessionService.updateSession(id, withoutProfile(body), scope);
                    }
                });
    }

    /**
     * 执行checkpoints相关逻辑。
     *
     * @param id 标识。
     * @return 返回checkpoints结果。
     */
    @Mapping(value = "/api/sessions/{id}/checkpoints", method = MethodType.GET)
    public Map<String, Object> checkpoints(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return isTarget(scope)
                                ? client(context, scope)
                                        .request(
                                                "GET",
                                                Collections.<String, String>emptyMap(),
                                                "api",
                                                "sessions",
                                                id,
                                                "checkpoints")
                                : sessionService.checkpoints(id);
                    }
                });
    }

    /**
     * 执行检查点预览相关逻辑。
     *
     * @param id 标识。
     * @return 返回检查点Preview结果。
     */
    @Mapping(value = "/api/checkpoints/{id}/preview", method = MethodType.GET)
    public Map<String, Object> checkpointPreview(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return isTarget(scope)
                                ? client(context, scope)
                                        .request(
                                                "GET",
                                                Collections.<String, String>emptyMap(),
                                                "api",
                                                "checkpoints",
                                                id,
                                                "preview")
                                : sessionService.checkpointPreview(id);
                    }
                });
    }

    /**
     * 执行回滚检查点相关逻辑。
     *
     * @param id 标识。
     * @return 返回回滚检查点结果。
     */
    @Mapping(value = "/api/checkpoints/{id}/rollback", method = MethodType.POST)
    public Map<String, Object> rollbackCheckpoint(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return isTarget(scope)
                                ? client(context, scope)
                                        .request(
                                                "POST",
                                                Collections.<String, String>emptyMap(),
                                                "api",
                                                "checkpoints",
                                                id,
                                                "rollback")
                                : sessionService.rollbackCheckpoint(id);
                    }
                });
    }

    /**
     * 执行delete，服务于控制台会话主流程相关逻辑。
     *
     * @param id 标识。
     * @return 返回delete结果。
     */
    @Mapping(value = "/api/sessions/{id}", method = MethodType.DELETE)
    public Map<String, Object> delete(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    /** 删除当前或目标 Profile 的会话。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return sessionService.deleteSession(id, scope);
                    }
                });
    }

    /**
     * 生成安全展示用的会话。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe会话结果。
     */
    private Map<String, Object> safeSession(Context context, SessionAction action)
            throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (DashboardProfileGatewayException e) {
            return DashboardResponse.error(context, e.getStatus(), e.getCode(), e);
        } catch (DashboardSessionService.SessionNotFoundException e) {
            return DashboardResponse.error(context, 404, "SESSION_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "SESSION_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 400, "SESSION_BAD_REQUEST", e);
        }
    }

    /** 解析 query 或 body 选择的 Profile；body 中非空 profile 优先。 */
    private DashboardProfileContext.Scope resolve(Context context, Map<String, Object> body) {
        String requested = DashboardProfileContext.requestedProfile(context, body);
        if (profileContext == null) {
            if (StrUtil.isBlank(requested) || "current".equalsIgnoreCase(requested)) {
                return null;
            }
            throw new IllegalStateException("Dashboard Profile scope is unavailable.");
        }
        return profileContext.resolve(requested);
    }

    /** 判断普通会话列表是否显式指定了 Profile。 */
    private boolean hasExplicitProfile(Context context) {
        return context != null && StrUtil.isNotBlank(context.param("profile"));
    }

    /** 从请求参数构造完整会话列表选项。 */
    private DashboardSessionService.SessionListOptions listOptions(
            Context context, String defaultOrder, boolean includeCwd) {
        return new DashboardSessionService.SessionListOptions(
                intParam(context, "limit", 20),
                intParam(context, "offset", 0),
                intParam(context, "min_messages", 0),
                StrUtil.blankToDefault(context.param("archived"), "exclude"),
                StrUtil.blankToDefault(context.param("order"), defaultOrder),
                context.param("source"),
                commaSeparated(context.param("exclude_sources")),
                includeCwd ? context.param("cwd_prefix") : null,
                booleanParam(context, "full", false));
    }

    /** 严格读取整数参数，格式错误时返回稳定的 400。 */
    private int intParam(Context context, String name, int defaultValue) {
        String raw = context == null ? null : context.param(name);
        if (StrUtil.isBlank(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    /** 读取可选整数参数。 */
    private Integer optionalInt(Context context, String name) {
        String raw = context == null ? null : context.param(name);
        return StrUtil.isBlank(raw) ? null : Integer.valueOf(intParam(context, name, 0));
    }

    /** 按 FastAPI 常见布尔文本规则读取查询参数。 */
    private boolean booleanParam(Context context, String name, boolean defaultValue) {
        String raw = context == null ? null : context.param(name);
        if (StrUtil.isBlank(raw)) {
            return defaultValue;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)
                || "0".equals(value)
                || "no".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be a boolean");
    }

    /** 把逗号分隔来源转换为去空白列表。 */
    private List<String> commaSeparated(String value) {
        if (StrUtil.isBlank(value)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String item : value.split(",")) {
            if (StrUtil.isNotBlank(item)) {
                result.add(item.trim());
            }
        }
        return result;
    }

    /** 返回聚合接口需要读取的全部或单个 Profile Scope。 */
    private List<DashboardProfileContext.Scope> aggregateScopes(String requested) throws Exception {
        if (StrUtil.isNotBlank(requested) && !"all".equalsIgnoreCase(requested)) {
            return Collections.singletonList(resolveProfileName(requested));
        }
        if (profileManager == null || profileContext == null) {
            throw new IllegalStateException("Dashboard Profile aggregation is unavailable.");
        }
        List<DashboardProfileContext.Scope> scopes = new ArrayList<DashboardProfileContext.Scope>();
        for (ProfileView view : profileManager.listProfileViews()) {
            scopes.add(profileContext.resolve(view.getName()));
        }
        return scopes;
    }

    /** 解析不依赖当前 Context 的单个 Profile 名。 */
    private DashboardProfileContext.Scope resolveProfileName(String profile) {
        if (profileContext == null) {
            throw new IllegalStateException("Dashboard Profile scope is unavailable.");
        }
        return profileContext.resolve(profile);
    }

    /** 判断是否需要交给目标 Profile 的独立网关。 */
    private boolean isTarget(DashboardProfileContext.Scope scope) {
        return scope != null && !scope.isCurrent();
    }

    /** 创建绑定目标 Profile、并使用其独立认证令牌的回环客户端。 */
    private DashboardProfileGatewayClient client(
            Context context, DashboardProfileContext.Scope scope) {
        return new DashboardProfileGatewayClient(
                profileContext, scope, context == null ? null : context.header("Authorization"));
    }

    /** 复制请求体并移除仅供机器级路由使用的 profile 字段。 */
    private Map<String, Object> withoutProfile(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (body != null) {
            result.putAll(body);
        }
        result.remove("profile");
        return result;
    }

    /** 构建少量固定查询参数，空值不发送。 */
    private Map<String, String> query(String... pairs) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (pairs == null) {
            return result;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                result.put(pairs[i], pairs[i + 1]);
            }
        }
        return result;
    }

    /** 定义会话Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface SessionAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
