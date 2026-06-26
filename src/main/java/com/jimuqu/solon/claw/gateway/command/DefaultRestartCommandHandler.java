package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartCoordinator;

/** 处理网关重启命令，隔离 drain 文案和 requester 元数据写入。 */
final class DefaultRestartCommandHandler {
    /** 会话仓储，用于给重启回复补充当前会话信息。 */
    private final SessionRepository sessionRepository;

    /** 运行控制服务，用于统计重启前仍在运行的任务。 */
    private final AgentRunControlService agentRunControlService;

    /** 网关重启协调器，用于登记重启请求和 drain 策略。 */
    private final GatewayRestartCoordinator gatewayRestartCoordinator;

    /**
     * 创建重启命令处理器。
     *
     * @param sessionRepository 会话仓储。
     * @param agentRunControlService 运行控制服务。
     * @param gatewayRestartCoordinator 网关重启协调器。
     */
    DefaultRestartCommandHandler(
            SessionRepository sessionRepository,
            AgentRunControlService agentRunControlService,
            GatewayRestartCoordinator gatewayRestartCoordinator) {
        this.sessionRepository = sessionRepository;
        this.agentRunControlService = agentRunControlService;
        this.gatewayRestartCoordinator = gatewayRestartCoordinator;
    }

    /**
     * 执行重启相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回重启结果。
     */
    GatewayReply handle(GatewayMessage message) throws Exception {
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        int activeRuns =
                agentRunControlService == null ? 0 : agentRunControlService.runningRunCount();
        GatewayRestartCoordinator.RestartRequest request =
                gatewayRestartCoordinator.requestRestartDrain(message, activeRuns);
        StringBuilder buffer = new StringBuilder();
        if (!request.isFirstRequest()) {
            buffer.append("网关重启已在进行中");
            if (activeRuns > 0) {
                buffer.append("，仍有 ").append(activeRuns).append(" 个任务等待 drain。");
            } else {
                buffer.append("。");
            }
        } else if (activeRuns > 0) {
            buffer.append("网关将重启，正在等待 ")
                    .append(activeRuns)
                    .append(" 个运行中任务完成；最长等待 ")
                    .append(request.getDrainTimeoutSeconds())
                    .append(" 秒。");
        } else {
            buffer.append("网关将立即重启。");
        }
        buffer.append("\n如果 60 秒内没有收到恢复通知，请在控制台检查 java -jar 或 Docker 进程。");

        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("restart_requested", Boolean.TRUE);
        reply.getRuntimeMetadata()
                .put("restart_first_request", Boolean.valueOf(request.isFirstRequest()));
        reply.getRuntimeMetadata().put("restart_active_runs", Integer.valueOf(activeRuns));
        reply.getRuntimeMetadata()
                .put(
                        "restart_drain_timeout_seconds",
                        Integer.valueOf(request.getDrainTimeoutSeconds()));
        putRestartRequesterMetadata(reply, request.getRequesterRouting());
        if (session != null) {
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
        }
        return reply;
    }

    /**
     * 写入重启Requester元数据。
     *
     * @param reply 回复参数。
     * @param routing routing 参数。
     */
    private void putRestartRequesterMetadata(
            GatewayReply reply, GatewayRestartCoordinator.RequesterRouting routing) {
        if (reply == null || routing == null || reply.getRuntimeMetadata() == null) {
            return;
        }
        if (routing.getPlatform() != null) {
            reply.getRuntimeMetadata()
                    .put("restart_requester_platform", routing.getPlatform().name());
        }
        if (StrUtil.isNotBlank(routing.getChatId())) {
            reply.getRuntimeMetadata().put("restart_requester_chat_id", routing.getChatId());
        }
        if (StrUtil.isNotBlank(routing.getUserId())) {
            reply.getRuntimeMetadata().put("restart_requester_user_id", routing.getUserId());
        }
        if (StrUtil.isNotBlank(routing.getChatType())) {
            reply.getRuntimeMetadata().put("restart_requester_chat_type", routing.getChatType());
        }
        if (StrUtil.isNotBlank(routing.getThreadId())) {
            reply.getRuntimeMetadata().put("restart_requester_thread_id", routing.getThreadId());
        }
    }
}
