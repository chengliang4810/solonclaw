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

    @Mapping(value = "/api/diagnostics/approvals", method = MethodType.GET)
    public Map<String, Object> pendingApprovals(Context context) throws Exception {
        return DashboardResponse.ok(diagnosticsService.pendingApprovals(limit(context.param("limit"))));
    }

    @Mapping(value = "/api/diagnostics/approvals/history", method = MethodType.GET)
    public Map<String, Object> approvalHistory(Context context) throws Exception {
        return DashboardResponse.ok(diagnosticsService.approvalHistory(limit(context.param("limit"))));
    }

    @Mapping(value = "/api/diagnostics/approvals/always", method = MethodType.GET)
    public Map<String, Object> alwaysApprovals(Context context) {
        return DashboardResponse.ok(diagnosticsService.alwaysApprovals(limit(context.param("limit"))));
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/approvals/always/revoke", method = MethodType.POST)
    public Map<String, Object> revokeAlwaysApproval(Context context) throws Exception {
        String raw = context.body();
        Object body = raw == null || raw.trim().length() == 0 ? null : ONode.ofJson(raw).toData();
        Map<String, Object> payload =
                body instanceof Map
                        ? (Map<String, Object>) body
                        : new LinkedHashMap<String, Object>();
        return DashboardResponse.ok(diagnosticsService.revokeAlwaysApproval(payload));
    }

    @Mapping(value = "/api/diagnostics/slash-confirms", method = MethodType.GET)
    public Map<String, Object> pendingSlashConfirms(Context context) {
        return DashboardResponse.ok(diagnosticsService.pendingSlashConfirms(limit(context.param("limit"))));
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/slash-confirms/resolve", method = MethodType.POST)
    public Map<String, Object> resolveSlashConfirm(Context context) throws Exception {
        String raw = context.body();
        Object body = raw == null || raw.trim().length() == 0 ? null : ONode.ofJson(raw).toData();
        Map<String, Object> payload =
                body instanceof Map
                        ? (Map<String, Object>) body
                        : new LinkedHashMap<String, Object>();
        return DashboardResponse.ok(diagnosticsService.resolveSlashConfirm(payload));
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/approvals/resolve", method = MethodType.POST)
    public Map<String, Object> resolveApproval(Context context) throws Exception {
        String raw = context.body();
        Object body = raw == null || raw.trim().length() == 0 ? null : ONode.ofJson(raw).toData();
        Map<String, Object> payload =
                body instanceof Map
                        ? (Map<String, Object>) body
                        : new LinkedHashMap<String, Object>();
        return DashboardResponse.ok(diagnosticsService.resolveApproval(payload));
    }

    private int limit(String value) {
        try {
            return value == null ? 100 : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 100;
        }
    }
}
