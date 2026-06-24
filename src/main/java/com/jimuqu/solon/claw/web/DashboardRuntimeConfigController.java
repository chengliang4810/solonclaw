package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 运行时配置接口。 */
@Controller
public class DashboardRuntimeConfigController {
    /** 注入运行时配置服务，用于调用对应业务能力。 */
    private final DashboardRuntimeConfigService runtimeConfigService;

    /** 注入认证服务，用于调用对应业务能力。 */
    private final DashboardAuthService authService;

    /**
     * 创建控制台运行时配置控制器实例，并注入运行所需依赖。
     *
     * @param runtimeConfigService 运行时配置Service配置对象。
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
     * @return 返回配置Items结果。
     */
    @Mapping(value = "/api/runtime-config", method = MethodType.GET)
    public Map<String, Object> configItems() {
        return DashboardResponse.ok(runtimeConfigService.getConfigItems());
    }

    /**
     * 执行set相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回set结果。
     */
    @Mapping(value = "/api/runtime-config", method = MethodType.PUT)
    public Map<String, Object> set(Context context) throws Exception {
        try {
            ONode body = DashboardRequestBodies.jsonObject(context);
            return DashboardResponse.ok(
                    runtimeConfigService.set(
                            body.get("key").getString(), body.get("value").getString()));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回remove结果。
     */
    @Mapping(value = "/api/runtime-config", method = MethodType.DELETE)
    public Map<String, Object> remove(Context context) throws Exception {
        String key = context.param("key");
        try {
            if (StrUtil.isBlank(key) && StrUtil.isNotBlank(context.body())) {
                ONode body = DashboardRequestBodies.jsonObject(context);
                key = body.get("key").getString();
            }
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        }
        if (StrUtil.isBlank(key)) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", "配置项 key 不能为空");
        }
        try {
            return DashboardResponse.ok(runtimeConfigService.remove(key));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 执行reveal相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回reveal结果。
     */
    @Mapping(value = "/api/runtime-config/reveal", method = MethodType.POST)
    public Map<String, Object> reveal(Context context) throws Exception {
        if (!authService.allowReveal()) {
            context.status(429);
            return DashboardResponse.error(
                    "RUNTIME_CONFIG_RATE_LIMITED", "Reveal rate limit exceeded");
        }
        try {
            ONode body = DashboardRequestBodies.jsonObject(context);
            return DashboardResponse.ok(runtimeConfigService.reveal(body.get("key").getString()));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }
}
