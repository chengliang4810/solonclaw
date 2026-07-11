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
    public Map<String, Object> list(Context context) throws Exception {
        return safeMcp(context, () -> mcpService.list(context.param("profile")));
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
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return mcpService.save(profile(context, body), body);
                    }
                });
    }

    /**
     * 执行reload全部相关逻辑。
     *
     * @return 返回reload全部结果。
     */
    @Mapping(value = "/api/mcp/reload", method = MethodType.POST)
    public Map<String, Object> reloadAll(Context context) throws Exception {
        return safeMcp(context, () -> mcpService.reloadAllView(context.param("profile")));
    }

    /**
     * 执行reload全部异步相关逻辑。
     *
     * @return 返回reload全部Async结果。
     */
    @Mapping(value = "/api/mcp/reload/async", method = MethodType.POST)
    public Map<String, Object> reloadAllAsync(Context context) throws Exception {
        return safeMcp(context, () -> mcpService.reloadAllAsyncView(context.param("profile")));
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
                        return mcpService.check(context.param("profile"), serverId);
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
                        return mcpService.connect(context.param("profile"), serverId);
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
                        return mcpService.reload(context.param("profile"), serverId);
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
                        return mcpService.refreshTools(context.param("profile"), serverId);
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
    public Map<String, Object> oauthStatus(String serverId, Context context) throws Exception {
        return safeMcp(context, () -> mcpService.oauthStatus(context.param("profile"), serverId));
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
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return mcpService.beginOAuth(profile(context, body), serverId, body);
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
                        return mcpService.completeOAuth(context.param("profile"), serverId, body);
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
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        return mcpService.completeOAuth(profile(context, body), serverId, body);
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
                        return mcpService.refreshOAuth(context.param("profile"), serverId);
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
                        return mcpService.handleOAuth401(context.param("profile"), serverId);
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
                        return mcpService.clearOAuth(context.param("profile"), serverId);
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
                        return mcpService.delete(context.param("profile"), serverId);
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
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "MCP_PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "MCP_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 400, "MCP_BAD_REQUEST", e);
        }
    }

    /** 写请求中请求体 profile 优先，未提供时使用查询参数。 */
    private String profile(Context context, Map<String, Object> body) {
        Object bodyProfile = body == null ? null : body.get("profile");
        if (bodyProfile != null && String.valueOf(bodyProfile).trim().length() > 0) {
            return String.valueOf(bodyProfile).trim();
        }
        return context.param("profile");
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
