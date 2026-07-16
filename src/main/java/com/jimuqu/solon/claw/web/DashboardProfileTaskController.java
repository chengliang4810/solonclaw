package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 协作任务 REST API。 */
@Controller
public class DashboardProfileTaskController {
    /** 应用服务。 */
    private final DashboardProfileTaskService service;

    /** 创建控制器。 */
    public DashboardProfileTaskController(DashboardProfileTaskService service) {
        this.service = service;
    }

    /** 列出任务。 */
    @Mapping(value = "/api/profile-tasks", method = MethodType.GET)
    public Map<String, Object> list(Context context) {
        return execute(context, () -> service.list(context.param("assignee")));
    }

    /** 创建任务。 */
    @Mapping(value = "/api/profile-tasks", method = MethodType.POST)
    public Map<String, Object> create(Context context) {
        return execute(
                context, () -> service.create(DashboardRequestBodies.jsonObjectMap(context)));
    }

    /** 查询任务。 */
    @Mapping(value = "/api/profile-tasks/{taskId}", method = MethodType.GET)
    public Map<String, Object> get(Context context, String taskId) {
        return execute(context, () -> service.get(taskId));
    }

    /** 显式修改描述并重试。 */
    @Mapping(value = "/api/profile-tasks/{taskId}/retry", method = MethodType.POST)
    public Map<String, Object> retry(Context context, String taskId) {
        return execute(
                context,
                () -> service.retry(taskId, DashboardRequestBodies.jsonObjectMap(context)));
    }

    /** 取消任务。 */
    @Mapping(value = "/api/profile-tasks/{taskId}/cancel", method = MethodType.POST)
    public Map<String, Object> cancel(Context context, String taskId) {
        return execute(context, () -> service.cancel(taskId));
    }

    /** 统一 API 错误结构。 */
    private Map<String, Object> execute(Context context, Action action) {
        try {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("value", action.run());
            return DashboardResponse.ok(data);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(
                    context, 400, "PROFILE_TASK_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 409, "PROFILE_TASK_CONFLICT", e.getMessage());
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "PROFILE_TASK_FAILED", "协作任务操作失败。");
        }
    }

    /** 允许抛出受检异常的控制器动作。 */
    private interface Action {
        /** 执行动作。 */
        Object run() throws Exception;
    }
}
