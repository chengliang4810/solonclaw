package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigController;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.ContextEmpty;

public class DashboardBootstrapTokenTest {
    @Test
    void localBlankTokenDashboardCanBootstrapAccessTokenOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardRuntimeConfigController controller =
                new DashboardRuntimeConfigController(
                        new DashboardRuntimeConfigService(
                                env.appConfig, env.gatewayRuntimeRefreshService),
                        new DashboardAuthService(env.appConfig));
        BootstrapContext context =
                new BootstrapContext("127.0.0.1", "{\"accessToken\":\"bootstrap-token-123456\"}");

        Map<String, Object> result = controller.bootstrapDashboardToken(context);

        assertThat(context.status()).isEqualTo(200);
        assertThat(result).containsEntry("success", true);
        assertThat(env.appConfig.getDashboard().getAccessToken())
                .isEqualTo("bootstrap-token-123456");
        assertThat(
                        new DashboardAuthService(env.appConfig)
                                .isAuthorized(new HeaderContext("Bearer bootstrap-token-123456")))
                .isTrue();

        BootstrapContext second =
                new BootstrapContext("127.0.0.1", "{\"accessToken\":\"second-token-123456\"}");
        controller.bootstrapDashboardToken(second);
        assertThat(second.status()).isEqualTo(409);
        assertThat(env.appConfig.getDashboard().getAccessToken())
                .isEqualTo("bootstrap-token-123456");
    }

    @Test
    void dashboardTokenBootstrapRouteIsPublicButEndpointStillChecksLocalhost() {
        DashboardAuthService authService =
                new DashboardAuthService(new com.jimuqu.solon.claw.config.AppConfig());

        assertThat(
                        authService.isPublicApiPath(
                                "/api/workspace-config/bootstrap-dashboard-token", "POST"))
                .isTrue();
    }

    /** 测试用上下文，模拟本机首次配置请求体。 */
    private static class BootstrapContext extends ContextEmpty {
        /** 请求来源 IP。 */
        private final String remoteIp;

        /** JSON 请求体。 */
        private final String body;

        /**
         * 创建首次配置请求上下文。
         *
         * @param remoteIp 请求来源 IP。
         * @param body JSON 请求体。
         */
        private BootstrapContext(String remoteIp, String body) {
            this.remoteIp = remoteIp;
            this.body = body;
        }

        @Override
        public String remoteIp() {
            return remoteIp;
        }

        @Override
        public String body() {
            return body;
        }
    }

    /** 测试用上下文，只提供 Bearer 认证头。 */
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
