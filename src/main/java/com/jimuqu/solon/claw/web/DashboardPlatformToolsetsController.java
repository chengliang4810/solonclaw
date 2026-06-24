package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台平台Toolsets相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardPlatformToolsetsController {
    /** 注入平台Toolsets服务，用于调用对应业务能力。 */
    private final DashboardPlatformToolsetsService platformToolsetsService;

    /**
     * 创建控制台平台Toolsets控制器实例，并注入运行所需依赖。
     *
     * @param platformToolsetsService 平台Toolsets服务依赖。
     */
    public DashboardPlatformToolsetsController(
            DashboardPlatformToolsetsService platformToolsetsService) {
        this.platformToolsetsService = platformToolsetsService;
    }

    /**
     * 执行overview相关逻辑。
     *
     * @return 返回overview结果。
     */
    @Mapping(value = "/api/tools/platform-toolsets", method = MethodType.GET)
    public Map<String, Object> overview() {
        return DashboardResponse.ok(platformToolsetsService.overview());
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param platform 平台参数。
     * @return 返回更新结果。
     */
    @Mapping(value = "/api/tools/platform-toolsets/{platform}", method = MethodType.PUT)
    public Map<String, Object> update(Context context, String platform) {
        try {
            return DashboardResponse.ok(
                    platformToolsetsService.update(
                            platform, DashboardRequestBodies.jsonObjectMap(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PLATFORM_TOOLSETS_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("PLATFORM_TOOLSETS_BAD_REQUEST", e.getMessage());
        }
    }
}
