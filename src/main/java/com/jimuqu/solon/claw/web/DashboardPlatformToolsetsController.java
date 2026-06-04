package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard platform toolset policy API. */
@Controller
public class DashboardPlatformToolsetsController {
    private final DashboardPlatformToolsetsService platformToolsetsService;

    public DashboardPlatformToolsetsController(
            DashboardPlatformToolsetsService platformToolsetsService) {
        this.platformToolsetsService = platformToolsetsService;
    }

    @Mapping(value = "/api/tools/platform-toolsets", method = MethodType.GET)
    public Map<String, Object> overview() {
        return DashboardResponse.ok(platformToolsetsService.overview());
    }

    @Mapping(value = "/api/tools/platform-toolsets/{platform}", method = MethodType.PUT)
    public Map<String, Object> update(Context context, String platform) {
        try {
            return DashboardResponse.ok(platformToolsetsService.update(platform, body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PLATFORM_TOOLSETS_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("PLATFORM_TOOLSETS_BAD_REQUEST", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (StrUtil.isBlank(raw)) {
            return java.util.Collections.emptyMap();
        }
        try {
            ONode node = ONode.ofJson(raw);
            Object data = node.toData();
            if (data instanceof Map) {
                return (Map<String, Object>) data;
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
