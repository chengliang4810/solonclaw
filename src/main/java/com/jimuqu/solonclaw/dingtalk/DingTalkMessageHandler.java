package com.jimuqu.solonclaw.dingtalk;

import com.alibaba.fastjson.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.jimuqu.solonclaw.agent.AgentService;
import com.jimuqu.solonclaw.memory.MemoryService;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉消息回调处理器
 *
 * @author SolonClaw
 */
@Component
public class DingTalkMessageHandler implements OpenDingTalkCallbackListener<ChatbotMessage, JSONObject> {

    private static final Logger log = LoggerFactory.getLogger(DingTalkMessageHandler.class);

    @Inject
    private AgentService agentService;

    @Inject
    private DingTalkMessageSender messageSender;

    @Inject
    private MemoryService memoryService;

    /**
     * 会话映射：钉钉用户ID -> sessionId
     */
    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();

    @Override
    public JSONObject execute(ChatbotMessage message) {
        try {
            MessageContent text = message.getText();
            if (text != null) {
                String userMessage = text.getContent();
                String userId = message.getSenderId();
                String conversationId = message.getConversationId();

                // 使用 conversationType 判断是群聊还是私聊
                // conversationType: "1" = 单聊, "2" = 群聊
                String conversationType = message.getConversationType();
                boolean isGroupChat = "2".equals(conversationType);

                // 私聊需要使用 senderStaffId（员工ID），而不是加密的 senderId
                String staffId = message.getSenderStaffId();

                log.info("收到钉钉消息 from userId={}, staffId={}, conversationId={}, conversationType={}, isGroup={}: {}",
                        userId, staffId, conversationId, conversationType, isGroupChat, userMessage);

                // 获取或创建会话
                String sessionId = getOrCreateSession(userId);

                // 调用 Agent 处理消息
                String response = agentService.chat(userMessage, sessionId);

                // 根据类型发送回复
                if (isGroupChat) {
                    messageSender.sendToGroup(conversationId, response);
                } else {
                    // 私聊使用 staffId
                    messageSender.sendToUser(staffId != null ? staffId : userId, response);
                }
            }
        } catch (Exception e) {
            log.error("处理钉钉消息异常", e);
        }

        return new JSONObject();
    }

    private String getOrCreateSession(String userId) {
        // 全局会话模式下，忽略用户差异，所有消息都使用同一个会话
        if (memoryService != null && memoryService.isGlobalSession()) {
            log.debug("全局会话模式：所有钉钉消息使用同一个会话");
            return memoryService.getGlobalSessionId();
        }
        return userSessionMap.computeIfAbsent(userId, k -> "dingtalk-" + System.currentTimeMillis());
    }
}
