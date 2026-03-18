package com.jimuqu.claw.web;

import cn.hutool.json.JSONUtil;
import com.jimuqu.claw.agent.model.AgentRun;
import com.jimuqu.claw.agent.model.RunEvent;
import com.jimuqu.claw.agent.runtime.AgentRuntimeService;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;

import java.util.List;

/**
 * 提供调试页使用的本地测试接口。
 */
@Controller
public class DebugChatController {
    /** Agent 运行时服务。 */
    private final AgentRuntimeService agentRuntimeService;

    /**
     * 创建调试控制器。
     *
     * @param agentRuntimeService Agent 运行时服务
     */
    public DebugChatController(AgentRuntimeService agentRuntimeService) {
        this.agentRuntimeService = agentRuntimeService;
    }

    /**
     * 提交一条调试聊天消息。
     *
     * @param ctx 当前请求上下文
     * @return 调试聊天响应
     * @throws Exception 读取请求体时的异常
     */
    @Mapping("/api/debug/chat")
    public DebugChatResponse chat(Context ctx) throws Exception {
        DebugChatRequest request = JSONUtil.toBean(ctx.bodyNew(), DebugChatRequest.class);
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }

        String runId = agentRuntimeService.submitDebugMessage(sessionId, request.getMessage());
        return new DebugChatResponse(runId, "debug-web:" + sessionId, "queued");
    }

    /**
     * 查询单个运行任务详情。
     *
     * @param runId 运行任务标识
     * @return 运行任务响应
     */
    @Mapping("/api/debug/runs/{runId}")
    public DebugRunResponse run(@Param String runId) {
        AgentRun run = agentRuntimeService.getRun(runId);
        return new DebugRunResponse(run);
    }

    /**
     * 查询某个运行任务的增量事件。
     *
     * @param runId 运行任务标识
     * @param after 上次已消费到的序号
     * @return 运行事件响应
     */
    @Mapping("/api/debug/runs/{runId}/events")
    public DebugRunEventsResponse events(@Param String runId, @Param(defaultValue = "0") long after) {
        List<RunEvent> events = agentRuntimeService.getRunEvents(runId, after);
        DebugRunEventsResponse response = new DebugRunEventsResponse();
        response.setEvents(events);
        response.setLastSeq(events.isEmpty() ? after : events.get(events.size() - 1).getSeq());
        return response;
    }
}
