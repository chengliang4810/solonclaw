package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.net.websocket.WebSocket;

/** 将后端危险命令审批事件转换为原终端 UI 的审批弹层事件。 */
public class TerminalUiApprovalObserver
        implements DangerousCommandApprovalService.ApprovalObserver {
    /** 终端 UI WebSocket 连接，用于向当前前端推送审批请求。 */
    private final WebSocket socket;
    /** 当前终端 UI 连接绑定的会话编号，用于过滤其他渠道或其他会话的审批事件。 */
    private volatile String sessionId;

    /** 创建绑定当前 WebSocket 的审批事件观察器。 */
    public TerminalUiApprovalObserver(WebSocket socket) {
        this.socket = socket;
    }

    /** 更新当前终端 UI 正在操作的会话编号。 */
    public void bindSession(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            this.sessionId = sessionId;
        }
    }

    /** 收到危险命令审批请求时，推送终端 UI 的 approval.request 事件。 */
    @Override
    public void onApprovalRequest(DangerousCommandApprovalService.ApprovalRequestEvent event) {
        if (event == null || !matchesSession(event.getSessionId())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("approval_id", event.getApprovalSelector());
        payload.put("command", event.getCommand());
        payload.put("description", StrUtil.blankToDefault(event.getDescription(), "dangerous command"));
        send("approval.request", payload, event.getSessionId());
    }

    /** 审批响应由 TUI RPC 主动回写，观察器无需额外处理。 */
    @Override
    public void onApprovalResponse(DangerousCommandApprovalService.ApprovalResponseEvent event) {}

    /** 判断事件是否属于当前 TUI 会话，避免跨会话弹出无关审批。 */
    private boolean matchesSession(String eventSessionId) {
        return StrUtil.isNotBlank(eventSessionId)
                && StrUtil.isNotBlank(sessionId)
                && StrUtil.equals(eventSessionId, sessionId);
    }

    /** 发送一条 JSON-RPC event 信封，保持和复制来的 TUI GatewayClient 协议一致。 */
    private void send(String type, Map<String, Object> payload, String eventSessionId) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("payload", payload);
        event.put("session_id", eventSessionId);

        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("jsonrpc", "2.0");
        frame.put("method", "event");
        frame.put("params", event);
        socket.send(org.noear.snack4.ONode.serialize(frame));
    }
}
