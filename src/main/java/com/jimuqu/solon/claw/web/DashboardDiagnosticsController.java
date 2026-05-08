package com.jimuqu.solon.claw.web;

import java.util.Map;
import java.util.LinkedHashMap;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 统一诊断接口。 */
@Controller
public class DashboardDiagnosticsController {
    private final DashboardDiagnosticsService diagnosticsService;

    public DashboardDiagnosticsController(DashboardDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @Mapping(value = "/api/diagnostics", method = MethodType.GET)
    public Map<String, Object> diagnostics() {
        return DashboardResponse.ok(diagnosticsService.diagnostics());
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/security-audit", method = MethodType.POST)
    public Map<String, Object> securityAudit(Context context) throws Exception {
        String raw = context.body();
        Object body = raw == null || raw.trim().length() == 0 ? null : ONode.ofJson(raw).toData();
        Map<String, Object> payload =
                body instanceof Map
                        ? (Map<String, Object>) body
                        : new LinkedHashMap<String, Object>();
        return DashboardResponse.ok(diagnosticsService.securityAudit(payload));
    }
}
