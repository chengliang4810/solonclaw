package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.proactive.ProactiveDispatchService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 统一诊断接口。 */
@Controller
public class DashboardDiagnosticsController {
    /** 记录诊断接口请求参数降级的低敏诊断日志，不输出请求载荷。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardDiagnosticsController.class);

    /** 注入诊断服务，用于调用对应业务能力。 */
    private final DashboardDiagnosticsService diagnosticsService;

    /** 注入诊断服务，用于调用对应业务能力。 */
    private final DashboardGatewayDoctorService doctorService;

    /** 主动协作投递服务，仅处理用户明确确认的结果不确定重试。 */
    private final ProactiveDispatchService proactiveDispatchService;

    /**
     * 创建控制台诊断控制器实例，并注入运行所需依赖。
     *
     * @param diagnosticsService diagnostics服务依赖。
     * @param doctorService doctor服务依赖。
     * @param proactiveDispatchService 主动协作人工重试服务。
     */
    public DashboardDiagnosticsController(
            DashboardDiagnosticsService diagnosticsService,
            DashboardGatewayDoctorService doctorService,
            ProactiveDispatchService proactiveDispatchService) {
        this.diagnosticsService = diagnosticsService;
        this.doctorService = doctorService;
        this.proactiveDispatchService = proactiveDispatchService;
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    @Mapping(value = "/api/diagnostics", method = MethodType.GET)
    public Map<String, Object> diagnostics() {
        return DashboardResponse.ok(diagnosticsService.diagnostics());
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    @Mapping(value = "/api/diagnostics/doctor", method = MethodType.GET)
    public Map<String, Object> doctor() throws Exception {
        DashboardGatewayDoctorService service = doctorService;
        if (service == null) {
            return DashboardResponse.error("DIAGNOSTICS_DOCTOR_UNAVAILABLE", "Doctor 诊断服务尚未启用。");
        }
        return DashboardResponse.ok(service.doctor());
    }

    /**
     * 对用户明确选择的投递结果不确定记录执行一次人工重试，不接受批量或自动重试。
     *
     * @param context 当前 Dashboard 请求上下文。
     * @return 重试结果；候选已变化时返回冲突状态。
     */
    @Mapping(value = "/api/diagnostics/proactive/retry", method = MethodType.POST)
    public Map<String, Object> retryProactiveDelivery(Context context) {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return DashboardResponse.error(
                    context, 400, "PROACTIVE_RETRY_BAD_REQUEST", payload.message);
        }
        String candidateId = String.valueOf(payload.getPayload().get("candidateId"));
        if (StrUtil.isBlank(candidateId) || "null".equals(candidateId)) {
            return DashboardResponse.error(
                    context, 400, "PROACTIVE_RETRY_BAD_REQUEST", "缺少 candidateId。");
        }
        if (proactiveDispatchService == null) {
            return DashboardResponse.error(
                    context, 503, "PROACTIVE_RETRY_UNAVAILABLE", "主动协作人工重试服务尚未启用。");
        }
        try {
            ProactiveDecisionRecord record =
                    proactiveDispatchService.retryUnknown(candidateId.trim(), null);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("candidate_id", record.getCandidateId());
            result.put("decision_id", record.getDecisionId());
            result.put("delivery_status", record.getDeliveryStatus());
            result.put("manual_retry", Boolean.TRUE);
            return DashboardResponse.ok(result);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 404, "PROACTIVE_RETRY_NOT_FOUND", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 409, "PROACTIVE_RETRY_CONFLICT", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "PROACTIVE_RETRY_FAILED", e);
        }
    }

