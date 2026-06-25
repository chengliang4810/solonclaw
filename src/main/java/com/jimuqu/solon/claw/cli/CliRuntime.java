package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.util.List;
import java.util.Locale;

/** 承载CLI运行时相关状态和辅助逻辑。 */
public class CliRuntime {
    /** 注入命令服务，用于调用对应业务能力。 */
    private final CommandService commandService;

    /** 记录CLI运行时中的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /**
     * 创建Cli运行时实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     */
    public CliRuntime(
            CommandService commandService, ConversationOrchestrator conversationOrchestrator) {
        this(commandService, conversationOrchestrator, null);
    }

    /**
     * 创建Cli运行时实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param agentRunControlService Agent运行控制服务依赖。
     */
    public CliRuntime(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            AgentRunControlService agentRunControlService) {
        this.commandService = commandService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.agentRunControlService = agentRunControlService;
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
        String sanitized = TerminalInputSanitizer.stripLeakedTerminalResponses(input);
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
        return conversationOrchestrator.handleIncoming(message, sink);
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
        return "MEMORY:cli:" + StrUtil.blankToDefault(sessionId, "cli");
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
        message.setChatName("CLI");
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
