package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 分析接口。 */
@Controller
public class DashboardAnalyticsController {
    /** 注入分析服务，用于调用对应业务能力。 */
    private final DashboardAnalyticsService analyticsService;

    /**
     * 创建控制台分析控制器实例，并注入运行所需依赖。
     *
     * @param analyticsService analytics服务依赖。
     */
    public DashboardAnalyticsController(DashboardAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * 执行用量相关逻辑。
     *
     * @param days days 参数。
     * @return 返回用量结果。
     */
    @Mapping(value = "/api/analytics/usage", method = MethodType.GET)
    public Map<String, Object> usage(int days) throws Exception {
        return analyticsService.getUsage(days);
    }
}
