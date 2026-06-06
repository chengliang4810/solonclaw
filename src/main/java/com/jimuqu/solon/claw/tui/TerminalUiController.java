package com.jimuqu.solon.claw.tui;

import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 终端 UI 后端发现接口。 */
@Controller
public class TerminalUiController {
    /** 终端 UI 握手响应构造服务。 */
    private final TerminalUiHandshakeService handshakeService;

    /** 注入终端 UI 握手服务。 */
    public TerminalUiController(TerminalUiHandshakeService handshakeService) {
        this.handshakeService = handshakeService;
    }

    /** 返回终端 UI 协议版本与 WebSocket 地址。 */
    @Mapping(value = "/api/tui/handshake", method = MethodType.GET)
    public Map<String, Object> handshake(Context context) {
        return handshakeService.handshake(baseUrl(context));
    }

    /** 根据当前请求头生成后端对外可访问的基础地址。 */
    private String baseUrl(Context context) {
        String forwardedProto = context.header("X-Forwarded-Proto");
        String scheme =
                forwardedProto == null || forwardedProto.trim().length() == 0
                        ? (context.isSecure() ? "https" : "http")
                        : forwardedProto.trim();
        String host = context.header("Host");
        if (host == null || host.trim().length() == 0) {
            host = "127.0.0.1:8080";
        }
        return scheme + "://" + host;
    }
}
