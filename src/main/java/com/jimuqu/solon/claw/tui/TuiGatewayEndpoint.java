package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import java.io.IOException;
import org.noear.solon.annotation.Component;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;

/** WebSocket endpoint for the browser Agent terminal. */
@Component
@ServerEndpoint("/api/jimuqu/tui")
public class TuiGatewayEndpoint extends SimpleWebSocketListener {
    private final TuiGatewayService gatewayService;
    private final DashboardAuthService authService;

    public TuiGatewayEndpoint(TuiGatewayService gatewayService, DashboardAuthService authService) {
        this.gatewayService = gatewayService;
        this.authService = authService;
    }

    @Override
    public void onOpen(WebSocket socket) {
        if (!authorized(socket)) {
            socket.close(1008, "Unauthorized");
            return;
        }
        TuiConnection connection = new TuiConnection(socket);
        socket.attr("tui.connection", connection);
        gatewayService.onOpen(connection);
    }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        TuiConnection connection = socket.attr("tui.connection");
        if (connection == null) {
            connection = new TuiConnection(socket);
            socket.attr("tui.connection", connection);
        }
        try {
            gatewayService.handle(connection, TuiEnvelope.parse(text));
        } catch (Exception e) {
            connection.sendError(null, connection.getActiveSessionId(), "BAD_MESSAGE", "请求解析失败 / Request parse failed");
        }
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        TuiConnection connection = socket == null ? null : socket.attr("tui.connection");
        if (connection != null) {
            connection.sendError(null, connection.getActiveSessionId(), "SOCKET_ERROR", "连接错误 / Socket error");
        }
    }

    private boolean authorized(WebSocket socket) {
        if (authService == null) {
            return false;
        }
        String expected = authService.sessionToken();
        String token = socket.param("token");
        String authorization = socket.param("authorization");
        if (StrUtil.isBlank(authorization)) {
            authorization = socket.param("Authorization");
        }
        return StrUtil.isNotBlank(expected)
                && (expected.equals(token) || ("Bearer " + expected).equals(authorization));
    }
}
