package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.util.Map;

/** 处理运行中输入策略、排队和 steer 命令，隔离运行控制类命令的格式化细节。 */
final class DefaultBusyCommandHandler {
    /** 应用配置，用于读取和回写默认 busy 策略。 */
    private final AppConfig appConfig;

    /** 运行时设置服务，存在时用于持久化 busy 策略。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 会话仓储，用于查找或创建当前来源键绑定会话。 */
    private final SessionRepository sessionRepository;

    /** 运行仓储，用于统计排队消息数量。 */
    private final AgentRunRepository agentRunRepository;

    /** 运行控制服务，用于队列、steer 和运行状态查询。 */
    private final AgentRunControlService agentRunControlService;

    /** 对话编排器，用于 steer 需要立即运行时继续处理消息。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /**
     * 创建运行中输入命令处理器。
     *
     * @param appConfig 应用配置。
     * @param runtimeSettingsService 运行时设置服务。
     * @param sessionRepository 会话仓储。
     * @param agentRunRepository 运行仓储。
     * @param agentRunControlService 运行控制服务。
     * @param conversationOrchestrator 对话编排器。
     */
    DefaultBusyCommandHandler(
            AppConfig appConfig,
            RuntimeSettingsService runtimeSettingsService,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            AgentRunControlService agentRunControlService,
            ConversationOrchestrator conversationOrchestrator) {
        this.appConfig = appConfig;
        this.runtimeSettingsService = runtimeSettingsService;
        this.sessionRepository = sessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentRunControlService = agentRunControlService;
        this.conversationOrchestrator = conversationOrchestrator;
    }

