package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台MCP相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardMcpController {
    /** 注入MCP服务，用于调用对应业务能力。 */
    private final DashboardMcpService mcpService;

    /**
     * 创建控制台MCP控制器实例，并注入运行所需依赖。
     *
     * @param mcpService MCP服务依赖。
     */
    public DashboardMcpController(DashboardMcpService mcpService) {
        this.mcpService = mcpService;
    }

    /**
     * 执行列表相关逻辑。
     *
     * @return 返回list结果。
     */
    @Mapping(value = "/api/mcp", method = MethodType.GET)
    public Map<String, Object> list() throws Exception {
        return DashboardResponse.ok(mcpService.list());
    }

    /**
     * 执行save，服务于控制台MCP主流程相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回save结果。
     */
    @Mapping(value = "/api/mcp", method = MethodType.POST)
    public Map<String, Object> save(Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.save(DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /**
     * 执行reload全部相关逻辑。
     *
     * @return 返回reload全部结果。
     */
    @Mapping(value = "/api/mcp/reload", method = MethodType.POST)
    public Map<String, Object> reloadAll() throws Exception {
        return DashboardResponse.ok(mcpService.reloadAllView());
    }

    /**
     * 执行reload全部异步相关逻辑。
     *
     * @return 返回reload全部Async结果。
     */
    @Mapping(value = "/api/mcp/reload/async", method = MethodType.POST)
    public Map<String, Object> reloadAllAsync() throws Exception {
        return DashboardResponse.ok(mcpService.reloadAllAsyncView());
    }

    /**
     * 执行check相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回check结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/check", method = MethodType.POST)
    public Map<String, Object> check(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.check(serverId);
                    }
                });
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回connect结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/connect", method = MethodType.POST)
    public Map<String, Object> connect(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.connect(serverId);
                    }
                });
    }

    /**
     * 重新加载目标服务端配置与工具清单。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回reload结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/reload", method = MethodType.POST)
    public Map<String, Object> reload(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.reload(serverId);
                    }
                });
    }

    /**
     * 刷新工具。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回工具结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/tools/refresh", method = MethodType.POST)
    public Map<String, Object> refreshTools(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.refreshTools(serverId);
                    }
                });
    }

    /**
     * 执行oauth状态相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回oauth状态。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/status", method = MethodType.GET)
    public Map<String, Object> oauthStatus(String serverId) throws Exception {
        return DashboardResponse.ok(mcpService.oauthStatus(serverId));
    }

    /**
     * 执行beginOAuth 认证相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回begin OAuth 认证结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/begin", method = MethodType.POST)
    public Map<String, Object> beginOAuth(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.beginOAuth(
                                serverId, DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /**
     * 执行oauth回调相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回oauth Callback结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/callback", method = MethodType.GET)
    public Map<String, Object> oauthCallback(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
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

    /**
     * 执行completeOAuth 认证相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回complete OAuth 认证结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/callback", method = MethodType.POST)
    public Map<String, Object> completeOAuth(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.completeOAuth(
                                serverId, DashboardRequestBodies.jsonObjectMap(context));
                    }
                });
    }

    /**
     * 刷新OAuth 认证。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回OAuth 认证结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/refresh", method = MethodType.POST)
    public Map<String, Object> refreshOAuth(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.refreshOAuth(serverId);
                    }
                });
    }

    /**
     * 执行OAuth401相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回O Auth401结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/handle-401", method = MethodType.POST)
    public Map<String, Object> handleOAuth401(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.handleOAuth401(serverId);
                    }
                });
    }

    /**
     * 清理OAuth 认证。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回OAuth 认证结果。
     */
    @Mapping(value = "/api/mcp/{serverId}/oauth/clear", method = MethodType.POST)
    public Map<String, Object> clearOAuth(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.clearOAuth(serverId);
                    }
                });
    }

    /**
     * 执行delete，服务于控制台MCP主流程相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param context 当前请求或运行上下文。
     * @return 返回delete结果。
     */
    @Mapping(value = "/api/mcp/{serverId}", method = MethodType.DELETE)
    public Map<String, Object> delete(String serverId, Context context) throws Exception {
        return safeMcp(
                context,
                new McpAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return mcpService.delete(serverId);
                    }
                });
    }

    /**
     * 生成安全展示用的MCP。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe MCP结果。
     */
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

    /** 定义MCP Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface McpAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
