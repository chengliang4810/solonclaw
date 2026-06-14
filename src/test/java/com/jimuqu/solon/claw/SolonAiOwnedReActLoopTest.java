package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
                        config.getLlm(),
                        null);

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
    void shouldPreserveOwnedLoopToolObservationBeyondUpstreamSanitizerDefault() throws Exception {
        AppConfig config = config();
        config.getTask().setToolOutputInlineLimit(12000);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.LONG_TOOL_OUTPUT);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-long-tool-output-session");
        final String tailMarker = "LONG_OBSERVATION_TAIL_MARKER";

        FunctionToolDesc readFile = new FunctionToolDesc("read_file");
        readFile.description("Read file content.");
        readFile.doHandle(args -> repeat("x", 2600) + tailMarker);

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请读取较长文件",
                        Collections.singletonList(readFile),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        null);

        assertThat(model.requestContents).hasSize(2);
        assertThat(model.requestContents.get(1))
                .anyMatch(
                        content ->
                                content.contains(tailMarker)
                                        && !content.contains("Content Truncated due to length"));
        assertThat(result.getAssistantMessage().getResultContent()).contains(tailMarker);
        List<ChatMessage> messages = MessageSupport.loadMessages(result.getNdjson());
        ToolMessage toolMessage = lastToolMessage(messages);
        assertThat(toolMessage).isNotNull();
        assertThat(toolMessage.getContent())
                .contains(tailMarker)
                .doesNotContain("Content Truncated due to length");
    }

    /** 验证原生工具调用未主动写入会话时，最大步数恢复仍保留真实工具转录。 */
    @Test
    void shouldKeepNativeToolTranscriptWhenMaxStepsStopsLoop() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(1);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.NATIVE_TOOL_WITHOUT_SESSION_APPEND);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-native-tool-max-steps-session");

        FunctionToolDesc echo = new FunctionToolDesc("echo_tool");
        echo.description("Echo one value.");
        echo.doHandle(args -> "工具结果：" + args.get("value"));

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请调用工具后停止",
                        Collections.singletonList(echo),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        null);

        List<ChatMessage> messages = MessageSupport.loadMessages(result.getNdjson());
        assertThat(result.getAssistantMessage().getContent()).contains("Maximum steps reached");
        assertThat(messages)
                .anyMatch(
                        message ->
                                message instanceof AssistantMessage
                                        && ((AssistantMessage) message).getToolCalls() != null
                                        && !((AssistantMessage) message).getToolCalls().isEmpty());
        assertThat(messages)
                .anyMatch(
                        message ->
                                message instanceof ToolMessage
                                        && message.getContent().contains("工具结果：native"));
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
                        config.getLlm(),
                        null);

        assertThat(result.isStreamed()).isTrue();
        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("流式答复");
        assertThat(result.getReasoningText()).contains("流式思考");
        assertThat(eventSink.reasoningDeltas).contains("流式思考");
        assertThat(eventSink.assistantDeltas).contains("流式答复");
    }

    @Test
    void shouldSuppressVisibleToolPreambleUntilFinalAnswer() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(3);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_VISIBLE_TOOL_PREAMBLE);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-tool-preamble-session");
        RecordingEventSink eventSink = new RecordingEventSink();

        FunctionToolDesc readFile = new FunctionToolDesc("read_file");
        readFile.description("Read file content.");
        readFile.doHandle(args -> "authoritative content");

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请分页读取文件并输出 JSON",
                        Collections.singletonList(readFile),
                        ConversationFeedbackSink.noop(),
                        eventSink,
                        false,
                        config.getLlm(),
                        null);

        assertThat(result.isStreamed()).isTrue();
        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("{\"pass\":true}");
        assertThat(eventSink.assistantDeltas).containsExactly("{\"pass\":true}");
        assertThat(String.join("", eventSink.assistantDeltas)).doesNotContain("Need second read");
        List<ChatMessage> messages = MessageSupport.loadMessages(result.getNdjson());
        assertThat(
                        messages.stream()
                                .filter(message -> message instanceof AssistantMessage)
                                .map(ChatMessage::getContent)
                                .filter("Need second read."::equals)
                                .count())
                .isEqualTo(1);
        assertThat(messages)
                .anyMatch(
                        message ->
                                message instanceof AssistantMessage
                                        && "Need second read.".equals(message.getContent())
                                        && ((AssistantMessage) message).getToolCalls() != null
                                        && !((AssistantMessage) message).getToolCalls().isEmpty());
    }

    /** 校验自有循环请求会把已有会话历史和本轮用户输入一起发送给模型。 */
    @Test
    void shouldReplayStoredSessionHistoryInOwnedLoopRequest() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.HISTORY_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-history-session");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("上一轮用户说喜欢中文"),
                                ChatMessage.ofAssistant("上一轮助手已确认"))));

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "这轮要记得上次内容",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        null);

        assertThat(model.requestContents).hasSize(1);
        assertThat(model.requestContents.get(0))
                .contains("上一轮用户说喜欢中文", "上一轮助手已确认", "这轮要记得上次内容");
        assertThat(messageContents(MessageSupport.loadMessages(result.getNdjson())))
                .contains("上一轮用户说喜欢中文", "上一轮助手已确认", "这轮要记得上次内容", "历史答复");
    }

    /** 校验召回记忆只进入模型请求，不进入最终持久化的会话 NDJSON。 */
    @Test
    void shouldInjectPrefetchedMemoryIntoRequestWithoutPersistingIt() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.TEXT_ACTION);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-memory-session");
        AgentRunContext runContext =
                new AgentRunContext(null, "run-memory", session.getSessionId(), session.getSourceKey());
        runContext.setMemoryPrefetchContext("请调用工具", "召回记忆：称呼用户为亮哥");

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
                        config.getLlm(),
                        runContext);

        assertThat(model.requestContents).hasSize(2);
        assertThat(model.requestContents.get(0))
                .anyMatch(
                        content ->
                                content.contains(MemoryContextBoundary.OPEN_TAG)
                                        && content.contains("召回记忆：称呼用户为亮哥"));
        assertThat(model.requestContents.get(1))
                .anyMatch(
                        content ->
                                content.contains(MemoryContextBoundary.OPEN_TAG)
                                        && content.contains("召回记忆：称呼用户为亮哥"));
        assertThat(messageContents(MessageSupport.loadMessages(result.getNdjson())))
                .contains("请调用工具")
                .noneMatch(
                        content ->
                                content.contains(MemoryContextBoundary.OPEN_TAG)
                                        || content.contains("召回记忆：称呼用户为亮哥"));
    }

    /** 校验失败后的同轮重试仍复用已预取的记忆上下文。 */
    @Test
    void shouldKeepPrefetchedMemoryForRetryAfterFailedOwnedLoopAttempt() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.FAIL_FIRST_THEN_HISTORY_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-memory-retry-session");
        AgentRunContext runContext =
                new AgentRunContext(null, "run-memory-retry", session.getSessionId(), session.getSourceKey());
        runContext.setMemoryPrefetchContext("重试问题", "召回记忆：失败后仍要称呼亮哥");

        assertThatThrownBy(
                        () ->
                                invokeExecuteSingle(
                                        gateway,
                                        session,
                                        "system",
                                        "重试问题",
                                        Collections.emptyList(),
                                        ConversationFeedbackSink.noop(),
                                        ConversationEventSink.noop(),
                                        false,
                                        config.getLlm(),
                                        runContext))
                .hasRootCauseMessage("first attempt failed");

        assertThat(runContext.getMemoryPrefetchContext()).contains("失败后仍要称呼亮哥");
        assertThat(messageContents(MessageSupport.loadMessages(session.getNdjson())))
                .contains("重试问题")
                .noneMatch(content -> content.contains("失败后仍要称呼亮哥"));

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "重试问题",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(model.requestContents).hasSize(2);
        assertThat(model.requestContents.get(0))
                .anyMatch(content -> content.contains("失败后仍要称呼亮哥"));
        assertThat(model.requestContents.get(1))
                .anyMatch(content -> content.contains("失败后仍要称呼亮哥"));
        assertThat(messageContents(MessageSupport.loadMessages(result.getNdjson())))
                .contains("重试问题", "重试答复")
                .noneMatch(content -> content.contains("失败后仍要称呼亮哥"));
    }

    /** 校验内部恢复提示词不会误用用户原问题对应的预取记忆。 */
    @Test
    void shouldSkipPrefetchedMemoryForDifferentInternalPrompt() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.HISTORY_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-memory-recovery-session");
        AgentRunContext runContext =
                new AgentRunContext(null, "run-memory-recovery", session.getSessionId(), session.getSourceKey());
        runContext.setMemoryPrefetchContext("原始问题", "召回记忆：只应给原始问题使用");

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "内部恢复提示",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(model.requestContents).hasSize(1);
        assertThat(model.requestContents.get(0))
                .contains("内部恢复提示")
                .noneMatch(content -> content.contains("只应给原始问题使用"));
        assertThat(messageContents(MessageSupport.loadMessages(result.getNdjson())))
                .contains("内部恢复提示", "历史答复")
                .noneMatch(content -> content.contains("只应给原始问题使用"));
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

    /**
     * 通过反射调用受保护的单次执行入口。
     *
     * @param gateway 待测试的网关实例。
     * @param session 当前会话记录。
     * @param systemPrompt 系统提示词。
     * @param userMessage 用户输入。
     * @param tools 工具对象集合。
     * @param feedbackSink 反馈输出。
     * @param eventSink 事件输出。
     * @param resume 是否恢复挂起会话。
     * @param llmConfig 已解析模型配置。
     * @param runContext 当前运行上下文。
     * @return 返回模型执行结果。
     */
    private static LlmResult invokeExecuteSingle(
            SolonAiLlmGateway gateway,
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            List<Object> tools,
            ConversationFeedbackSink feedbackSink,
            ConversationEventSink eventSink,
            boolean resume,
            AppConfig.LlmConfig llmConfig,
            AgentRunContext runContext)
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
                        runContext);
    }

    /**
     * 提取消息内容列表，方便断言模型请求中携带的上下文。
     *
     * @param messages 消息列表。
     * @return 返回消息文本内容列表。
     */
    private static List<String> messageContents(List<ChatMessage> messages) {
        List<String> contents = new ArrayList<String>();
        for (ChatMessage message : messages) {
            contents.add(message == null ? "" : message.getContent());
        }
        return contents;
    }

    /**
     * 找到最近一次工具消息，用于验证自有循环持久化的 observation 内容。
     *
     * @param messages 会话消息列表。
     * @return 返回最近一次工具消息；不存在时返回 null。
     */
    private static ToolMessage lastToolMessage(List<ChatMessage> messages) {
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

    /**
     * 生成固定长度文本，避免测试依赖外部文件。
     *
     * @param value 单字符或短文本。
     * @param count 重复次数。
     * @return 返回重复后的文本。
     */
    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * Math.max(0, count));
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
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
        LONG_TOOL_OUTPUT,
        /** 模拟协议层只返回 tool_calls 聚合结果但不写入 AgentSession 的场景。 */
        NATIVE_TOOL_WITHOUT_SESSION_APPEND,
        HISTORY_FINAL,
        FAIL_FIRST_THEN_HISTORY_FINAL,
        STREAM_FINAL,
        STREAM_VISIBLE_TOOL_PREAMBLE
    }

    private static class FakeChatModel extends ChatModel {
        private final FakeMode mode;

        /** 记录每次模型请求实际看到的消息内容快照。 */
        private final List<List<String>> requestContents = new ArrayList<List<String>>();

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
            model.requestContents.add(messageContents(session.getMessages()));
            if (model.mode == FakeMode.FAIL_FIRST_THEN_HISTORY_FINAL && model.calls == 1) {
                throw new IOException("first attempt failed");
            }
            ToolMessage toolMessage = lastToolMessage(session.getMessages());
            AssistantMessage assistant;
            if (model.mode == FakeMode.HISTORY_FINAL
                    || model.mode == FakeMode.FAIL_FIRST_THEN_HISTORY_FINAL) {
                assistant =
                        ChatMessage.ofAssistant(
                                model.mode == FakeMode.FAIL_FIRST_THEN_HISTORY_FINAL
                                        ? "重试答复"
                                        : "历史答复");
            } else if (toolMessage == null) {
                if (model.mode == FakeMode.LONG_TOOL_OUTPUT) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要读取长文件\n"
                                            + "Action: read_file\n"
                                            + "Action Input: {\"path\":\"runtime/logs/long-observation.json\"}");
                } else if (model.mode == FakeMode.NATIVE_TOOL_WITHOUT_SESSION_APPEND) {
                    assistant = assistantWithToolCall("call_native_echo", "echo_tool", "{\"value\":\"native\"}");
                    return new FakeResponse(model, options, assistant, false);
                } else {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要调用工具\n"
                                            + "Action: echo_tool\n"
                                            + "Action Input: {\"value\":\"alpha\"}");
                }
            } else {
                assistant = ChatMessage.ofAssistant("最终答复：" + toolMessage.getContent());
            }
            session.addMessage(assistant);
            return new FakeResponse(model, options, assistant, false);
        }

        @Override
        public Flux<ChatResponse> stream() {
            try {
                if (model.mode != FakeMode.STREAM_FINAL
                        && model.mode != FakeMode.STREAM_VISIBLE_TOOL_PREAMBLE) {
                    return Flux.just(call());
                }
                if (prompt != null && !prompt.isEmpty()) {
                    session.addMessage(prompt);
                }
                model.calls++;
                model.requestContents.add(messageContents(session.getMessages()));
                if (model.mode == FakeMode.STREAM_VISIBLE_TOOL_PREAMBLE) {
                    ToolMessage toolMessage = lastToolMessage(session.getMessages());
                    if (toolMessage == null) {
                        AssistantMessage visible = ChatMessage.ofAssistant("Need second read.");
                        AssistantMessage aggregation =
                                assistantWithToolCall(
                                        "Need second read.",
                                        "call_preamble_read",
                                        "read_file",
                                        "{\"path\":\"runtime/logs/page.json\"}");
                        session.addMessage(visible);
                        session.addMessage(aggregation);
                        return Flux.just(new FakeResponse(model, options, visible, true, aggregation));
                    }
                    AssistantMessage finalJson = ChatMessage.ofAssistant("{\"pass\":true}");
                    session.addMessage(finalJson);
                    return Flux.just(new FakeResponse(model, options, finalJson, true));
                }
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

        private AssistantMessage assistantWithToolCall(
                String callId, String name, String arguments) {
            return assistantWithToolCall("", callId, name, arguments);
        }

        private AssistantMessage assistantWithToolCall(
                String content, String callId, String name, String arguments) {
            Map<String, Object> argumentMap = new LinkedHashMap<String, Object>();
            argumentMap.put("value", "native");
            Map<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", name);
            function.put("arguments", arguments);

            Map<String, Object> rawCall = new LinkedHashMap<String, Object>();
            rawCall.put("id", callId);
            rawCall.put("type", "function");
            rawCall.put("function", function);

            List<Map> rawCalls = new ArrayList<Map>();
            rawCalls.add(rawCall);
            List<ToolCall> toolCalls = new ArrayList<ToolCall>();
            toolCalls.add(new ToolCall("0", callId, name, arguments, argumentMap));
            return new AssistantMessage(content, false, null, rawCalls, toolCalls, null);
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
        private final AssistantMessage aggregationMessage;

        private FakeResponse(
                FakeChatModel model, ChatOptions options, AssistantMessage message, boolean stream) {
            this(model, options, message, stream, message);
        }

        private FakeResponse(
                FakeChatModel model,
                ChatOptions options,
                AssistantMessage message,
                boolean stream,
                AssistantMessage aggregationMessage) {
            this.model = model;
            this.options = options;
            this.message = message;
            this.stream = stream;
            this.aggregationMessage = aggregationMessage;
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
            return aggregationMessage;
        }

        @Override
        public String getAggregationContent() {
            return aggregationMessage == null ? getContent() : aggregationMessage.getContent();
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
