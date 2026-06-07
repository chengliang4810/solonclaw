package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tui.TerminalUiController;
import com.jimuqu.solon.claw.tui.TerminalUiHandshakeService;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.ContextEmpty;

public class TerminalUiControllerTest {
    @Test
    void shouldAllowLocalHandshakeWithoutDashboardAuthorization() {
        AppConfig config = new AppConfig();
        TerminalUiController controller = controller(config);
        FakeContext context = new FakeContext("127.0.0.1");
        context.requestHeader("Host", "127.0.0.1:8080");

        Map<String, Object> handshake = controller.handshake(context);

        assertThat(context.status()).isEqualTo(200);
        assertThat(handshake.get("ws_url").toString()).contains("/ws/tui");
    }

    @Test
    void shouldRejectRemoteHandshakeWithoutDashboardAuthorization() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("tui-secret");
        TerminalUiController controller = controller(config);
        FakeContext context = new FakeContext("203.0.113.10");
        context.requestHeader("Host", "agent.example.com");

        Map<String, Object> handshake = controller.handshake(context);

        assertThat(context.status()).isEqualTo(401);
        assertThat(handshake).isEmpty();
    }

    @Test
    void shouldAppendDashboardTokenForAuthorizedRemoteHandshake() {
        AppConfig config = new AppConfig();
        config.getDashboard().setAccessToken("tui-secret");
        TerminalUiController controller = controller(config);
        FakeContext context = new FakeContext("203.0.113.10");
        context.requestHeader("Host", "agent.example.com");
        context.requestHeader("Authorization", "Bearer tui-secret");

        Map<String, Object> handshake = controller.handshake(context);

        assertThat(context.status()).isEqualTo(200);
        assertThat(handshake.get("ws_url"))
                .isEqualTo("ws://agent.example.com/ws/tui?token=tui-secret");
    }

    private TerminalUiController controller(AppConfig config) {
        return new TerminalUiController(
                new TerminalUiHandshakeService(), new DashboardAuthService(config));
    }

    private static class FakeContext extends ContextEmpty {
        private final String remoteIp;

        private FakeContext(String remoteIp) {
            this.remoteIp = remoteIp;
        }

        private void requestHeader(String name, String value) {
            headerMap().put(name, value);
        }

        @Override
        public String remoteIp() {
            return remoteIp;
        }
    }
}
