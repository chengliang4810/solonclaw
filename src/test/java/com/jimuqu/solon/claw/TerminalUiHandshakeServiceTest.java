package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tui.TerminalUiHandshakeService;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TerminalUiHandshakeServiceTest {
    @Test
    void shouldExposeTerminalUiWebSocketEndpointFromServerUrl() {
        TerminalUiHandshakeService service = new TerminalUiHandshakeService(() -> 9443);

        Map<String, Object> handshake = service.handshake("https://agent.example.com/base");

        assertThat(handshake)
                .containsEntry("app", "solon-claw")
                .containsEntry("mode", "server")
                .containsEntry("protocol_version", Integer.valueOf(1));
        assertThat(handshake.get("ws_url")).isEqualTo("wss://agent.example.com:9443/base/ws/tui");
        assertThat(handshake.get("features").toString())
                .contains("chat", "slash_commands", "sessions", "approvals");
    }

    @Test
    void shouldUseSameHttpPortByDefaultBecauseSolonWebSocketSharesServerPort() {
        TerminalUiHandshakeService service = new TerminalUiHandshakeService();

        Map<String, Object> handshake = service.handshake("http://127.0.0.1:18081");

        assertThat(handshake.get("ws_url")).isEqualTo("ws://127.0.0.1:18081/ws/tui");
    }

    @Test
    void shouldAppendAuthorizedDashboardTokenToWebSocketUrl() {
        TerminalUiHandshakeService service = new TerminalUiHandshakeService(() -> 9443);

        Map<String, Object> handshake = service.handshake("https://agent.example.com/base", "abc+/=");

        assertThat(handshake.get("ws_url"))
                .isEqualTo("wss://agent.example.com:9443/base/ws/tui?token=abc%2B%2F%3D");
    }

    @Test
    void shouldNotExposeTokenForAnonymousHandshake() {
        TerminalUiHandshakeService service = new TerminalUiHandshakeService(() -> 9443);

        Map<String, Object> handshake = service.handshake("https://agent.example.com/base", "");

        assertThat(handshake.get("ws_url")).isEqualTo("wss://agent.example.com:9443/base/ws/tui");
    }
}
