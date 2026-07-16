package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.Props;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.websocket.WebSocket;

public class DashboardAuthServiceTest {
    @TempDir java.nio.file.Path tempDir;

    @Test
    void shouldRejectBlankAccessTokenInsteadOfAdminFallback() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("");

        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.sessionToken()).isEmpty();
        assertThat(authService.isAuthorized(new HeaderContext("Bearer admin"))).isFalse();
    }

    @Test
    void shouldDetectExplicitAdminAsWeakDefaultToken() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("admin");

        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.sessionToken()).isEqualTo("admin");
        assertThat(authService.hasWeakDefaultToken()).isTrue();
    }

    @Test
    void shouldAcceptBearerSchemeCaseInsensitively() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("test-token");

        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.isAuthorized(new HeaderContext("bearer test-token"))).isTrue();
        assertThat(authService.isAuthorized(new HeaderContext("BEARER test-token"))).isTrue();
        assertThat(authService.isAuthorized(new HeaderContext("Bearer wrong-token"))).isFalse();
    }

    /** 验证 loopback WebSocket 也必须携带正确令牌，防止浏览器跨站连接绕过鉴权。 */
    @Test
    void shouldRequireTokenForLoopbackWebSocket() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("test-token");
        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.isAuthorized(webSocket(""))).isFalse();
        assertThat(authService.isAuthorized(webSocket("test-token"))).isTrue();
    }

    @Test
    void shouldAllowCorsOriginMatchingExplicitNonLoopbackBindHost() throws Exception {
        Props props = new Props();
        props.put(
                "solonclaw.workspace",
                Files.createDirectory(tempDir.resolve("runtime")).toString());
        props.put("server.host", "100.64.0.10");
        props.put("server.port", "9119");
        AppConfig config = AppConfig.load(props);

        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.isAllowedDashboardOrigin("http://100.64.0.10:9119")).isTrue();
        assertThat(authService.isAllowedDashboardOrigin("http://localhost:9119")).isTrue();
        assertThat(authService.isAllowedDashboardOrigin("http://evil.example.com:9119")).isFalse();
    }

    /** 创建只暴露查询 token 的测试 WebSocket，避免依赖真实网络握手。 */
    private WebSocket webSocket(String token) {
        MultiMap<String> params = new MultiMap<String>();
        if (token != null) {
            params.put("token", token);
        }
        return (WebSocket)
                Proxy.newProxyInstance(
                        WebSocket.class.getClassLoader(),
                        new Class<?>[] {WebSocket.class},
                        (proxy, method, args) -> {
                            if ("param".equals(method.getName())
                                    && args != null
                                    && args.length == 1) {
                                return params.get(String.valueOf(args[0]));
                            }
                            if ("paramMap".equals(method.getName())) {
                                return params;
                            }
                            if ("attrMap".equals(method.getName())) {
                                return Collections.emptyMap();
                            }
                            if ("remoteAddress".equals(method.getName())) {
                                return new InetSocketAddress("127.0.0.1", 0);
                            }
                            return null;
                        });
    }

    /** 测试用上下文，只暴露 Dashboard 鉴权需要读取的 Authorization 头。 */
    private static class HeaderContext extends ContextEmpty {
        /**
         * 创建带 Authorization 头的请求上下文。
         *
         * @param authorization Authorization 头内容。
         */
        private HeaderContext(String authorization) {
            headerMap().put("Authorization", authorization);
        }
    }
}