    /**
     * 执行安全审计相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回安全审计结果。
     */
    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/security-audit", method = MethodType.POST)
    public Map<String, Object> securityAudit(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.securityAudit(payload.getPayload()));
    }

    /**
     * 执行子进程EnvironmentProbe相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回子进程Environment Probe结果。
     */
    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/subprocess-environment/probe", method = MethodType.POST)
    public Map<String, Object> subprocessEnvironmentProbe(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(
                diagnosticsService.subprocessEnvironmentProbe(payload.getPayload()));
    }

    /**
     * 执行待恢复Approvals相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回pending Approvals结果。
     */
    @Mapping(value = "/api/diagnostics/approvals", method = MethodType.GET)
    public Map<String, Object> pendingApprovals(Context context) throws Exception {
        return DashboardResponse.ok(
                diagnosticsService.pendingApprovals(limit(context.param("limit"))));
    }

    /**
     * 执行审批历史相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回审批历史结果。
     */
    @Mapping(value = "/api/diagnostics/approvals/history", method = MethodType.GET)
    public Map<String, Object> approvalHistory(Context context) throws Exception {
        return DashboardResponse.ok(
                diagnosticsService.approvalHistory(limit(context.param("limit"))));
    }

    /**
     * 执行alwaysApprovals相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回always Approvals结果。
     */
    @Mapping(value = "/api/diagnostics/approvals/always", method = MethodType.GET)
    public Map<String, Object> alwaysApprovals(Context context) {
        return DashboardResponse.ok(
                diagnosticsService.alwaysApprovals(limit(context.param("limit"))));
    }

    /**
     * 执行revokeAlways审批相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回revoke Always审批结果。
     */
    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/approvals/always/revoke", method = MethodType.POST)
    public Map<String, Object> revokeAlwaysApproval(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.revokeAlwaysApproval(payload.getPayload()));
    }

    /**
     * 执行待恢复斜杠命令Confirms相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回pending Slash Confirms结果。
     */
    @Mapping(value = "/api/diagnostics/slash-confirms", method = MethodType.GET)
    public Map<String, Object> pendingSlashConfirms(Context context) {
        return DashboardResponse.ok(
                diagnosticsService.pendingSlashConfirms(limit(context.param("limit"))));
    }

    /**
     * 解析Slash Confirm。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回解析后的Slash Confirm。
     */
    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/slash-confirms/resolve", method = MethodType.POST)
    public Map<String, Object> resolveSlashConfirm(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.resolveSlashConfirm(payload.getPayload()));
    }

    /**
     * 解析审批。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回解析后的审批。
     */
    @SuppressWarnings("unchecked")
    @Mapping(value = "/api/diagnostics/approvals/resolve", method = MethodType.POST)
    public Map<String, Object> resolveApproval(Context context) throws Exception {
        PayloadResult payload = payload(context);
        if (!payload.isSuccess()) {
            return payload.error();
        }
        return DashboardResponse.ok(diagnosticsService.resolveApproval(payload.getPayload()));
    }

    /**
     * 执行载荷相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回payload结果。
     */
    @SuppressWarnings("unchecked")
    private PayloadResult payload(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            return PayloadResult.error(
                    "DIAGNOSTICS_BAD_REQUEST", "请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return PayloadResult.ok(new LinkedHashMap<String, Object>());
        }
        try {
            Object body = ONode.ofJson(raw).toData();
            if (body instanceof Map) {
                return PayloadResult.ok((Map<String, Object>) body);
            }
            return PayloadResult.error(
                    "DIAGNOSTICS_BAD_REQUEST",
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (Exception e) {
            return PayloadResult.error(
                    "DIAGNOSTICS_BAD_REQUEST", "请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    /**
     * 执行限制相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回限制结果。
     */
    private int limit(String value) {
        try {
            return value == null ? 100 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            log.debug("诊断接口limit解析失败，使用默认限制 error={}", e.getClass().getSimpleName());
            return 100;
        }
    }

    /** 表示载荷结果，携带调用方后续判断所需信息。 */
    private static final class PayloadResult {
        /** 保存载荷映射，便于按键快速查询。 */
        private final Map<String, Object> payload;

        /** 记录载荷中的code。 */
        private final String code;

        /** 记录载荷中的消息。 */
        private final String message;

        /**
         * 创建Payload结果实例，并注入运行所需依赖。
         *
         * @param payload 待签名或解析的载荷内容。
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         */
        private PayloadResult(Map<String, Object> payload, String code, String message) {
            this.payload = payload;
            this.code = code;
            this.message = message;
        }

        /**
         * 构造成功结果。
         *
         * @param payload 待签名或解析的载荷内容。
         * @return 返回ok结果。
         */
        static PayloadResult ok(Map<String, Object> payload) {
            return new PayloadResult(payload, null, null);
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         * @return 返回error结果。
         */
        static PayloadResult error(String code, String message) {
            return new PayloadResult(null, code, message);
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        boolean isSuccess() {
            return code == null;
        }

        /**
         * 读取Payload。
         *
         * @return 返回读取到的Payload。
         */
        Map<String, Object> getPayload() {
            return payload == null ? new LinkedHashMap<String, Object>() : payload;
        }

        /**
         * 执行错误相关逻辑。
         *
         * @return 返回error结果。
         */
        Map<String, Object> error() {
            return DashboardResponse.error(code, message);
        }
    }
}
