package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;
import org.noear.solon.core.handle.UploadedFile;

/** Dashboard chat 运行接口。 */
@Controller
public class DashboardChatController {
    private final DashboardChatService chatService;

    public DashboardChatController(DashboardChatService chatService) {
        this.chatService = chatService;
    }

    @Mapping(value = "/api/chat/uploads", method = MethodType.POST, multipart = true)
    public Map<String, Object> uploads(Context context, UploadedFile[] file) {
        try {
            return chatService.uploads(file);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("CHAT_BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            context.status(500);
            return DashboardResponse.error("CHAT_FAILED", e.getMessage());
        }
    }

    @Mapping(value = "/api/chat/runs", method = MethodType.POST)
    public Map<String, Object> startRun(Context context) {
        try {
            return chatService.startRun(body(context));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("CHAT_BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            context.status(500);
            return DashboardResponse.error("CHAT_FAILED", e.getMessage());
        }
    }

    private ONode body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new ONode();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return node;
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    @Mapping(value = "/api/chat/runs/{runId}/events", method = MethodType.GET)
    public void events(Context context, String runId) throws Exception {
        chatService.streamEvents(runId, context);
    }

    @Mapping(value = "/api/chat/runs/{runId}/cancel", method = MethodType.POST)
    public Map<String, Object> cancel(Context context, String runId) {
        try {
            return chatService.cancelRun(runId);
        } catch (IllegalArgumentException e) {
            context.status(404);
            return DashboardResponse.error("CHAT_NOT_FOUND", e.getMessage());
        }
    }
}
