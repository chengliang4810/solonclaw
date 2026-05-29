package com.jimuqu.solon.claw.web;

import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.MethodType;

/** Dashboard approval events API controller. */
@Controller
public class DashboardApprovalEventsController {
    private final DashboardApprovalEventsService approvalEventsService;

    public DashboardApprovalEventsController(DashboardApprovalEventsService approvalEventsService) {
        this.approvalEventsService = approvalEventsService;
    }

    @Mapping(value = "/api/approval/events", method = MethodType.GET)
    public Map<String, Object> events(@Param(defaultValue = "50") Integer limit) {
        int safeLimit = limit == null ? 50 : Math.min(Math.max(1, limit.intValue()), 200);
        List<Map<String, Object>> events = approvalEventsService.recentEvents(safeLimit);
        Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        data.put("events", events);
        data.put("count", Integer.valueOf(events.size()));
        return DashboardResponse.ok(data);
    }

    @Mapping(value = "/api/approval/stats", method = MethodType.GET)
    public Map<String, Object> stats() {
        return DashboardResponse.ok(approvalEventsService.stats());
    }
}
