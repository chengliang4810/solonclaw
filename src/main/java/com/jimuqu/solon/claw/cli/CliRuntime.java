package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;

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
        GatewayMessage message = message(sessionId, input);
        String text = StrUtil.nullToEmpty(input).trim();
        if (text.startsWith(GatewayCommandConstants.COMMAND_PREFIX)) {
            GatewayReply reply = commandService.handle(message, text, eventSink);
            if (reply != null) {
                reply.setCommandHandled(true);
            }
            return reply;
        }
        return conversationOrchestrator.handleIncoming(message, eventSink);
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

    private GatewayMessage message(String sessionId, String input) {
        String sid = StrUtil.blankToDefault(sessionId, "cli");
        GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "cli", "local", input);
        message.setChatType(GatewayBehaviorConstants.CHAT_TYPE_DM);
        message.setChatName("CLI");
        message.setUserName("local");
        message.setSourceKeyOverride(sourceKey(sid));
        return message;
    }
}
