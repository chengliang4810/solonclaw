package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.util.List;
import java.util.Locale;

/** 承载终端 UI 会话和命令执行逻辑。 */
public class TerminalUiRuntime {
    /** 终端 UI 默认来源键前缀。 */
    private static final String DEFAULT_SOURCE_KEY_PREFIX = "MEMORY:terminal-ui:";

    /** 注入命令服务，用于调用对应业务能力。 */
    private final CommandService commandService;

    /** 终端 UI 使用的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /** 会话仓储，用于在回复完成后定位学习目标会话。 */
    private final SessionRepository sessionRepository;

    /** 任务后学习服务，由 TUI 运行时统一触发。 */
    private final SkillLearningService skillLearningService;

    /** 来源键前缀，决定 send/stop 把会话绑定到哪个终端 UI 命名空间。 */
    private final String sourceKeyPrefix;

    /**
     * 创建带自定义来源键前缀的终端 UI 运行时。
     *
     * <p>终端 UI 通过 /ws/tui 发起 prompt.submit 时，必须与自身的会话管理（MEMORY:terminal-ui:*） 使用同一来源键前缀，否则后端会按 cli
     * 前缀查不到会话而新建，导致回复事件的 session_id 与 前端当前会话不匹配，被前端按 session_id 过滤丢弃，表现为"一直运行中不回复"。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param sourceKeyPrefix 来源键前缀。
     */
    public TerminalUiRuntime(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            AgentRunControlService agentRunControlService,
            String sourceKeyPrefix) {
        this(
                commandService,
                conversationOrchestrator,
                agentRunControlService,
                sourceKeyPrefix,
                null,
                null);
    }

    /**
     * 创建可在回复后触发技能学习的终端 UI 运行时。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator 对话编排器。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param sourceKeyPrefix 来源键前缀。
     * @param sessionRepository 会话仓储依赖。
     * @param skillLearningService 任务后学习服务。
     */
    public TerminalUiRuntime(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            AgentRunControlService agentRunControlService,
            String sourceKeyPrefix,
            SessionRepository sessionRepository,
            SkillLearningService skillLearningService) {
        this.commandService = commandService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.agentRunControlService = agentRunControlService;
        this.sessionRepository = sessionRepository;
        this.skillLearningService = skillLearningService;
        this.sourceKeyPrefix =
                StrUtil.isBlank(sourceKeyPrefix) ? DEFAULT_SOURCE_KEY_PREFIX : sourceKeyPrefix;
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     * @param eventSink 事件Sink参数。
     * @return 返回send结果。
     */
    public GatewayReply send(String sessionId, String input, ConversationEventSink eventSink)
            throws Exception {
        return send(sessionId, input, null, eventSink);
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     * @param attachments attachments 参数。
     * @param eventSink 事件Sink参数。
     * @return 返回send结果。
     */
    public GatewayReply send(
            String sessionId,
            String input,
            List<MessageAttachment> attachments,
            ConversationEventSink eventSink)
            throws Exception {
        return send(sessionId, input, attachments, eventSink, null);
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     * @param attachments attachments 参数。
     * @param eventSink 事件Sink参数。
     * @param workspaceDir 文件或目录路径参数。
     * @return 返回send结果。
     */
    public GatewayReply send(
            String sessionId,
            String input,
            List<MessageAttachment> attachments,
            ConversationEventSink eventSink,
            String workspaceDir)
            throws Exception {
        String sanitized = TerminalUiInputSanitizer.stripLeakedTerminalResponses(input);
        ConversationEventSink sink = eventSink == null ? ConversationEventSink.noop() : eventSink;
        GatewayMessage message = message(sessionId, sanitized, attachments, workspaceDir);
        String text = StrUtil.nullToEmpty(sanitized).trim();
        if (isSupportedSlashCommand(text)) {
            GatewayReply reply = commandService.handle(message, text, sink);
            if (reply != null) {
                reply.setCommandHandled(true);
            }
            return reply;
        }
        if (text.startsWith(GatewayCommandConstants.COMMAND_PREFIX)) {
            return GatewayReply.error(
                    "unknown command: " + text.split("\\s+", 2)[0] + " — try /help");
        }
        GatewayReply reply = conversationOrchestrator.handleIncoming(message, sink);
        scheduleLearning(message, reply);
        return reply;
    }

    /** 在成功的普通对话回复后调度学习；学习调度失败不影响用户已得到的回复。 */
    private void scheduleLearning(GatewayMessage message, GatewayReply reply) {
        if (sessionRepository == null
                || skillLearningService == null
                || reply == null
                || reply.isError()
                || reply.getSessionId() == null) {
            return;
        }
        try {
            SessionRecord session = sessionRepository.findById(reply.getSessionId());
            if (session != null) {
                skillLearningService.schedulePostReplyLearning(session, message, reply);
            }
        } catch (Exception ignored) {
            // 学习是回复后的旁路任务，不得让其故障覆盖已成功的用户回复。
        }
    }

    /**
     * 停止当前组件并释放运行状态。
     *
     * @param sessionId 当前会话标识。
     * @return 返回stop结果。
     */
    public AgentRunStopResult stop(String sessionId) {
        if (agentRunControlService == null) {
            return AgentRunStopResult.none();
        }
        return agentRunControlService.stop(sourceKey(sessionId));
    }

    /**
     * 执行来源键相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回来源键结果。
     */
    public String sourceKey(String sessionId) {
        return sourceKeyPrefix + StrUtil.blankToDefault(sessionId, defaultSourceKeySuffix());
    }

    /**
     * 读取来源键在会话标识为空时使用的兜底后缀，保持与构造前缀所属命名空间一致。
     *
     * @return 返回默认来源键后缀。
     */
    private String defaultSourceKeySuffix() {
        return DEFAULT_SOURCE_KEY_PREFIX.equals(sourceKeyPrefix) ? "cli" : "terminal-ui";
    }

    /**
     * 判断是否Supported Slash命令。
     *
     * @param text 待处理文本。
     * @return 如果Supported Slash命令满足条件则返回 true，否则返回 false。
     */
    private boolean isSupportedSlashCommand(String text) {
        if (!text.startsWith(GatewayCommandConstants.COMMAND_PREFIX)) {
            return false;
        }
        String withoutSlash =
                text.substring(GatewayCommandConstants.COMMAND_PREFIX.length()).trim();
        if (StrUtil.isBlank(withoutSlash)) {
            return false;
        }
        String[] parts = withoutSlash.split("\\s+", 2);
        String commandName = parts[0].toLowerCase(Locale.ROOT);
        return commandService.supports(commandName);
    }

    /**
     * 执行消息相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     * @param attachments attachments 参数。
     * @param workspaceDir 文件或目录路径参数。
     * @return 返回消息结果。
     */
    private GatewayMessage message(
            String sessionId,
            String input,
            List<MessageAttachment> attachments,
            String workspaceDir) {
        String sid = StrUtil.blankToDefault(sessionId, "cli");
        GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "cli", "local", input);
        message.setChatType(GatewayBehaviorConstants.CHAT_TYPE_DM);
        message.setChatName("TUI");
        message.setUserName("local");
        message.setSourceKeyOverride(sourceKey(sid));
        if (StrUtil.isNotBlank(workspaceDir)) {
            message.setWorkspaceDirOverride(workspaceDir.trim());
        }
        if (attachments != null && !attachments.isEmpty()) {
            message.setAttachments(new java.util.ArrayList<MessageAttachment>(attachments));
        }
        return message;
    }
}
