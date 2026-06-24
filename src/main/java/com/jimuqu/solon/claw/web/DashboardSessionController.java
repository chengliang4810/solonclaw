package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 会话接口。 */
@Controller
public class DashboardSessionController {
    /** 注入会话服务，用于调用对应业务能力。 */
    private final DashboardSessionService sessionService;

    /**
     * 创建控制台会话控制器实例，并注入运行所需依赖。
     *
     * @param sessionService 会话服务依赖。
     */
    public DashboardSessionController(DashboardSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 执行sessions相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回sessions结果。
     */
    @Mapping(value = "/api/sessions", method = MethodType.GET)
    public Map<String, Object> sessions(Context context) throws Exception {
        return DashboardResponse.ok(
                sessionService.getSessions(
                        context.paramAsInt("limit", 20), context.paramAsInt("offset", 0)));
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回搜索结果。
     */
    @Mapping(value = "/api/sessions/search", method = MethodType.GET)
    public Map<String, Object> search(Context context) throws Exception {
        return DashboardResponse.ok(sessionService.searchSessions(context.param("q")));
    }

    /**
     * 执行messages相关逻辑。
     *
     * @param id 标识。
     * @return 返回messages结果。
     */
    @Mapping(value = "/api/sessions/{id}/messages", method = MethodType.GET)
    public Map<String, Object> messages(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.getSessionMessages(id);
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
        return DashboardResponse.ok(sessionService.recap(id, context.paramAsInt("limit", 10)));
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
        return DashboardResponse.ok(sessionService.trajectory(id, userQuery, completed));
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
        return DashboardResponse.ok(sessionService.saveTrajectory(id, userQuery, completed));
    }

    /**
     * 执行tree相关逻辑。
     *
     * @param id 标识。
     * @return 返回tree结果。
     */
    @Mapping(value = "/api/sessions/{id}/tree", method = MethodType.GET)
    public Map<String, Object> tree(String id) throws Exception {
        return DashboardResponse.ok(sessionService.sessionTree(id));
    }

    /**
     * 执行latestDescendant相关逻辑。
     *
     * @param id 标识。
     * @return 返回latest Descendant结果。
     */
    @Mapping(value = "/api/sessions/{id}/latest-descendant", method = MethodType.GET)
    public Map<String, Object> latestDescendant(String id) throws Exception {
        return DashboardResponse.ok(sessionService.latestDescendant(id));
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回更新结果。
     */
    @Mapping(value = "/api/sessions/{id}", method = MethodType.PUT)
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
                        return sessionService.updateSession(
                                id, DashboardRequestBodies.jsonObjectMap(context));
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
    public Map<String, Object> checkpoints(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.checkpoints(id);
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
    public Map<String, Object> checkpointPreview(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.checkpointPreview(id);
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
    public Map<String, Object> rollbackCheckpoint(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.rollbackCheckpoint(id);
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
    public Map<String, Object> delete(String id) throws Exception {
        return DashboardResponse.ok(sessionService.deleteSession(id));
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
        } catch (IllegalArgumentException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("SESSION_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("SESSION_BAD_REQUEST", e.getMessage());
        }
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
