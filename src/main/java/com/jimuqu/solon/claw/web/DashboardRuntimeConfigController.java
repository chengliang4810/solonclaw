package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.util.Collections;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 工作区配置接口。 */
@Controller
public class DashboardRuntimeConfigController {
    /** 注入工作区配置服务，用于调用对应业务能力。 */
    private final DashboardRuntimeConfigService runtimeConfigService;

    /** 注入认证服务，用于调用对应业务能力。 */
    private final DashboardAuthService authService;

    /**
     * 创建控制台工作区配置控制器实例，并注入运行所需依赖。
     *
     * @param runtimeConfigService 工作区配置Service配置对象。
     * @param authService 鉴权服务依赖。
     */
    public DashboardRuntimeConfigController(
            DashboardRuntimeConfigService runtimeConfigService, DashboardAuthService authService) {
        this.runtimeConfigService = runtimeConfigService;
        this.authService = authService;
    }

    /**
     * 执行配置Items相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回配置Items结果。
     */
    @Mapping(value = "/api/workspace-config", method = MethodType.GET)
    public Map<String, Object> configItems(Context context) {
        try {
            return DashboardResponse.ok(
                    runtimeConfigService.getConfigItems(
                            DashboardProfileContext.requestedProfile(context)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        }
    }

    /**
     * 本机首次启动且 Dashboard token 为空时，允许用户从登录页写入第一个访问令牌。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回首次配置结果。
     */
    @Mapping(value = "/api/workspace-config/bootstrap-dashboard-token", method = MethodType.POST)
    public Map<String, Object> bootstrapDashboardToken(Context context) throws Exception {
        if (!authService.isLocalRequest(context)) {
            context.status(403);
            return DashboardResponse.error("WORKSPACE_CONFIG_BOOTSTRAP_FORBIDDEN", "仅允许本机首次配置");
        }
        if (StrUtil.isNotBlank(authService.sessionToken())) {
            context.status(409);
            return DashboardResponse.error(
                    "WORKSPACE_CONFIG_BOOTSTRAP_ALREADY_SET", "Dashboard token 已配置");
        }
        try {
            String token =
                    DashboardRequestBodies.jsonObject(context).get("accessToken").getString();
            if (!SecretValueGuard.hasUsableSecret(token, 8)) {
                context.status(400);
                return DashboardResponse.error(
                        "WORKSPACE_CONFIG_BOOTSTRAP_BAD_TOKEN", "访问令牌至少需要 8 个有效字符");
            }
            runtimeConfigService.set("solonclaw.dashboard.accessToken", token, false);
            return DashboardResponse.ok(
                    Collections.<String, Object>singletonMap("configured", true));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error(
                    "WORKSPACE_CONFIG_BOOTSTRAP_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error(
                    "WORKSPACE_CONFIG_BOOTSTRAP_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 执行set相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回set结果。
     */
    @Mapping(value = "/api/workspace-config", method = MethodType.PUT)
    public Map<String, Object> set(Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return DashboardResponse.ok(
                    runtimeConfigService.set(
                            body.get("key") == null ? "" : String.valueOf(body.get("key")),
                            body.get("value") == null ? "" : String.valueOf(body.get("value")),
                            DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 持久化插件启用状态；插件管理器将在后续会话或进程重启时重新读取该配置。
     *
     * @param context 当前请求上下文。
     * @param plugin 插件名称。
     * @return Dashboard 响应包装后的持久化结果。
     */
    @Mapping(value = "/api/plugins/{plugin}/enabled", method = MethodType.PUT)
    public Map<String, Object> setPluginEnabled(Context context, String plugin) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            Object enabled = body.get("enabled");
            if (!(enabled instanceof Boolean)) {
                throw new IllegalArgumentException("enabled 必须是布尔值。");
            }
            return DashboardResponse.ok(
                    runtimeConfigService.setPluginEnabled(
                            plugin,
                            ((Boolean) enabled).booleanValue(),
                            DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PLUGIN_CONFIG_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 400, "PLUGIN_CONFIG_BAD_REQUEST", e);
        }
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回remove结果。
     */
    @Mapping(value = "/api/workspace-config", method = MethodType.DELETE)
    public Map<String, Object> remove(Context context) throws Exception {
        String key = context.param("key");
        Map<String, Object> body = Collections.emptyMap();
        try {
            if (StrUtil.isBlank(key) && StrUtil.isNotBlank(context.body())) {
                body = DashboardRequestBodies.jsonObjectMap(context);
                key = body.get("key") == null ? "" : String.valueOf(body.get("key"));
            }
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        }
        if (StrUtil.isBlank(key)) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", "配置项 key 不能为空");
        }
        try {
            return DashboardResponse.ok(
                    runtimeConfigService.remove(
                            key, DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 执行reveal相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回reveal结果。
     */
    @Mapping(value = "/api/workspace-config/reveal", method = MethodType.POST)
    public Map<String, Object> reveal(Context context) throws Exception {
        if (!authService.allowReveal()) {
            context.status(429);
            return DashboardResponse.error(
                    "WORKSPACE_CONFIG_RATE_LIMITED", "Reveal rate limit exceeded");
        }
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return DashboardResponse.ok(
                    runtimeConfigService.reveal(
                            body.get("key") == null ? "" : String.valueOf(body.get("key")),
                            DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("WORKSPACE_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }
}
