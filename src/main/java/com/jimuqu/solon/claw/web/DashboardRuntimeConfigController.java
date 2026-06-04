package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 运行时配置接口。 */
@Controller
public class DashboardRuntimeConfigController {
    private final DashboardRuntimeConfigService runtimeConfigService;
    private final DashboardAuthService authService;

    public DashboardRuntimeConfigController(
            DashboardRuntimeConfigService runtimeConfigService, DashboardAuthService authService) {
        this.runtimeConfigService = runtimeConfigService;
        this.authService = authService;
    }

    @Mapping(value = "/api/runtime-config", method = MethodType.GET)
    public Map<String, Object> configItems() {
        return DashboardResponse.ok(runtimeConfigService.getConfigItems());
    }

    @Mapping(value = "/api/runtime-config", method = MethodType.PUT)
    public Map<String, Object> set(Context context) throws Exception {
        try {
            ONode body = body(context);
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

    @Mapping(value = "/api/runtime-config", method = MethodType.DELETE)
    public Map<String, Object> remove(Context context) throws Exception {
        String key = context.param("key");
        try {
            if (StrUtil.isBlank(key) && StrUtil.isNotBlank(context.body())) {
                ONode body = body(context);
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

    @Mapping(value = "/api/runtime-config/reveal", method = MethodType.POST)
    public Map<String, Object> reveal(Context context) throws Exception {
        if (!authService.allowReveal()) {
            context.status(429);
            return DashboardResponse.error(
                    "RUNTIME_CONFIG_RATE_LIMITED", "Reveal rate limit exceeded");
        }
        try {
            ONode body = body(context);
            return DashboardResponse.ok(runtimeConfigService.reveal(body.get("key").getString()));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("RUNTIME_CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    private ONode body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (StrUtil.isBlank(raw)) {
            return new ONode();
        }
        try {
            ONode node = ONode.ofJson(raw);
            Object data = node.toData();
            if (data instanceof Map) {
                return node;
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }
}
