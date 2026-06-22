package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import java.util.List;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

public class FakeLlmGateway implements LlmGateway {
    public String lastSystemPrompt;

    /** 记录最近一次 executeOnce 收到的临时记忆上下文。 */
    public String lastRunContextMemoryPrefetch;

    @Override
    public LlmResult chat(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects)
            throws Exception {
        lastSystemPrompt = systemPrompt;
        InMemoryChatSession chatSession = new InMemoryChatSession(session.getSessionId());
        if (StrUtil.isNotBlank(session.getNdjson())) {
            chatSession.addMessage(ChatMessage.fromNdjson(session.getNdjson()));
        }
        chatSession.addMessage(ChatMessage.ofUser(userMessage));
        chatSession.addMessage(ChatMessage.ofAssistant("echo:" + userMessage));

        LlmResult result = new LlmResult();
        result.setAssistantMessage(ChatMessage.ofAssistant("echo:" + userMessage));
        result.setNdjson(ChatMessage.toNdjson(chatSession.getMessages()));
        result.setRawResponse("fake");
        result.setStreamed(false);
        result.setProvider("openai-responses");
        result.setModel("gpt-5.4");
        result.setInputTokens(Math.max(1, userMessage == null ? 0 : userMessage.length()));
        result.setOutputTokens(Math.max(1, ("echo:" + userMessage).length()));
        result.setCacheReadTokens(2L);
        result.setCacheWriteTokens(1L);
        result.setTotalTokens(
                result.getInputTokens()
                        + result.getOutputTokens()
                        + result.getCacheReadTokens()
                        + result.getCacheWriteTokens());
        return result;
    }

    /**
     * 执行一次测试模型调用，并记录运行上下文中的临时记忆。
     *
     * @param session 当前会话记录。
     * @param systemPrompt 系统提示词。
     * @param userMessage 用户输入。
     * @param toolObjects 工具对象集合。
     * @param feedbackSink 反馈输出。
     * @param eventSink 事件输出。
     * @param resume 是否恢复挂起会话。
     * @param resolved 已解析模型配置。
     * @param runContext 当前运行上下文。
     * @return 返回测试模型结果。
     */
    @Override
    public LlmResult executeOnce(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> toolObjects,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig resolved,
            AgentRunContext runContext)
            throws Exception {
        lastRunContextMemoryPrefetch =
                runContext == null ? null : runContext.getMemoryPrefetchContext();
        return LlmGateway.super.executeOnce(
                session,
                systemPrompt,
                userMessage,
                toolObjects,
                feedbackSink,
                eventSink,
                resume,
                resolved,
                runContext);
    }

    @Override
    public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects)
            throws Exception {
        lastSystemPrompt = systemPrompt;
        InMemoryChatSession chatSession = new InMemoryChatSession(session.getSessionId());
        if (StrUtil.isNotBlank(session.getNdjson())) {
            chatSession.addMessage(ChatMessage.fromNdjson(session.getNdjson()));
        }
        chatSession.addMessage(ChatMessage.ofAssistant("echo:resume"));

        LlmResult result = new LlmResult();
        result.setAssistantMessage(ChatMessage.ofAssistant("echo:resume"));
        result.setNdjson(ChatMessage.toNdjson(chatSession.getMessages()));
        result.setRawResponse("fake-resume");
        result.setStreamed(false);
        result.setProvider("openai-responses");
        result.setModel("gpt-5.4");
        result.setInputTokens(1L);
        result.setOutputTokens("echo:resume".length());
        result.setCacheReadTokens(1L);
        result.setTotalTokens(
                result.getInputTokens() + result.getOutputTokens() + result.getCacheReadTokens());
        return result;
    }
}
