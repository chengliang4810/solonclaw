package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import reactor.core.publisher.Flux;

/** 校验项目自有 ReAct 循环能直接驱动文本 Action 与流式输出。 */
public class SolonAiOwnedReActLoopTest {
    @Test
    void shouldRunTextActionInputWhenNativeToolCallsAreMissing() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.TEXT_ACTION);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-text-action-session");

        FunctionToolDesc echo = new FunctionToolDesc("echo_tool");
        echo.description("Echo one value.");
        echo.doHandle(args -> "工具结果：" + args.get("value"));

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请调用工具",
                        Collections.singletonList(echo),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm());

        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("最终答复：工具结果：alpha");
        assertThat(model.calls).isEqualTo(2);
        List<ChatMessage> messages = MessageSupport.loadMessages(result.getNdjson());
        assertThat(messages)
                .anyMatch(
                        message ->
                                message instanceof AssistantMessage
                                        && ((AssistantMessage) message).getToolCalls() != null
                                        && !((AssistantMessage) message).getToolCalls().isEmpty()
                                        && "echo_tool"
                                                .equals(
                                                        ((AssistantMessage) message)
                                                                .getToolCalls()
                                                                .get(0)
                                                                .getName()));
        assertThat(messages).anyMatch(message -> message instanceof ToolMessage);
    }

    @Test
    void shouldStreamOwnedLoopDeltasWhenEventSinkIsProvided() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(2);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-stream-session");
        RecordingEventSink eventSink = new RecordingEventSink();

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请流式回复",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        eventSink,
                        false,
                        config.getLlm());

        assertThat(result.isStreamed()).isTrue();
        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("流式答复");
        assertThat(result.getReasoningText()).contains("流式思考");
        assertThat(eventSink.reasoningDeltas).contains("流式思考");
        assertThat(eventSink.assistantDeltas).contains("流式答复");
    }

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        config.getReact().setMaxSteps(4);
        config.getLlm().setProvider("openai");
        config.getLlm().setDialect("openai");
        config.getLlm().setApiUrl("https://example.com/v1/chat/completions");
        config.getLlm().setModel("owned-loop-model");
        config.getSecurity().setAllowPrivateUrls(true);
        return config;
    }

    private static SessionRecord session(String sessionId) {
        SessionRecord session = new SessionRecord();
        session.setSessionId(sessionId);
        session.setSourceKey("MEMORY:owned-loop:user");
        return session;
    }

    private static LlmResult invokeExecuteSingle(
            SolonAiLlmGateway gateway,
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> tools,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig llmConfig)
            throws Exception {
        Method executeSingle =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "executeSingle",
                        SessionRecord.class,
                        String.class,
                        String.class,
                        List.class,
                        ConversationFeedbackSink.class,
                        ConversationEventSink.class,
                        boolean.class,
                        AppConfig.LlmConfig.class,
                        com.jimuqu.solon.claw.core.model.AgentRunContext.class);
        executeSingle.setAccessible(true);
        return (LlmResult)
                executeSingle.invoke(
                        gateway,
                        session,
                        systemPrompt,
                        userMessage,
                        tools,
                        feedbackSink,
                        eventSink,
                        resume,
                        llmConfig,
                        null);
    }

    private static class RecordingEventSink implements ConversationEventSink {
        private final List<String> assistantDeltas = new ArrayList<String>();
        private final List<String> reasoningDeltas = new ArrayList<String>();

        @Override
        public void onAssistantDelta(String delta) {
            assistantDeltas.add(delta);
        }

        @Override
        public void onReasoningDelta(String delta) {
            reasoningDeltas.add(delta);
        }
    }

    private static class TestGateway extends SolonAiLlmGateway {
        private final ChatModel model;

        private TestGateway(
                AppConfig appConfig, SessionRepository sessionRepository, ChatModel model) {
            super(
                    appConfig,
                    sessionRepository,
                    null,
                    null,
                    null,
                    new ToolCallLoopGuardrailService(appConfig),
                    null);
            this.model = model;
        }

        @Override
        protected ChatModel buildChatModel(AppConfig.LlmConfig resolved) {
            return model;
        }
    }

    private enum FakeMode {
        TEXT_ACTION,
        STREAM_FINAL
    }

    private static class FakeChatModel extends ChatModel {
        private final FakeMode mode;
        private int calls;

        private FakeChatModel(String model, FakeMode mode) {
            super(fakeConfig(model));
            this.mode = mode;
        }

        @Override
        public ChatRequestDesc prompt(ChatSession session) {
            return new FakeRequestDesc(this, null, session);
        }

        @Override
        public ChatRequestDesc prompt(Prompt prompt) {
            return new FakeRequestDesc(this, prompt, null);
        }

        @Override
        public ChatRequestDesc prompt(List<ChatMessage> messages) {
            return prompt(Prompt.of(messages));
        }

        @Override
        public ChatRequestDesc prompt(ChatMessage... messages) {
            return prompt(Prompt.of(messages));
        }
    }

    private static class FakeRequestDesc implements ChatRequestDesc {
        private final FakeChatModel model;
        private final Prompt prompt;
        private ChatSession session;
        private ChatOptions options = ChatOptions.of();

        private FakeRequestDesc(FakeChatModel model, Prompt prompt, ChatSession initialSession) {
            this.model = model;
            this.prompt = prompt;
            this.session = initialSession;
        }

        @Override
        public ChatRequestDesc session(ChatSession session) {
            this.session = session;
            return this;
        }

        @Override
        public ChatRequestDesc options(ChatOptions options) {
            this.options = options;
            return this;
        }

        @Override
        public ChatRequestDesc options(java.util.function.Consumer<ChatOptions> optionsBuilder) {
            ChatOptions next = ChatOptions.of();
            optionsBuilder.accept(next);
            this.options = next;
            return this;
        }

        @Override
        public ChatResponse call() throws IOException {
            if (prompt != null && !prompt.isEmpty()) {
                session.addMessage(prompt);
            }
            model.calls++;
            ToolMessage toolMessage = lastToolMessage(session.getMessages());
            AssistantMessage assistant;
            if (toolMessage == null) {
                assistant =
                        ChatMessage.ofAssistant(
                                "Thought: 需要调用工具\n"
                                        + "Action: echo_tool\n"
                                        + "Action Input: {\"value\":\"alpha\"}");
            } else {
                assistant = ChatMessage.ofAssistant("最终答复：" + toolMessage.getContent());
            }
            session.addMessage(assistant);
            return new FakeResponse(model, options, assistant, false);
        }

        @Override
        public Flux<ChatResponse> stream() {
            try {
                if (model.mode != FakeMode.STREAM_FINAL) {
                    return Flux.just(call());
                }
                if (prompt != null && !prompt.isEmpty()) {
                    session.addMessage(prompt);
                }
                model.calls++;
                AssistantMessage thinking = new AssistantMessage("流式思考", true);
                AssistantMessage visible = ChatMessage.ofAssistant("流式答复");
                session.addMessage(visible);
                return Flux.just(
                        new FakeResponse(model, options, thinking, true),
                        new FakeResponse(model, options, visible, true));
            } catch (IOException e) {
                return Flux.error(e);
            }
        }

        private ToolMessage lastToolMessage(List<ChatMessage> messages) {
            if (messages == null) {
                return null;
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage message = messages.get(i);
                if (message instanceof ToolMessage) {
                    return (ToolMessage) message;
                }
            }
            return null;
        }
    }

    private static class FakeResponse implements ChatResponse {
        private final FakeChatModel model;
        private final ChatOptions options;
        private final AssistantMessage message;
        private final boolean stream;

        private FakeResponse(
                FakeChatModel model, ChatOptions options, AssistantMessage message, boolean stream) {
            this.model = model;
            this.options = options;
            this.message = message;
            this.stream = stream;
        }

        @Override
        public ChatConfigReadonly getConfig() {
            return model.getConfig();
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        @Override
        public String getResponseData() {
            return null;
        }

        @Override
        public String getModel() {
            return model.getModel();
        }

        @Override
        public ChatException getError() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean hasChoices() {
            return true;
        }

        @Override
        public ChatChoice lastChoice() {
            return new ChatChoice(0, new Date(), "stop", message);
        }

        @Override
        public List<ChatChoice> getChoices() {
            return Collections.singletonList(lastChoice());
        }

        @Override
        public AssistantMessage getMessage() {
            return message;
        }

        @Override
        public AssistantMessage getAggregationMessage() {
            return message;
        }

        @Override
        public String getAggregationContent() {
            return getContent();
        }

        @Override
        public boolean hasContent() {
            return message.hasContent();
        }

        @Override
        public String getContent() {
            return message.getContent();
        }

        @Override
        public String getResultContent() {
            return message.getResultContent();
        }

        @Override
        public org.noear.solon.ai.AiUsage getUsage() {
            return null;
        }

        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public boolean isStream() {
            return stream;
        }
    }

    private static class RecordingSessionRepository implements SessionRepository {
        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            return null;
        }

        @Override
        public SessionRecord bindNewSession(String sourceKey) {
            return session("session");
        }

        @Override
        public void bindSource(String sourceKey, String sessionId) {}

        @Override
        public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
            return null;
        }

        @Override
        public SessionRecord findById(String sessionId) {
            return null;
        }

        @Override
        public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
            return null;
        }

        @Override
        public List<SessionRecord> findResumeCandidates(String reference, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void save(SessionRecord session) {}

        @Override
        public List<SessionRecord> search(String keyword, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void delete(String sessionId) {}

        @Override
        public List<SessionRecord> listRecent(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int countAll() {
            return 0;
        }

        @Override
        public void setModelOverride(String sessionId, String modelOverride) {}

        @Override
        public void setServiceTierOverride(String sessionId, String serviceTierOverride) {}

        @Override
        public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {}

        @Override
        public void setActiveAgentName(String sessionId, String agentName) {}

        @Override
        public void clearActiveAgentName(String agentName) {}

        @Override
        public void setGoalState(String sessionId, String goalStateJson) {}

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {}
    }

    private static ChatConfig fakeConfig(String model) {
        ChatConfig config = new ChatConfig();
        config.setProvider("openai");
        config.setApiUrl("https://example.com/v1/chat/completions");
        config.setModel(model);
        return config;
    }
}
