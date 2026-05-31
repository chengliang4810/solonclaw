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

/** Shared local console runtime that reuses the normal command and conversation chain. */
public class CliRuntime {
    private final CommandService commandService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final AgentRunControlService agentRunControlService;

    public CliRuntime(CommandService commandService, ConversationOrchestrator conversationOrchestrator) {
        this(commandService, conversationOrchestrator, null);
    }

    public CliRuntime(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            AgentRunControlService agentRunControlService) {
        this.commandService = commandService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.agentRunControlService = agentRunControlService;
    }

    public GatewayReply send(String sessionId, String input, ConversationEventSink eventSink)
            throws Exception {
        return send(sessionId, input, null, eventSink);
    }

    public GatewayReply send(
            String sessionId,
            String input,
            List<MessageAttachment> attachments,
            ConversationEventSink eventSink)
            throws Exception {
        return send(sessionId, input, attachments, eventSink, null);
    }

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

    public AgentRunStopResult stop(String sessionId) {
        if (agentRunControlService == null) {
            return AgentRunStopResult.none();
        }
        return agentRunControlService.stop(sourceKey(sessionId));
    }

    public String sourceKey(String sessionId) {
        return "MEMORY:cli:" + StrUtil.blankToDefault(sessionId, "cli");
    }

    private boolean isSupportedSlashCommand(String text) {
        if (!text.startsWith(GatewayCommandConstants.COMMAND_PREFIX)) {
            return false;
        }
        String withoutSlash = text.substring(GatewayCommandConstants.COMMAND_PREFIX.length()).trim();
        if (StrUtil.isBlank(withoutSlash)) {
            return false;
        }
        String[] parts = withoutSlash.split("\\s+", 2);
        String commandName = parts[0].toLowerCase(Locale.ROOT);
        return commandService.supports(commandName);
    }

    private GatewayMessage message(String sessionId, String input) {
        return message(sessionId, input, null);
    }

    private GatewayMessage message(
            String sessionId, String input, List<MessageAttachment> attachments) {
        return message(sessionId, input, attachments, null);
    }

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
