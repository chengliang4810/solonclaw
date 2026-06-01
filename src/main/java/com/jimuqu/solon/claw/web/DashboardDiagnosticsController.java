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
    private final DashboardGatewayDoctorService doctorService;

    public DashboardDiagnosticsController(
            DashboardDiagnosticsService diagnosticsService, DashboardGatewayDoctorService doctorService) {
        this.diagnosticsService = diagnosticsService;
        this.doctorService = doctorService;
    }

    @Mapping(value = "/api/diagnostics", method = MethodType.GET)
    public Map<String, Object> diagnostics() {
        return DashboardResponse.ok(diagnosticsService.diagnostics());
    }

    @Mapping(value = "/api/diagnostics/doctor", method = MethodType.GET)
    public Map<String, Object> doctor() throws Exception {
        DashboardGatewayDoctorService service = doctorService;
        if (service == null) {
            return DashboardResponse.error(
                    "DIAGNOSTICS_DOCTOR_UNAVAILABLE", "Doctor 诊断服务尚未启用。");
        }
        return DashboardResponse.ok(service.doctor());
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/security-audit", method = MethodType.POST)
    public Map<String, Object> securityAudit(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.securityAudit(payload.getPayload()));
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
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.revokeAlwaysApproval(payload.getPayload()));
    }

    @Mapping(value = "/api/diagnostics/slash-confirms", method = MethodType.GET)
    public Map<String, Object> pendingSlashConfirms(Context context) {
        return DashboardResponse.ok(diagnosticsService.pendingSlashConfirms(limit(context.param("limit"))));
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/slash-confirms/resolve", method = MethodType.POST)
    public Map<String, Object> resolveSlashConfirm(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.resolveSlashConfirm(payload.getPayload()));
    }

    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/approvals/resolve", method = MethodType.POST)
    public Map<String, Object> resolveApproval(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.resolveApproval(payload.getPayload()));
    }

    @SuppressWarnings("unchecked")
    private PayloadResult payload(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            return PayloadResult.error("DIAGNOSTICS_BAD_REQUEST", "请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return PayloadResult.ok(new LinkedHashMap<String, Object>());
        }
        try {
            Object body = ONode.ofJson(raw).toData();
            if (body instanceof Map) {
                return PayloadResult.ok((Map<String, Object>) body);
            }
            return PayloadResult.error("DIAGNOSTICS_BAD_REQUEST", "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (Exception e) {
            return PayloadResult.error("DIAGNOSTICS_BAD_REQUEST", "请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private int limit(String value) {
        try {
            return value == null ? 100 : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 100;
        }
    }

    private static final class PayloadResult {
        private final Map<String, Object> payload;
        private final String code;
        private final String message;

        private PayloadResult(Map<String, Object> payload, String code, String message) {
            this.payload = payload;
            this.code = code;
            this.message = message;
        }

        static PayloadResult ok(Map<String, Object> payload) {
            return new PayloadResult(payload, null, null);
        }

        static PayloadResult error(String code, String message) {
            return new PayloadResult(null, code, message);
        }

        boolean isSuccess() {
            return code == null;
        }

        Map<String, Object> getPayload() {
            return payload == null ? new LinkedHashMap<String, Object>() : payload;
        }

        Map<String, Object> error() {
            return DashboardResponse.error(code, message);
        }
    }
}
