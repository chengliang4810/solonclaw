package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 会话接口。 */
@Controller
public class DashboardSessionController {
    private final DashboardSessionService sessionService;

    public DashboardSessionController(DashboardSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Mapping(value = "/api/sessions", method = MethodType.GET)
    public Map<String, Object> sessions(Context context) throws Exception {
        return DashboardResponse.ok(
                sessionService.getSessions(
                        context.paramAsInt("limit", 20), context.paramAsInt("offset", 0)));
    }

    @Mapping(value = "/api/sessions/search", method = MethodType.GET)
    public Map<String, Object> search(Context context) throws Exception {
        return DashboardResponse.ok(sessionService.searchSessions(context.param("q")));
    }

    @Mapping(value = "/api/sessions/{id}/messages", method = MethodType.GET)
    public Map<String, Object> messages(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.getSessionMessages(id);
                    }
                });
    }

    @Mapping(value = "/api/sessions/{id}/recap", method = MethodType.GET)
    public Map<String, Object> recap(String id, Context context) throws Exception {
        return DashboardResponse.ok(sessionService.recap(id, context.paramAsInt("limit", 10)));
    }

    @Mapping(value = "/api/sessions/{id}/trajectory", method = MethodType.GET)
    public Map<String, Object> trajectory(String id, Context context) throws Exception {
        String userQuery = context.param("user_query");
        if (userQuery == null || userQuery.trim().length() == 0) {
            userQuery = context.param("userQuery");
        }
        boolean completed = !"false".equalsIgnoreCase(context.param("completed"));
        return DashboardResponse.ok(sessionService.trajectory(id, userQuery, completed));
    }

    @Mapping(value = "/api/sessions/{id}/trajectory/save", method = MethodType.POST)
    public Map<String, Object> saveTrajectory(String id, Context context) throws Exception {
        String userQuery = context.param("user_query");
        if (userQuery == null || userQuery.trim().length() == 0) {
            userQuery = context.param("userQuery");
        }
        boolean completed = !"false".equalsIgnoreCase(context.param("completed"));
        return DashboardResponse.ok(sessionService.saveTrajectory(id, userQuery, completed));
    }

    @Mapping(value = "/api/sessions/{id}/tree", method = MethodType.GET)
    public Map<String, Object> tree(String id) throws Exception {
        return DashboardResponse.ok(sessionService.sessionTree(id));
    }

    @Mapping(value = "/api/sessions/{id}/latest-descendant", method = MethodType.GET)
    public Map<String, Object> latestDescendant(String id) throws Exception {
        return DashboardResponse.ok(sessionService.latestDescendant(id));
    }

    @Mapping(value = "/api/sessions/{id}", method = MethodType.PUT)
    public Map<String, Object> update(String id, Context context) throws Exception {
        return safeSession(
                context,
                new SessionAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.updateSession(id, body(context));
                    }
                });
    }

    @Mapping(value = "/api/sessions/{id}/checkpoints", method = MethodType.GET)
    public Map<String, Object> checkpoints(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.checkpoints(id);
                    }
                });
    }

    @Mapping(value = "/api/checkpoints/{id}/preview", method = MethodType.GET)
    public Map<String, Object> checkpointPreview(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.checkpointPreview(id);
                    }
                });
    }

    @Mapping(value = "/api/checkpoints/{id}/rollback", method = MethodType.POST)
    public Map<String, Object> rollbackCheckpoint(String id) throws Exception {
        return safeSession(
                Context.current(),
                new SessionAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return sessionService.rollbackCheckpoint(id);
                    }
                });
    }

    @Mapping(value = "/api/sessions/{id}", method = MethodType.DELETE)
    public Map<String, Object> delete(String id) throws Exception {
        return DashboardResponse.ok(sessionService.deleteSession(id));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return java.util.Collections.emptyMap();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return ONode.deserialize(node.toJson(), java.util.LinkedHashMap.class);
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

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

    private interface SessionAction {
        Map<String, Object> run() throws Exception;
    }
}
