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

    @Mapping(value = "/api/mcp", method = MethodType.GET)
    public Map<String, Object> list() throws Exception {
        return DashboardResponse.ok(mcpService.list());
    }

    @Mapping(value = "/api/mcp", method = MethodType.POST)
    public Map<String, Object> save(Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.save(body(context));
            }
        });
    }

    @Mapping(value = "/api/mcp/reload", method = MethodType.POST)
    public Map<String, Object> reloadAll() throws Exception {
        return DashboardResponse.ok(mcpService.reloadAllView());
    }

    @Mapping(value = "/api/mcp/reload/async", method = MethodType.POST)
    public Map<String, Object> reloadAllAsync() throws Exception {
        return DashboardResponse.ok(mcpService.reloadAllAsyncView());
    }

    @Mapping(value = "/api/mcp/{serverId}/check", method = MethodType.POST)
    public Map<String, Object> check(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.check(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/connect", method = MethodType.POST)
    public Map<String, Object> connect(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.connect(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/reload", method = MethodType.POST)
    public Map<String, Object> reload(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.reload(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/tools/refresh", method = MethodType.POST)
    public Map<String, Object> refreshTools(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.refreshTools(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/status", method = MethodType.GET)
    public Map<String, Object> oauthStatus(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.oauthStatus(serverId));
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/begin", method = MethodType.POST)
    public Map<String, Object> beginOAuth(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.beginOAuth(
                        serverId,
                        body(context));
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/callback", method = MethodType.GET)
    public Map<String, Object> oauthCallback(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("code", context.param("code"));
                body.put("state", context.param("state"));
                body.put("error", context.param("error"));
                return mcpService.completeOAuth(serverId, body);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/callback", method = MethodType.POST)
    public Map<String, Object> completeOAuth(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.completeOAuth(serverId, body(context));
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/refresh", method = MethodType.POST)
    public Map<String, Object> refreshOAuth(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.refreshOAuth(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/handle-401", method = MethodType.POST)
    public Map<String, Object> handleOAuth401(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.handleOAuth401(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}/oauth/clear", method = MethodType.POST)
    public Map<String, Object> clearOAuth(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.clearOAuth(serverId);
            }
        });
    }

    @Mapping(value = "/api/mcp/{serverId}", method = MethodType.DELETE)
    public Map<String, Object> delete(String serverId, Context context) throws Exception {
        return safeMcp(context, new McpAction() {
            @Override
            public Map<String, Object> run() throws Exception {
                return mcpService.delete(serverId);
            }
        });
    }

    private Map<String, Object> safeMcp(Context context, McpAction action) throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("MCP_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("MCP_BAD_REQUEST", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Object> body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object data = ONode.ofJson(raw).toData();
            if (data instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) data);
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private interface McpAction {
        Map<String, Object> run() throws Exception;
    }
}
