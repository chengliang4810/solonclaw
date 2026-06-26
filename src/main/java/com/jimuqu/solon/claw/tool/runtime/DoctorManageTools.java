package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;

/** 提供消息网关 Doctor 诊断工具，复用 Dashboard Doctor 聚合服务。 */
public class DoctorManageTools {
    /** Dashboard Doctor 服务，用于复用模型、渠道、配置和关闭诊断聚合逻辑。 */
    private final DashboardGatewayDoctorService doctorService;

    /**
     * 创建 Doctor 管理工具。
     *
     * @param doctorService Dashboard Doctor 服务。
     */
    public DoctorManageTools(DashboardGatewayDoctorService doctorService) {
        this.doctorService = doctorService;
    }

    /**
     * 查询消息网关 Doctor 诊断结果。
     *
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "doctor_manage",
            description = "Inspect dashboard gateway doctor diagnostics.")
    public String doctorManage() {
        try {
            if (doctorService == null) {
                return ToolResultEnvelope.error("doctor service unavailable").toJson();
            }
            Map<String, Object> result = doctorService.doctor();
            return ToolResultEnvelope.ok("Doctor 诊断完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }
}
