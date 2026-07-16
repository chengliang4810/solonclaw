package com.jimuqu.solon.claw.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 验证终端 UI 握手服务在各协议与端口场景下生成正确的 WebSocket 入口信息。 */
@DisplayName("TerminalUiHandshakeService 握手服务")
class TerminalUiHandshakeServiceTest {

    private final TerminalUiHandshakeService service = new TerminalUiHandshakeService();

    @Test
    @DisplayName("handshake - HTTP 地址生成 ws:// WebSocket URL")
    void handshake_withHttpBaseUrl_shouldGenerateWsUrl() {
        Map<String, Object> result = service.handshake("http://localhost:8080");

        assertThat(result).containsEntry("app", "solonclaw");
        assertThat(result).containsEntry("mode", "server");
        assertThat(result).containsEntry("protocol_version", 1);
        assertThat(result.get("ws_url").toString()).startsWith("ws://");
        assertThat(result.get("ws_url").toString()).contains("/ws/tui");
    }

    @Test
    @DisplayName("handshake - HTTPS 地址生成 wss:// WebSocket URL")
    void handshake_withHttpsBaseUrl_shouldGenerateWssUrl() {
        Map<String, Object> result = service.handshake("https://example.com");

        assertThat(result.get("ws_url").toString()).startsWith("wss://");
        assertThat(result.get("ws_url").toString()).contains("/ws/tui");
    }

    @Test
    @DisplayName("handshake - 带 access token 时追加 token 参数")
    void handshake_withAccessToken_shouldAppendTokenParam() {
        Map<String, Object> result =
                service.handshake("http://127.0.0.1:8080", "token with space&scope=all");

        String wsUrl = result.get("ws_url").toString();
        assertThat(wsUrl).endsWith("token=token+with+space%26scope%3Dall");
    }

    @Test
    @DisplayName("handshake - 空 access token 时 URL 不包含 token 参数")
    void handshake_withEmptyToken_shouldNotAppendToken() {
        Map<String, Object> result = service.handshake("http://127.0.0.1:8080", "");

        String wsUrl = result.get("ws_url").toString();
        assertThat(wsUrl).doesNotContain("token=");
    }

    @Test
    @DisplayName("handshake - 地址末尾斜杠应被去除避免重复路径")
    void handshake_withTrailingSlash_shouldNormalizeUrl() {
        Map<String, Object> result = service.handshake("http://localhost:8080/");

        String wsUrl = result.get("ws_url").toString();
        assertThat(wsUrl).doesNotContain("//ws");
    }

    @Test
    @DisplayName("handshake - 默认地址缺失时使用 127.0.0.1:8080")
    void handshake_withBlankUrl_shouldUseDefault() {
        Map<String, Object> result = service.handshake("");

        String wsUrl = result.get("ws_url").toString();
        assertThat(wsUrl).contains("127.0.0.1");
    }

    @Test
    @DisplayName("handshake - 响应包含固定功能列表")
    void handshake_shouldReturnFeaturesList() {
        Map<String, Object> result = service.handshake("http://localhost:8080");

        assertThat(result.get("features")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> features = (List<String>) result.get("features");
        assertThat(features).contains("chat", "slash_commands", "sessions", "approvals");
    }

    @Test
    @DisplayName("toSamePortWebSocketUrl - HTTP 转 ws 同端口")
    void toSamePortWebSocketUrl_withHttp_shouldPreservePort() {
        String wsUrl = service.toSamePortWebSocketUrl("http://localhost:3000");

        assertThat(wsUrl).isEqualTo("ws://localhost:3000/ws/tui");
    }

    @Test
    @DisplayName("toSamePortWebSocketUrl - HTTPS 转 wss 同端口")
    void toSamePortWebSocketUrl_withHttps_shouldPreservePort() {
        String wsUrl = service.toSamePortWebSocketUrl("https://example.com");

        assertThat(wsUrl).isEqualTo("wss://example.com/ws/tui");
    }

    @Test
    @DisplayName("toSamePortWebSocketUrl - 非 http 前缀保持原样")
    void toSamePortWebSocketUrl_withUnknownScheme_shouldAppendPath() {
        String wsUrl = service.toSamePortWebSocketUrl("tcp://broker.local");

        assertThat(wsUrl).isEqualTo("tcp://broker.local/ws/tui");
    }

    @Test
    @DisplayName("带自定义端口供应商时应替换端口")
    void handshake_withCustomPortSupplier_shouldReplacePort() {
        TerminalUiHandshakeService customService = new TerminalUiHandshakeService(() -> 9090);

        Map<String, Object> result = customService.handshake("http://localhost:8080");

        String wsUrl = result.get("ws_url").toString();
        assertThat(wsUrl).contains("9090");
    }

    @Test
    @DisplayName("protocol_version 为固定值 1")
    void handshake_shouldReturnProtocolVersion1() {
        Map<String, Object> result = service.handshake("http://localhost:8080");

        assertThat(result.get("protocol_version")).isEqualTo(1);
    }
}
