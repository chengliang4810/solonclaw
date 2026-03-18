package com.jimuqu.claw.agent.runtime;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.claw.agent.channel.ChannelRegistry;
import com.jimuqu.claw.agent.model.AgentRun;
import com.jimuqu.claw.agent.model.ChannelType;
import com.jimuqu.claw.agent.model.ConversationType;
import com.jimuqu.claw.agent.model.InboundEnvelope;
import com.jimuqu.claw.agent.model.OutboundEnvelope;
import com.jimuqu.claw.agent.model.ReplyTarget;
import com.jimuqu.claw.agent.model.RunEvent;
import com.jimuqu.claw.agent.model.RunStatus;
import com.jimuqu.claw.agent.store.RuntimeStoreService;
import com.jimuqu.claw.config.SolonClawProperties;

import java.util.List;

/**
 * 协调消息入站、任务调度、状态落盘和出站发送的核心运行时服务。
 */
public class AgentRuntimeService {
    /** 会话执行 Agent。 */
    private final ConversationAgent conversationAgent;
    /** 运行时存储服务。 */
    private final RuntimeStoreService runtimeStoreService;
    /** 会话调度器。 */
    private final ConversationScheduler conversationScheduler;
    /** 渠道注册表。 */
    private final ChannelRegistry channelRegistry;
    /** 项目配置。 */
    private final SolonClawProperties properties;

    /**
     * 创建 Agent 运行时服务。
     *
     * @param conversationAgent 会话执行 Agent
     * @param runtimeStoreService 运行时存储服务
     * @param conversationScheduler 会话调度器
     * @param channelRegistry 渠道注册表
     * @param properties 项目配置
     */
    public AgentRuntimeService(
            ConversationAgent conversationAgent,
            RuntimeStoreService runtimeStoreService,
            ConversationScheduler conversationScheduler,
            ChannelRegistry channelRegistry,
            SolonClawProperties properties
    ) {
        this.conversationAgent = conversationAgent;
        this.runtimeStoreService = runtimeStoreService;
        this.conversationScheduler = conversationScheduler;
        this.channelRegistry = channelRegistry;
        this.properties = properties;
    }

    /**
     * 向调试页渠道提交一条消息。
     *
     * @param sessionId 调试会话标识
     * @param message 文本消息
     * @return 运行任务标识
     */
    public String submitDebugMessage(String sessionId, String message) {
        InboundEnvelope inboundEnvelope = new InboundEnvelope();
        inboundEnvelope.setMessageId("debug-" + IdUtil.fastSimpleUUID());
        inboundEnvelope.setChannelType(ChannelType.DEBUG_WEB);
        inboundEnvelope.setChannelInstanceId("debug-web");
        inboundEnvelope.setSenderId("debug-user");
        inboundEnvelope.setConversationId(sessionId);
        inboundEnvelope.setConversationType(ConversationType.PRIVATE);
        inboundEnvelope.setContent(message);
        inboundEnvelope.setReceivedAt(System.currentTimeMillis());
        inboundEnvelope.setSessionKey("debug-web:" + sessionId);
        inboundEnvelope.setReplyTarget(new ReplyTarget(ChannelType.DEBUG_WEB, ConversationType.PRIVATE, sessionId, "debug-user"));
        return submitInbound(inboundEnvelope);
    }

    /**
     * 向指定外部路由提交一条系统消息。
     *
     * @param sessionKey 会话键
     * @param replyTarget 回复目标
     * @param content 文本内容
     * @return 运行任务标识
     */
    public String submitSystemMessage(String sessionKey, ReplyTarget replyTarget, String content) {
        InboundEnvelope inboundEnvelope = new InboundEnvelope();
        inboundEnvelope.setMessageId("system-" + IdUtil.fastSimpleUUID());
        inboundEnvelope.setChannelType(ChannelType.SYSTEM);
        inboundEnvelope.setChannelInstanceId("system");
        inboundEnvelope.setSenderId("system");
        inboundEnvelope.setConversationId(replyTarget.getConversationId());
        inboundEnvelope.setConversationType(replyTarget.getConversationType());
        inboundEnvelope.setContent(content);
        inboundEnvelope.setReceivedAt(System.currentTimeMillis());
        inboundEnvelope.setSessionKey(sessionKey);
        inboundEnvelope.setReplyTarget(replyTarget);
        return submitInbound(inboundEnvelope);
    }

