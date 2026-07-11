package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;
import org.noear.solon.core.handle.UploadedFile;

/** Dashboard chat 运行接口。 */
@Controller
public class DashboardChatController {
    /** 注入聊天服务，用于调用对应业务能力。 */
    private final DashboardChatService chatService;

    /** 解析请求指定的 Profile；旧构造路径为空时保持当前聊天运行。 */
    @Inject(required = false)
    private DashboardProfileContext profileContext;

    /**
     * 创建控制台Chat控制器实例，并注入运行所需依赖。
     *
     * @param chatService 聊天服务依赖。
     */
    public DashboardChatController(DashboardChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 创建显式注入 Profile 上下文的 Chat 控制器，供嵌入式运行和隔离测试使用。
     *
     * @param chatService 当前 Profile 的 Chat 服务。
     * @param profileContext 机器级 Profile 请求上下文。
     */
    public DashboardChatController(
            DashboardChatService chatService, DashboardProfileContext profileContext) {
        this.chatService = chatService;
        this.profileContext = profileContext;
    }

    /**
     * 执行uploads相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param file 文件或目录路径参数。
     * @return 返回uploads结果。
     */
    @Mapping(value = "/api/chat/uploads", method = MethodType.POST, multipart = true)
    public Map<String, Object> uploads(Context context, UploadedFile[] file) {
        boolean forwarded = false;
        try {
            DashboardProfileContext.Scope scope = resolve(context, null);
            if (isTarget(scope)) {
                forwarded = true;
                return client(context, scope).upload(file);
            }
            return chatService.uploads(file);
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (DashboardProfileGatewayException e) {
            return DashboardResponse.error(context, e.getStatus(), e.getCode(), e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "CHAT_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "CHAT_FAILED", e);
        } finally {
            if (forwarded) {
                deleteUploads(file);
            }
        }
    }

    /**
     * 启动运行。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回运行结果。
     */
    @Mapping(value = "/api/chat/runs", method = MethodType.POST)
    public Map<String, Object> startRun(Context context) {
        try {
            ONode body = DashboardRequestBodies.jsonObject(context);
            Map<String, Object> values = ONode.deserialize(body.toJson(), LinkedHashMap.class);
            DashboardProfileContext.Scope scope = resolve(context, values);
            if (isTarget(scope)) {
                return client(context, scope)
                        .request(
                                "POST",
                                Collections.<String, String>emptyMap(),
                                withoutProfile(values),
                                "api",
                                "chat",
                                "runs");
            }
            return chatService.startRun(body);
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (DashboardProfileGatewayException e) {
            return DashboardResponse.error(context, e.getStatus(), e.getCode(), e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "CHAT_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "CHAT_FAILED", e);
        }
    }

    /**
     * 执行events相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param runId 运行标识。
     */
    @Mapping(value = "/api/chat/runs/{runId}/events", method = MethodType.GET)
    public void events(Context context, String runId) throws Exception {
        try {
            DashboardProfileContext.Scope scope = resolve(context, null);
            if (isTarget(scope)) {
                client(context, scope).stream(context, "api", "chat", "runs", runId, "events");
                return;
            }
            chatService.streamEvents(runId, context);
        } catch (DashboardProfileNotFoundException e) {
            writeError(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (DashboardProfileGatewayException e) {
            writeError(context, e.getStatus(), e.getCode(), e);
        }
    }

    /**
     * 执行cancel相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param runId 运行标识。
     * @return 返回cancel结果。
     */
    @Mapping(value = "/api/chat/runs/{runId}/cancel", method = MethodType.POST)
    public Map<String, Object> cancel(Context context, String runId) {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            DashboardProfileContext.Scope scope = resolve(context, body);
            if (isTarget(scope)) {
                return client(context, scope)
                        .request(
                                "POST",
                                Collections.<String, String>emptyMap(),
                                withoutProfile(body),
                                "api",
                                "chat",
                                "runs",
                                runId,
                                "cancel");
            }
            return chatService.cancelRun(runId);
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (DashboardProfileGatewayException e) {
            return DashboardResponse.error(context, e.getStatus(), e.getCode(), e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 404, "CHAT_NOT_FOUND", e);
        }
    }

    /** 解析 query 或非空 body.profile，body 优先。 */
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

    /** 判断请求是否需要交给目标 Profile 独立网关。 */
    private boolean isTarget(DashboardProfileContext.Scope scope) {
        return scope != null && !scope.isCurrent();
    }

    /** 创建绑定目标 Profile 的回环客户端。 */
    private DashboardProfileGatewayClient client(
            Context context, DashboardProfileContext.Scope scope) {
        return new DashboardProfileGatewayClient(
                profileContext, scope, context == null ? null : context.header("Authorization"));
    }

    /** 复制 JSON 写请求并移除机器级路由字段。 */
    private Map<String, Object> withoutProfile(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (body != null) {
            result.putAll(body);
        }
        result.remove("profile");
        return result;
    }

    /** 把 Profile SSE 路由错误写成普通 JSON 错误响应。 */
    private void writeError(Context context, int status, String code, Throwable error)
            throws IOException {
        context.status(status);
        context.contentType("application/json;charset=UTF-8");
        context.output(ONode.serialize(DashboardResponse.error(code, error.getMessage())));
    }

    /** 删除已由回环上传消费的 Solon 临时文件。 */
    private void deleteUploads(UploadedFile[] files) {
        if (files == null) {
            return;
        }
        for (UploadedFile file : files) {
            if (file == null) {
                continue;
            }
            try {
                file.delete();
            } catch (IOException ignored) {
                // Solon 会在请求结束后继续回收上传临时文件。
            }
        }
    }
}
