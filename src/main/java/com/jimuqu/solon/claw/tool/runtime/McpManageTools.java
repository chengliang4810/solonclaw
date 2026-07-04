package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 MCP 服务端配置和状态管理工具，复用 Dashboard MCP 服务。 */
public class McpManageTools {
    /** Dashboard MCP 服务，用于复用服务端配置、检查和重载逻辑。 */
    private final DashboardMcpService dashboardMcpService;

    /**
     * 创建 MCP 管理工具。
     *
     * @param dashboardMcpService Dashboard MCP 服务。
     */
    public McpManageTools(DashboardMcpService dashboardMcpService) {
        this.dashboardMcpService = dashboardMcpService;
    }

    /**
     * 查询或管理 MCP 服务端。
     *
     * @param action 操作名称。
     * @param serverId MCP 服务端标识。
     * @param bodyJson 保存配置时使用的 JSON 请求体。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "mcp_manage",
            description =
                    "Manage MCP servers. Actions: list, save, delete, check, connect, reload, refresh_tools, tools_refresh, reload_all, reload_all_async, oauth_status, oauth_refresh, oauth_handle_401, oauth_clear.")
    public String mcpManage(
            @Param(
                            name = "action",
                            description =
                                    "list, save, delete, check, connect, reload, refresh_tools, tools_refresh, reload_all, reload_all_async, oauth_status, oauth_refresh, oauth_handle_401, oauth_clear")
                    String action,
            @Param(name = "server_id", required = false, description = "MCP server id")
                    String serverId,
            @Param(
                            name = "body_json",
                            required = false,
                            description = "JSON body for action=save")
                    String bodyJson) {
        try {
            if (dashboardMcpService == null) {
                return ToolResultEnvelope.error("mcp service unavailable").toJson();
            }
            Map<String, Object> result = run(action, serverId, bodyJson);
            return ToolResultEnvelope.ok("MCP 管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行 MCP 管理动作。
     *
     * @param action 操作名称。
     * @param serverId MCP 服务端标识。
     * @param bodyJson 保存配置时使用的 JSON 请求体。
     * @return 返回服务结果。
     */
    private Map<String, Object> run(String action, String serverId, String bodyJson)
            throws Exception {
        String normalized =
                action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("save".equals(normalized)) {
            return dashboardMcpService.save(body(bodyJson));
        }
        if ("delete".equals(normalized)) {
            return dashboardMcpService.delete(serverId);
        }
        if ("check".equals(normalized)) {
            return dashboardMcpService.check(serverId);
        }
        if ("connect".equals(normalized)) {
            return dashboardMcpService.connect(serverId);
        }
        if ("reload".equals(normalized)) {
            return dashboardMcpService.reload(serverId);
        }
        if ("refresh_tools".equals(normalized) || "tools_refresh".equals(normalized)) {
            return dashboardMcpService.refreshTools(serverId);
        }
        if ("reload_all".equals(normalized)) {
            return dashboardMcpService.reloadAllView();
        }
        if ("reload_all_async".equals(normalized)) {
            return dashboardMcpService.reloadAllAsyncView();
        }
        if ("oauth_status".equals(normalized)) {
            return dashboardMcpService.oauthStatus(serverId);
        }
        if ("oauth_refresh".equals(normalized)) {
            return dashboardMcpService.refreshOAuth(serverId);
        }
        if ("oauth_handle_401".equals(normalized)) {
            return dashboardMcpService.handleOAuth401(serverId);
        }
        if ("oauth_clear".equals(normalized)) {
            return dashboardMcpService.clearOAuth(serverId);
        }
        return dashboardMcpService.list();
    }

    /**
     * 解析保存 MCP 服务端时的请求体 JSON。
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
