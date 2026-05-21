package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.MethodType;

/** Dashboard insights API controller. */
@Controller
public class DashboardInsightsController {
    private final DashboardInsightsService insightsService;

    public DashboardInsightsController(DashboardInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @Mapping(value = "/api/insights/overview", method = MethodType.GET)
    public Map<String, Object> overview() {
        return DashboardResponse.ok(insightsService.overview());
    }

    @Mapping(value = "/api/insights/skills", method = MethodType.GET)
    public Map<String, Object> skillUsage() {
        return DashboardResponse.ok(insightsService.skillUsage());
    }
}
