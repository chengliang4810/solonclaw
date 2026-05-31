package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class DashboardAuthServiceTest {
    @TempDir java.nio.file.Path tempDir;

    @Test
    void shouldUseAdminWhenAccessTokenIsBlank() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("");

        DashboardAuthService authService = new DashboardAuthService(config);

        assertThat(authService.sessionToken()).isEqualTo("admin");
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
}
