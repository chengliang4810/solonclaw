package com.jimuqu.claw.agent.runtime;

import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述一次会话执行所需的上下文输入。
 */
public class ConversationExecutionRequest {
    /** 当前会话对应的内部键。 */
    private String sessionKey;
    /** 当前待处理的用户消息。 */
    private String currentMessage;
    /** 历史消息列表。 */
    private List<ChatMessage> history = new ArrayList<>();

    /**
     * 返回会话键。
     *
     * @return 会话键
     */
    public String getSessionKey() {
        return sessionKey;
    }

    /**
     * 设置会话键。
     *
     * @param sessionKey 会话键
     */
    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    /**
     * 返回当前消息。
     *
     * @return 当前消息
     */
    public String getCurrentMessage() {
        return currentMessage;
    }

    /**
     * 设置当前消息。
     *
     * @param currentMessage 当前消息
     */
    public void setCurrentMessage(String currentMessage) {
        this.currentMessage = currentMessage;
    }

    /**
     * 返回历史消息列表。
     *
     * @return 历史消息列表
     */
    public List<ChatMessage> getHistory() {
        return history;
    }

    /**
     * 设置历史消息列表。
     *
     * @param history 历史消息列表
     */
    public void setHistory(List<ChatMessage> history) {
        this.history = history;
    }
}
