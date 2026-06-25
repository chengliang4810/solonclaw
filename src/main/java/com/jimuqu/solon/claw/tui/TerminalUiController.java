package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.util.Collections;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 终端 UI 后端发现接口，负责把当前 Dashboard 访问身份转换为前端可连接的协议入口。 */
@Controller
public class TerminalUiController {
    /** 终端 UI 握手响应构造服务。 */
    private final TerminalUiHandshakeService handshakeService;

    /** 复用 Dashboard 访问控制服务，避免 TUI 握手暴露独立凭据规则。 */
    private final DashboardAuthService authService;

    /**
     * 创建终端 UI 后端发现接口。
     *
     * @param handshakeService 终端 UI 握手响应构造服务。
     * @param authService Dashboard 认证服务，复用同一套访问控制与 token 暴露策略。
     */
    public TerminalUiController(
            TerminalUiHandshakeService handshakeService, DashboardAuthService authService) {
        this.handshakeService = handshakeService;
        this.authService = authService;
    }

    /**
     * 返回终端 UI 协议版本与 WebSocket 地址。
     *
     * @param context 当前 HTTP 请求上下文，用于判断调用方是否允许读取会话 token。
     * @return 前端握手信息；未授权时写入 401 并返回空对象。
     */
    @Mapping(value = "/api/tui/handshake", method = MethodType.GET)
    public Map<String, Object> handshake(Context context) {
        boolean canRevealToken = authService == null || authService.canRevealToken(context);
        if (!canRevealToken) {
            authService.writeUnauthorized(context);
            return Collections.emptyMap();
        }
        String token = authService == null ? "" : authService.sessionToken();
        return handshakeService.handshake(baseUrl(context), token);
    }

    /**
     * 根据当前请求头生成后端对外可访问的基础地址。
     *
     * @param context 当前请求上下文。
     * @return 优先使用代理头的 http/https 基础地址。
     */
    private String baseUrl(Context context) {
        String forwardedProto = context.header("X-Forwarded-Proto");
        String scheme =
                StrUtil.isBlank(forwardedProto)
                        ? (context.isSecure() ? "https" : "http")
                        : forwardedProto.trim();
        String host = context.header("Host");
        if (StrUtil.isBlank(host)) {
            host = "127.0.0.1:8080";
        }
        return scheme + "://" + host;
    }
}
