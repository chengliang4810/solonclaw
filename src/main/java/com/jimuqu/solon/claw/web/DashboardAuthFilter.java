package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import java.util.Collections;
import org.noear.snack4.ONode;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/** Dashboard API token 校验与 localhost CORS 过滤器。 */
public class DashboardAuthFilter implements Filter {
    /** 注入认证服务，用于调用对应业务能力。 */
    private final DashboardAuthService authService;

    /**
     * 创建控制台认证Filter实例，并注入运行所需依赖。
     *
     * @param authService 鉴权服务依赖。
     */
    public DashboardAuthFilter(DashboardAuthService authService) {
        this.authService = authService;
    }

    /**
     * 执行do过滤器相关逻辑。
     *
     * @param ctx ctx 参数。
     * @param chain chain 参数。
     */
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        authService.applyCors(ctx);

        if ("OPTIONS".equalsIgnoreCase(ctx.method()) && ctx.path().startsWith("/api/")) {
            ctx.status(204);
            return;
        }

        String path = ctx.path();
        if (path.startsWith("/api/") && isUnsafeBrowserWriteFromDisallowedOrigin(ctx)) {
            ctx.status(403);
            ctx.contentType("application/json;charset=UTF-8");
            ctx.output(
                    ONode.serialize(
                            Collections.singletonMap(
                                    "detail", "Forbidden dashboard request origin")));
            return;
        }
        boolean signedGatewayInjection =
                "/api/gateway/message".equals(path)
                        && "POST".equalsIgnoreCase(ctx.method())
                        && ctx.header("X-SolonClaw-Signature") != null;
        if (path.startsWith("/api/")
                && !signedGatewayInjection
                && !authService.isPublicApiPath(path, ctx.method())
                && !authService.isAuthorized(ctx)) {
            ctx.status(401);
            ctx.contentType("application/json;charset=UTF-8");
            ctx.output(ONode.serialize(Collections.singletonMap("detail", "Unauthorized")));
            return;
        }

        try {
            chain.doFilter(ctx);
        } catch (Throwable e) {
            if (isClientDisconnect(e)) {
                return;
            }
            throw e;
        }
    }

    /**
     * 判断请求输出时的异常是否来自浏览器刷新、关闭页面或请求超时后的客户端断连。
     *
     * @param error 下游处理链抛出的异常。
     * @return 如果是客户端断连则返回 true，避免将正常的连接关闭记录成服务端处理失败。
     */
    private boolean isClientDisconnect(Throwable error) {
        return DashboardClientDisconnects.isClientDisconnected(error);
    }

    /**
     * 判断浏览器跨站写请求是否来自未授权 Origin，CLI/API 客户端没有 Origin 时仍按 Bearer Token 校验。
     *
     * @param ctx 当前请求上下文。
     * @return 如果是带 Origin 的非安全跨站写请求则返回 true。
     */
    private boolean isUnsafeBrowserWriteFromDisallowedOrigin(Context ctx) {
        String method = ctx.method();
        if ("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        String origin = ctx.header("Origin");
        return StrUtil.isNotBlank(origin) && !authService.isAllowedDashboardOrigin(origin);
    }
}
