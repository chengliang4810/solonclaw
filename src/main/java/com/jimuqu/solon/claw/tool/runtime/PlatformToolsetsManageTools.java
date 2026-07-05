package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardPlatformToolsetsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供国内渠道平台工具集管理工具，复用 Dashboard 平台工具集服务。 */
public class PlatformToolsetsManageTools {
    /** Dashboard 平台工具集服务，用于复用平台白名单、规范化和配置保存逻辑。 */
    private final DashboardPlatformToolsetsService dashboardPlatformToolsetsService;

    /**
     * 创建平台工具集管理工具。
     *
     * @param dashboardPlatformToolsetsService Dashboard 平台工具集服务。
     */
    public PlatformToolsetsManageTools(
            DashboardPlatformToolsetsService dashboardPlatformToolsetsService) {
        this.dashboardPlatformToolsetsService = dashboardPlatformToolsetsService;
    }

    /**
     * 查询或更新平台工具集策略。
     *
     * @param action 操作名称。
     * @param platform 国内渠道平台名。
     * @param bodyJson 更新请求体 JSON。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "platform_toolsets_manage",
            description =
                    "Manage platform toolset policy for domestic channels. Actions: overview, update, save, save_toolsets. Supported platforms: feishu, dingtalk, wecom, weixin, qqbot, yuanbao.")
    public String platformToolsetsManage(
            @Param(name = "action", description = "overview, update, save, save_toolsets")
                    String action,
            @Param(name = "platform", required = false, description = "Platform for update")
                    String platform,
            @Param(
                            name = "body_json",
                            required = false,
                            description =
                                    "JSON body with enabledToolsets, disabledToolsets, approvalRequired for action=update")
                    String bodyJson) {
        try {
            if (dashboardPlatformToolsetsService == null) {
                return ToolResultEnvelope.error("platform toolsets service unavailable").toJson();
            }
            Map<String, Object> result = run(action, platform, bodyJson);
            return ToolResultEnvelope.ok("平台工具集管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行平台工具集管理动作。
     *
     * @param action 操作名称。
     * @param platform 国内渠道平台名。
     * @param bodyJson 更新请求体 JSON。
     * @return 返回服务结果。
     */
    private Map<String, Object> run(String action, String platform, String bodyJson) {
        String normalized =
                action == null ? "overview" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("update".equals(normalized)
                || "save".equals(normalized)
                || "save_toolsets".equals(normalized)) {
            return dashboardPlatformToolsetsService.update(platform, body(bodyJson));
        }
        return dashboardPlatformToolsetsService.overview();
    }

    /**
     * 解析更新平台工具集时的请求体 JSON。
     *
     * @param bodyJson 请求体 JSON。
     * @return 返回请求体 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String bodyJson) {
        if (bodyJson == null || bodyJson.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return ONode.deserialize(bodyJson, LinkedHashMap.class);
    }
}