    /**
     * 执行Busy相关逻辑。
     *
     * @param args 工具或命令参数。
     * @param sourceKey 渠道来源键。
     * @return 返回Busy结果。
     */
    GatewayReply handleBusy(String args, String sourceKey) {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if (StrUtil.isBlank(normalized) || "status".equals(normalized)) {
            return GatewayReply.ok(formatBusyStatus(sourceKey));
        }
        if ("queue".equals(normalized)
                || "steer".equals(normalized)
                || "interrupt".equals(normalized)
                || "reject".equals(normalized)) {
            persistBusyPolicy(normalized);
            return GatewayReply.ok(
                    "已切换运行中输入策略为 " + normalized + "。\n" + formatBusyPolicyDescription(normalized));
        }
        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_BUSY
                        + " [status|queue|steer|interrupt|reject]");
    }

    /**
     * 执行队列相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Queue结果。
     */
    GatewayReply handleQueue(GatewayMessage message, String args) throws Exception {
        if (StrUtil.isBlank(args)) {
            return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_QUEUE + " <prompt>");
        }
        SessionRecord session = requireSession(message.sourceKey());
        GatewayMessage queuedMessage = cloneUserMessage(message, args);
        RunBusyDecision decision =
                agentRunControlService.queueIncoming(
                        message.sourceKey(), session.getSessionId(), queuedMessage);
        GatewayReply reply =
                GatewayReply.ok(StrUtil.blankToDefault(decision.getMessage(), "已加入下一轮队列。"));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        reply.getRuntimeMetadata().put("busy_policy", decision.getPolicy());
        reply.getRuntimeMetadata().put("busy_status", decision.getStatus());
        if (StrUtil.isNotBlank(decision.getRunId())) {
            reply.getRuntimeMetadata().put("run_id", decision.getRunId());
        }
        if (StrUtil.isNotBlank(decision.getQueueId())) {
            reply.getRuntimeMetadata().put("queue_id", decision.getQueueId());
        }
        return reply;
    }

    /**
     * 执行Steer相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Steer结果。
     */
    GatewayReply handleSteer(GatewayMessage message, String args) throws Exception {
        if (StrUtil.isBlank(args)) {
            return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_STEER + " <prompt>");
        }
        SessionRecord session = requireSession(message.sourceKey());
        GatewayMessage steerMessage = cloneUserMessage(message, args);
        RunBusyDecision decision =
                agentRunControlService.steerIncoming(
                        message.sourceKey(), session.getSessionId(), steerMessage);
        if (decision.isShouldRunNow()) {
            return conversationOrchestrator.handleIncoming(steerMessage);
        }
        GatewayReply reply =
                GatewayReply.ok(
                        StrUtil.blankToDefault(decision.getMessage(), "已将 steer 指令注入当前任务。"));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        reply.getRuntimeMetadata().put("busy_policy", decision.getPolicy());
        reply.getRuntimeMetadata().put("busy_status", decision.getStatus());
        if (StrUtil.isNotBlank(decision.getRunId())) {
            reply.getRuntimeMetadata().put("run_id", decision.getRunId());
        }
        return reply;
    }

    /**
     * 克隆用户消息。
     *
     * @param source 来源参数。
     * @param text 待处理文本。
     * @return 返回clone用户消息结果。
     */
    private GatewayMessage cloneUserMessage(GatewayMessage source, String text) {
        GatewayMessage copy =
                new GatewayMessage(
                        source.getPlatform(), source.getChatId(), source.getUserId(), text);
        copy.setThreadId(source.getThreadId());
        copy.setReplyToMessageId(source.getReplyToMessageId());
        copy.setChatType(source.getChatType());
        copy.setChatName(source.getChatName());
        copy.setUserName(source.getUserName());
        copy.setTimestamp(source.getTimestamp());
        copy.setHeartbeat(source.isHeartbeat());
        copy.setSourceKeyOverride(source.sourceKey());
        return copy;
    }

    /**
     * 执行persistBusy策略相关逻辑。
     *
     * @param policy 策略参数。
     */
    private void persistBusyPolicy(String policy) {
        if (runtimeSettingsService != null) {
            runtimeSettingsService.setConfigValue("task.busyPolicy", policy);
            return;
        }
        appConfig.getTask().setBusyPolicy(policy);
    }

    /**
     * 格式化Busy状态。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回Busy状态。
     */
    private String formatBusyStatus(String sourceKey) {
        String policy = StrUtil.blankToDefault(appConfig.getTask().getBusyPolicy(), "queue");
        SessionRecord session = findBoundSessionQuietly(sourceKey);
        Map<String, Object> activeRun =
                agentRunControlService == null
                        ? null
                        : agentRunControlService.activeRunSummary(sourceKey);
        StringBuilder buffer = new StringBuilder();
        buffer.append("busy_policy=").append(policy).append('\n');
        buffer.append("source_running=")
                .append(
                        agentRunControlService != null
                                && agentRunControlService.isRunning(sourceKey))
                .append('\n');
        buffer.append("any_running=")
                .append(agentRunControlService != null && agentRunControlService.hasRunningRuns())
                .append('\n');
        buffer.append("active_run_id=")
                .append(activeRun == null ? "-" : valueOrDash(activeRun.get("run_id")))
                .append('\n');
        if (activeRun != null) {
            buffer.append("active_run_phase=")
                    .append(valueOrDash(activeRun.get("phase")))
                    .append('\n');
            buffer.append("active_run_idle_seconds=")
                    .append(valueOrDash(activeRun.get("seconds_since_activity")))
                    .append('\n');
        }
        buffer.append("queue_pending=")
                .append(countQueuedMessagesQuietly(sourceKey, session))
                .append('\n');
        buffer.append("current_policy=").append(formatBusyPolicyDescription(policy)).append('\n');
        buffer.append("policy_options:\n").append(formatBusyPolicyOptions()).append('\n');
        buffer.append("用法：")
                .append(GatewayCommandConstants.SLASH_BUSY)
                .append(" [status|queue|steer|interrupt|reject]");
        return buffer.toString();
    }

    /**
     * 查找绑定会话Quietly。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回绑定会话Quietly结果。
     */
    private SessionRecord findBoundSessionQuietly(String sourceKey) {
        try {
            return sessionRepository.getBoundSession(sourceKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行次数排队MessagesQuietly相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param session 会话参数。
     * @return 返回次数Queued Messages Quietly结果。
     */
    private int countQueuedMessagesQuietly(String sourceKey, SessionRecord session) {
        if (agentRunRepository == null
                || session == null
                || StrUtil.isBlank(session.getSessionId())) {
            return 0;
        }
        try {
            return agentRunRepository.countQueuedMessages(sourceKey, session.getSessionId());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 执行值OrDash相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回value Or Dash结果。
     */
    private String valueOrDash(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return StrUtil.blankToDefault(text, "-");
    }

    /**
     * 格式化Busy策略Description。
     *
     * @param policy 策略参数。
     * @return 返回Busy策略Description结果。
     */
    private String formatBusyPolicyDescription(String policy) {
        if ("steer".equals(policy)) {
            return "steer：运行中收到的新消息会作为 steer 指令注入当前 run。";
        }
        if ("interrupt".equals(policy)) {
            return "interrupt：运行中收到的新消息会打断当前 run，并立即启动新 run。";
        }
        if ("reject".equals(policy)) {
            return "reject：运行中收到的新消息会被拒绝，需等待或手动停止当前 run。";
        }
        return "queue：运行中收到的新消息会进入队列，当前 run 结束后自动执行。";
    }

    /**
     * 格式化Busy策略Options。
     *
     * @return 返回Busy策略Options结果。
     */
    private String formatBusyPolicyOptions() {
        return "queue：运行中收到的新消息会进入队列，当前 run 结束后自动执行。\n"
                + "steer：运行中收到的新消息会作为 steer 指令注入当前 run。\n"
                + "interrupt：运行中收到的新消息会打断当前 run，并立即启动新 run。\n"
                + "reject：运行中收到的新消息会被拒绝，需等待或手动停止当前 run。";
    }

    /** 获取当前来源键的会话；若不存在则立即创建。 */
    private SessionRecord requireSession(String sourceKey) throws Exception {
        return GatewayCommandSessionSupport.requireSession(sessionRepository, sourceKey);
    }
}
