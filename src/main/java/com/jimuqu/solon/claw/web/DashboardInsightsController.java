package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台洞察相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardInsightsController {
    /** 注入洞察服务，用于调用对应业务能力。 */
    private final DashboardInsightsService insightsService;

    /**
     * 创建控制台洞察控制器实例，并注入运行所需依赖。
     *
     * @param insightsService insights服务依赖。
     */
    public DashboardInsightsController(DashboardInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /**
     * 执行overview相关逻辑。
     *
     * @return 返回overview结果。
     */
    @Mapping(value = "/api/insights/overview", method = MethodType.GET)
    public Map<String, Object> overview() {
        return DashboardResponse.ok(insightsService.overview());
    }

    /**
     * 执行技能用量相关逻辑。
     *
     * @return 返回技能用量结果。
     */
    @Mapping(value = "/api/insights/skills", method = MethodType.GET)
    public Map<String, Object> skillUsage() {
        return DashboardResponse.ok(insightsService.skillUsage());
    }
}
