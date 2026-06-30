package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;
import org.noear.solon.core.handle.UploadedFile;

/** Dashboard chat 运行接口。 */
@Controller
public class DashboardChatController {
    /** 注入聊天服务，用于调用对应业务能力。 */
    private final DashboardChatService chatService;

    /**
     * 创建控制台Chat控制器实例，并注入运行所需依赖。
     *
     * @param chatService 聊天服务依赖。
     */
    public DashboardChatController(DashboardChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 执行uploads相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param file 文件或目录路径参数。
     * @return 返回uploads结果。
     */
    @Mapping(value = "/api/chat/uploads", method = MethodType.POST, multipart = true)
    public Map<String, Object> uploads(Context context, UploadedFile[] file) {
        try {
            return chatService.uploads(file);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "CHAT_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "CHAT_FAILED", e);
        }
    }

    /**
     * 启动运行。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回运行结果。
     */
    @Mapping(value = "/api/chat/runs", method = MethodType.POST)
    public Map<String, Object> startRun(Context context) {
        try {
            return chatService.startRun(DashboardRequestBodies.jsonObject(context));
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "CHAT_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "CHAT_FAILED", e);
        }
    }

    /**
     * 执行events相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param runId 运行标识。
     */
    @Mapping(value = "/api/chat/runs/{runId}/events", method = MethodType.GET)
    public void events(Context context, String runId) throws Exception {
        chatService.streamEvents(runId, context);
    }

    /**
     * 执行cancel相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param runId 运行标识。
     * @return 返回cancel结果。
     */
    @Mapping(value = "/api/chat/runs/{runId}/cancel", method = MethodType.POST)
    public Map<String, Object> cancel(Context context, String runId) {
        try {
            return chatService.cancelRun(runId);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 404, "CHAT_NOT_FOUND", e);
        }
    }
}
