package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
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
     * @param context 当前请求或运行上下文。
     * @return 返回overview结果。
     */
    @Mapping(value = "/api/tools/platform-toolsets", method = MethodType.GET)
    public Map<String, Object> overview(Context context) {
        try {
            return DashboardResponse.ok(
                    platformToolsetsService.overview(
                            DashboardProfileContext.requestedProfile(context)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        }
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
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return DashboardResponse.ok(
                    platformToolsetsService.update(
                            platform,
                            body,
                            DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PLATFORM_TOOLSETS_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 400, "PLATFORM_TOOLSETS_BAD_REQUEST", e);
        }
    }
}
