package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 状态接口。 */
@Controller
public class DashboardStatusController {
    /** 注入状态服务，用于调用对应业务能力。 */
    private final DashboardStatusService statusService;

    /** 注入认证服务，用于调用对应业务能力。 */
    private final DashboardAuthService authService;

    /**
     * 创建控制台状态控制器实例，并注入运行所需依赖。
     *
     * @param statusService 状态服务依赖。
     * @param authService 鉴权服务依赖。
     */
    public DashboardStatusController(
            DashboardStatusService statusService, DashboardAuthService authService) {
        this.statusService = statusService;
        this.authService = authService;
    }

    /**
     * 执行状态相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回状态。
     */
    @Mapping(value = "/api/status", method = MethodType.GET)
    public Map<String, Object> status(Context context) throws Exception {
        return DashboardResponse.ok(statusService.getStatus(authService.isAuthorized(context)));
    }

    /**
     * 执行模型Info相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回模型Info结果。
     */
    @Mapping(value = "/api/model/info", method = MethodType.GET)
    public Map<String, Object> modelInfo(Context context) {
        return DashboardResponse.ok(statusService.getModelInfo(authService.isAuthorized(context)));
    }
}
