package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.web.DashboardAuthFilter;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.FilterChain;

public class DashboardAuthFilterTest {
    @Test
    void shouldShortCircuitNonApiOptionsPreflight() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("OPTIONS", "/assets/app.js");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 浏览器预检请求只需要 CORS 响应，不应继续落到静态资源或路由链。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(204);
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldIgnoreStaticResourceClientDisconnects() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("GET", "/assets/app.js");

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 模拟浏览器刷新时静态资源输出流已经关闭。 */
                    @Override
                    public void doFilter(Context ctx) {
                        throw new RuntimeException(
                                "OutputStream has closed",
                                new IOException("writeBuffer has closed"));
                    }
                });

        assertThat(context.status()).isEqualTo(200);
    }

    @Test
    void shouldIgnoreApiClientDisconnects() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("GET", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");

        filter.doFilter(
                context,
                new FilterChain() {
                    /** API 渲染响应时客户端超时断开，应避免冒泡为 Solon 顶层 WARN。 */
                    @Override
                    public void doFilter(Context ctx) {
                        throw new RuntimeException(
                                "OutputStream has closed",
                                new IOException("writeBuffer has closed"));
                    }
                });

        assertThat(context.status()).isEqualTo(200);
    }

    @Test
    void shouldPropagateStaticResourceBusinessErrors() {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("GET", "/assets/app.js");

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        context,
                                        new FilterChain() {
                                            /** 非断连异常仍然暴露，避免掩盖真实服务端错误。 */
                                            @Override
                                            public void doFilter(Context ctx) {
                                                throw new IllegalStateException(
                                                        "static file missing");
                                            }
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("static file missing");
    }

    @Test
    void shouldPropagateApiBusinessErrors() {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("GET", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        context,
                                        new FilterChain() {
                                            /** API 业务异常不能被断连兜底逻辑吞掉。 */
                                            @Override
                                            public void doFilter(Context ctx) {
                                                throw new IllegalStateException("search failed");
                                            }
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search failed");
    }

    @Test
    void shouldRejectUnsafeDashboardWriteFromDisallowedOrigin() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("POST", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");
        context.requestHeader("Origin", "https://evil.example.com");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 如果跨站写请求被正确拦截，下游处理链不应被调用。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(403);
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldReturnUnauthorizedBeforeOriginRejectionForMissingToken() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("POST", "/api/private");
        context.requestHeader("Origin", "https://evil.example.com");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 未登录请求应优先返回 401，让前端能够回到登录页。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(401);
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldRejectCrossOriginWriteEvenWhenOriginIsLocalhost() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("POST", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");
        context.requestHeader("Origin", "http://localhost:3000");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 不同端口的 localhost 仍是跨源请求，不得进入下游处理链。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(403);
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldAllowUnsafeDashboardWriteFromSameRequestOrigin() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("POST", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");
        context.requestHeader("Origin", "http://dashboard.example.com");
        context.requestHeader("Host", "dashboard.example.com");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 用户通过同一域名打开 Dashboard 时，写请求不应被 CSRF Origin 校验误拦截。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void shouldAllowUnsafeDashboardWriteFromSameHostBehindTlsProxy() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("POST", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");
        context.requestHeader("Origin", "https://dashboard.example.com");
        context.requestHeader("Host", "dashboard.example.com");
        context.requestHeader("X-Forwarded-Proto", "https");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 代理明确透传外部 HTTPS 协议后，严格同源请求应进入下游处理链。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    /** 验证公开的首次 token 配置接口也会拒绝跨站抢占写请求。 */
    @Test
    void shouldRejectCrossOriginDashboardTokenBootstrap() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context =
                new FakeContext("POST", "/api/workspace-config/bootstrap-dashboard-token");
        context.requestHeader("Origin", "https://evil.example.com");
        context.requestHeader("Host", "127.0.0.1:8080");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 跨站首次配置必须在进入公开控制器前被拦截。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(403);
        assertThat(invoked).isFalse();
    }

    /** 创建带固定测试令牌的过滤器实例。 */
    private DashboardAuthFilter filter() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("test-token");
        return new DashboardAuthFilter(new DashboardAuthService(config));
    }

    /** 测试用请求上下文，最小化提供过滤器依赖的请求信息。 */
    private static class FakeContext extends ContextEmpty {
        /** 请求方法。 */
        private final String method;

        /** 请求路径。 */
        private final String path;

        /**
         * 创建测试上下文。
         *
         * @param method 请求方法。
         * @param path 请求路径。
         */
        private FakeContext(String method, String path) {
            this.method = method;
            this.path = path;
        }

        /** 写入请求头。 */
        private void requestHeader(String name, String value) {
            headerMap().put(name, value);
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }
    }
}
