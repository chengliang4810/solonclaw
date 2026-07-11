package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import org.noear.solon.annotation.Component;

/** 终端 UI 握手信息服务，用于暴露协议版本与 WebSocket 入口。 */
@Component
public class TerminalUiHandshakeService {
    /** 终端 UI WebSocket 协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 后端暴露给终端 UI 的 WebSocket 路径。 */
    public static final String WS_PATH = "/ws/tui";

    /** WebSocket 对外端口解析器，生产环境读取 Solon WebSocket 配置。 */
    private final IntSupplier websocketPortSupplier;

    /** 创建使用 Solon WebSocket 配置的握手服务。 */
    public TerminalUiHandshakeService() {
        this(
                new IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return 0;
                    }
                });
    }

    /** 创建指定 WebSocket 端口解析器的握手服务，便于测试不同部署端口。 */
    public TerminalUiHandshakeService(IntSupplier websocketPortSupplier) {
        this.websocketPortSupplier = websocketPortSupplier;
    }

    /** 根据当前服务地址生成终端 UI 握手响应。 */
    public Map<String, Object> handshake(String serverUrl) {
        return handshake(serverUrl, "");
    }

    /** 根据当前服务地址和可用访问令牌生成终端 UI 握手响应。 */
    public Map<String, Object> handshake(String serverUrl, String accessToken) {
        String base = normalizeBaseUrl(serverUrl);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("app", "solonclaw");
        result.put("mode", "server");
        result.put("protocol_version", Integer.valueOf(PROTOCOL_VERSION));
        result.put("ws_url", appendToken(toWebSocketUrl(base), accessToken));
        result.put("features", Arrays.asList("chat", "slash_commands", "sessions", "approvals"));
        return result;
    }

    /** 给 WebSocket 地址追加经过编码的访问令牌，匿名握手不暴露令牌。 */
    private String appendToken(String wsUrl, String accessToken) {
        if (StrUtil.isBlank(accessToken)) {
            return wsUrl;
        }
        try {
            String separator = wsUrl.contains("?") ? "&" : "?";
            return wsUrl
                    + separator
                    + "token="
                    + URLEncoder.encode(accessToken, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return wsUrl;
        }
    }

    /** 将 HTTP 基础地址转换为对应的 WebSocket 地址。 */
    private String toWebSocketUrl(String base) {
        String wsBase = replacePort(base, websocketPortSupplier.getAsInt());
        if (wsBase.startsWith("https://")) {
            return "wss://" + wsBase.substring("https://".length()) + WS_PATH;
        }
        if (wsBase.startsWith("http://")) {
            return "ws://" + wsBase.substring("http://".length()) + WS_PATH;
        }
        return wsBase + WS_PATH;
    }

    /** 将后端 HTTP 地址端口替换为 Solon WebSocket 对外端口。 */
    private String replacePort(String base, int wsPort) {
        if (wsPort <= 0) {
            return base;
        }
        try {
            URI uri = URI.create(base);
            if (StrUtil.isBlank(uri.getScheme()) || StrUtil.isBlank(uri.getHost())) {
                return base;
            }
            return new URI(
                            uri.getScheme(),
                            uri.getUserInfo(),
                            uri.getHost(),
                            wsPort,
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment())
                    .toString();
        } catch (Exception e) {
            return base;
        }
    }

    /** 将 HTTP 基础地址转换为同端口 WebSocket 地址，供测试和兜底路径使用。 */
    String toSamePortWebSocketUrl(String base) {
        String wsBase;
        if (base.startsWith("https://")) {
            wsBase = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            wsBase = "ws://" + base.substring("http://".length());
        } else {
            wsBase = base;
        }
        return wsBase + WS_PATH;
    }

    /** 规范化后端基础地址，避免路径拼接时出现多余斜杠。 */
    private String normalizeBaseUrl(String serverUrl) {
        String value = StrUtil.blankToDefault(serverUrl, "http://127.0.0.1:8080").trim();
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
