package com.jimuqu.solon.claw.web;

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

        chain.doFilter(ctx);
    }
}