    /**
     * 提交一条标准化后的入站消息。
     *
     * @param inboundEnvelope 入站消息
     * @return 新建运行任务标识；若命中去重则返回 null
     */
    public String submitInbound(InboundEnvelope inboundEnvelope) {
        if (!runtimeStoreService.registerInbound(inboundEnvelope.getChannelType(), inboundEnvelope.getMessageId())) {
            return null;
        }

        ConversationScheduler.SessionState state = conversationScheduler.inspect(inboundEnvelope.getSessionKey());

        long version = runtimeStoreService.appendInboundConversationEvent(inboundEnvelope);
        inboundEnvelope.setSessionVersion(version);
        runtimeStoreService.rememberReplyTarget(inboundEnvelope.getSessionKey(), inboundEnvelope.getReplyTarget());

        AgentRun run = new AgentRun();
        run.setRunId(runtimeStoreService.newRunId());
        run.setSessionKey(inboundEnvelope.getSessionKey());
        run.setSourceMessageId(inboundEnvelope.getMessageId());
        run.setSourceUserVersion(version);
        run.setReplyTarget(inboundEnvelope.getReplyTarget());
        run.setStatus(RunStatus.QUEUED);
        run.setCreatedAt(System.currentTimeMillis());
        runtimeStoreService.saveRun(run);
        runtimeStoreService.appendRunEvent(run.getRunId(), "status", "queued");

        if (properties.getAgent().getScheduler().isAckWhenBusy()
                && state.activeCount() > 0
                && inboundEnvelope.getReplyTarget() != null
                && !inboundEnvelope.getReplyTarget().isDebugWeb()) {
            OutboundEnvelope ack = new OutboundEnvelope();
            ack.setRunId(run.getRunId());
            ack.setReplyTarget(inboundEnvelope.getReplyTarget());
            ack.setContent(state.queuedCount() > 0 ? "已收到，排队处理中。" : "已收到，正在并行处理中。");
            channelRegistry.send(ack);
        }

        conversationScheduler.submit(inboundEnvelope.getSessionKey(), () -> processRun(inboundEnvelope, run.getRunId()));
        return run.getRunId();
    }

    /**
     * 查询单个运行任务。
     *
     * @param runId 运行任务标识
     * @return 运行任务
     */
    public AgentRun getRun(String runId) {
        return runtimeStoreService.getRun(runId);
    }

    /**
     * 查询某个运行任务的增量事件。
     *
     * @param runId 运行任务标识
     * @param afterSeq 起始序号
     * @return 运行事件列表
     */
    public List<RunEvent> getRunEvents(String runId, long afterSeq) {
        return runtimeStoreService.getRunEvents(runId, afterSeq);
    }

    /**
     * 执行一次真正的运行任务处理。
     *
     * @param inboundEnvelope 入站消息
     * @param runId 运行任务标识
     */
    private void processRun(InboundEnvelope inboundEnvelope, String runId) {
        AgentRun run = runtimeStoreService.getRun(runId);
        if (run == null) {
            return;
        }

        try {
            run.setStatus(RunStatus.RUNNING);
            run.setStartedAt(System.currentTimeMillis());
            runtimeStoreService.saveRun(run);
            runtimeStoreService.appendRunEvent(runId, "status", "running");

            ConversationExecutionRequest request = new ConversationExecutionRequest();
            request.setSessionKey(inboundEnvelope.getSessionKey());
            request.setCurrentMessage(inboundEnvelope.getContent());
            request.setHistory(runtimeStoreService.loadConversationHistoryBefore(inboundEnvelope.getSessionKey(), inboundEnvelope.getSessionVersion()));

            final String[] latestProgress = {""};
            String response = conversationAgent.execute(request, progress -> {
                latestProgress[0] = progress;
                runtimeStoreService.appendRunEvent(runId, "progress", progress);
            });

            if (StrUtil.isBlank(response)) {
                response = latestProgress[0];
            }

            runtimeStoreService.appendAssistantConversationEvent(
                    inboundEnvelope.getSessionKey(),
                    runId,
                    inboundEnvelope.getMessageId(),
                    inboundEnvelope.getSessionVersion(),
                    response
            );

            run.setStatus(RunStatus.SUCCEEDED);
            run.setFinishedAt(System.currentTimeMillis());
            run.setFinalResponse(response);
            runtimeStoreService.saveRun(run);
            runtimeStoreService.appendRunEvent(runId, "reply", response);
            runtimeStoreService.appendRunEvent(runId, "status", "succeeded");

            if (inboundEnvelope.getReplyTarget() != null && !inboundEnvelope.getReplyTarget().isDebugWeb()) {
                OutboundEnvelope outboundEnvelope = new OutboundEnvelope();
                outboundEnvelope.setRunId(runId);
                outboundEnvelope.setReplyTarget(inboundEnvelope.getReplyTarget());
                outboundEnvelope.setContent(response);
                channelRegistry.send(outboundEnvelope);
            }
        } catch (Throwable throwable) {
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(System.currentTimeMillis());
            run.setErrorMessage(throwable.getMessage());
            runtimeStoreService.saveRun(run);
            runtimeStoreService.appendRunEvent(runId, "error", throwable.getMessage());
            runtimeStoreService.appendRunEvent(runId, "status", "failed");

            if (inboundEnvelope.getReplyTarget() != null && !inboundEnvelope.getReplyTarget().isDebugWeb()) {
                OutboundEnvelope outboundEnvelope = new OutboundEnvelope();
                outboundEnvelope.setRunId(runId);
                outboundEnvelope.setReplyTarget(inboundEnvelope.getReplyTarget());
                outboundEnvelope.setContent("抱歉，这次处理失败了：" + throwable.getMessage());
                channelRegistry.send(outboundEnvelope);
            }
        }
    }
}
