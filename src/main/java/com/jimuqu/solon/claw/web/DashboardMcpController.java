package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard MCP endpoints. */
@Controller
public class DashboardMcpController {
    private final DashboardMcpService mcpService;

    public DashboardMcpController(DashboardMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @Mapping(value = "/api/jimuqu/mcp", method = MethodType.GET)
    public Map<String, Object> list() throws Exception {
        return DashboardResponse.ok(mcpService.list());
    }

    @Mapping(value = "/api/jimuqu/mcp", method = MethodType.POST)
    public Map<String, Object> save(Context context) throws Exception {
        return DashboardResponse.ok(
                mcpService.save(
                        ONode.deserialize(
                                ONode.ofJson(context.body()).toJson(), LinkedHashMap.class)));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/check", method = MethodType.POST)
    public Map<String, Object> check(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.check(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/connect", method = MethodType.POST)
    public Map<String, Object> connect(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.connect(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/reload", method = MethodType.POST)
    public Map<String, Object> reload(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.reload(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/tools/refresh", method = MethodType.POST)
    public Map<String, Object> refreshTools(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.refreshTools(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/status", method = MethodType.GET)
    public Map<String, Object> oauthStatus(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.oauthStatus(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/begin", method = MethodType.POST)
    public Map<String, Object> beginOAuth(String serverId, Context context) throws Exception {
        return DashboardResponse.ok(
                mcpService.beginOAuth(
                        serverId,
                        ONode.deserialize(
                                ONode.ofJson(context.body()).toJson(), LinkedHashMap.class)));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/callback", method = MethodType.GET)
    public Map<String, Object> oauthCallback(String serverId, Context context) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", context.param("code"));
        body.put("state", context.param("state"));
        body.put("error", context.param("error"));
        return DashboardResponse.ok(mcpService.completeOAuth(serverId, body));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/callback", method = MethodType.POST)
    public Map<String, Object> completeOAuth(String serverId, Context context) throws Exception {
        return DashboardResponse.ok(
                mcpService.completeOAuth(
                        serverId,
                        ONode.deserialize(
                                ONode.ofJson(context.body()).toJson(), LinkedHashMap.class)));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/refresh", method = MethodType.POST)
    public Map<String, Object> refreshOAuth(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.refreshOAuth(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/handle-401", method = MethodType.POST)
    public Map<String, Object> handleOAuth401(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.handleOAuth401(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}/oauth/clear", method = MethodType.POST)
    public Map<String, Object> clearOAuth(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.clearOAuth(serverId));
    }

    @Mapping(value = "/api/jimuqu/mcp/{serverId}", method = MethodType.DELETE)
    public Map<String, Object> delete(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.delete(serverId));
    }
}
