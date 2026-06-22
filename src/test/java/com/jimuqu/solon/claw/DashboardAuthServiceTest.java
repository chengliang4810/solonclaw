package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.Props;
import org.noear.solon.core.handle.ContextEmpty;

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
    void shouldAllowCorsOriginMatchingExplicitNonLoopbackBindHost() throws Exception {
        Props props = new Props();
        props.put(
                "solonclaw.runtime.home",
                Files.createDirectory(tempDir.resolve("runtime")).toString());
        props.put("server.host", "100.64.0.10");
        props.put("server.port", "9119");
        AppConfig config = AppConfig.load(props);

        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.isAllowedDashboardOrigin("http://100.64.0.10:9119")).isTrue();
        assertThat(authService.isAllowedDashboardOrigin("http://localhost:9119")).isTrue();
        assertThat(authService.isAllowedDashboardOrigin("http://evil.example.com:9119")).isFalse();
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
