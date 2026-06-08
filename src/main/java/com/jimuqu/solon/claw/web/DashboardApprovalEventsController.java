package com.jimuqu.solon.claw.web;

import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台审批Events相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardApprovalEventsController {
    /** 注入审批Events服务，用于调用对应业务能力。 */
    private final DashboardApprovalEventsService approvalEventsService;

    /**
     * 创建控制台审批Events控制器实例，并注入运行所需依赖。
     *
     * @param approvalEventsService 审批Events服务依赖。
     */
    public DashboardApprovalEventsController(DashboardApprovalEventsService approvalEventsService) {
        this.approvalEventsService = approvalEventsService;
    }

    /**
     * 执行events相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回events结果。
     */
    @Mapping(value = "/api/approval/events", method = MethodType.GET)
    public Map<String, Object> events(@Param(defaultValue = "50") Integer limit) {
        int safeLimit = limit == null ? 50 : Math.min(Math.max(1, limit.intValue()), 200);
        List<Map<String, Object>> events = approvalEventsService.recentEvents(safeLimit);
        Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        data.put("events", events);
        data.put("count", Integer.valueOf(events.size()));
        return DashboardResponse.ok(data);
    }

    /**
     * 执行stats相关逻辑。
     *
     * @return 返回stats结果。
     */
    @Mapping(value = "/api/approval/stats", method = MethodType.GET)
    public Map<String, Object> stats() {
        return DashboardResponse.ok(approvalEventsService.stats());
    }
}
