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
                                "OutputStream has closed", new IOException("writeBuffer has closed"));
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
                                "OutputStream has closed", new IOException("writeBuffer has closed"));
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
                                                throw new IllegalStateException("static file missing");
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
    void shouldAllowUnsafeDashboardWriteFromLocalOrigin() throws Throwable {
        DashboardAuthFilter filter = filter();
        FakeContext context = new FakeContext("POST", "/api/private");
        context.requestHeader("Authorization", "Bearer test-token");
        context.requestHeader("Origin", "http://localhost:3000");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(
                context,
                new FilterChain() {
                    /** 同源或本机开发 Origin 通过 CSRF 预检后仍进入正常 Bearer Token 鉴权流程。 */
                    @Override
                    public void doFilter(Context ctx) {
                        invoked.set(true);
                    }
                });

        assertThat(context.status()).isEqualTo(200);
        assertThat(invoked).isTrue();
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
