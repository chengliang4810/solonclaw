package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.MemoryContextBoundary;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
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

    /** 非对象原生 arguments 必须写回工具错误，不能调用目标 handler。 */
    @Test
    void shouldRejectInvalidNativeToolArgumentsWithoutExecutingHandler() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.INVALID_NATIVE_ARGUMENTS);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-invalid-arguments-session");
        AtomicInteger handlerCalls = new AtomicInteger();
        FunctionToolDesc echo = new FunctionToolDesc("echo_tool");
        echo.doHandle(
                args -> {
                    handlerCalls.incrementAndGet();
                    return "must-not-run";
                });

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

        assertThat(handlerCalls).hasValue(0);
        assertThat(model.calls).isEqualTo(2);
        assertThat(result.getAssistantMessage().getResultContent())
                .contains("Tool call arguments must be a JSON object");
        ToolMessage observation = lastToolMessage(MessageSupport.loadMessages(result.getNdjson()));
        assertThat(observation).isNotNull();
        assertThat(observation.getContent()).contains("arguments must be a JSON object");
        Object persistedStatus = observation.getMetadataAs("solonclaw.tool.status");
        assertThat(persistedStatus).isEqualTo("error");
    }

    /** 缺失、截断、数组和标量 arguments 均应 fail-closed，只有 JSON 对象合法。 */
    @Test
    void shouldValidateRawToolArgumentsAsJsonObjects() throws Exception {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config());
        Method validator =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "validateToolCallArguments", ToolCall.class);
        validator.setAccessible(true);

        assertThat(validateArguments(validator, gateway, "")).contains("non-empty JSON object");
        assertThat(validateArguments(validator, gateway, "{\"value\":"))
                .contains("valid JSON object syntax");
        assertThat(validateArguments(validator, gateway, "[1,2]"))
                .contains("must be a JSON object");
        assertThat(validateArguments(validator, gateway, "7")).contains("must be a JSON object");
        assertThat(validateArguments(validator, gateway, "{\"value\":7}")).isNull();
    }

    /** 调用 arguments 根类型校验器。 */
    private String validateArguments(Method validator, SolonAiLlmGateway gateway, String raw)
            throws Exception {
        ToolCall call =
                new ToolCall(
                        "0",
                        "call-validate",
                        "echo_tool",
                        raw,
                        Collections.<String, Object>emptyMap());
        return (String) validator.invoke(gateway, call);
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
                new FakeChatModel(
                        config.getLlm().getModel(), FakeMode.NATIVE_TOOL_WITHOUT_SESSION_APPEND);
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
    void shouldRestoreExplicitCronjobBooleanArgsWhenModelOmitsThem() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(
                        config.getLlm().getModel(), FakeMode.CRONJOB_MISSING_BOOLEAN_ARGS);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-cronjob-boolean-args-session");
        final List<Map<String, Object>> handledArgs = new ArrayList<Map<String, Object>>();
        RecordingEventSink eventSink = new RecordingEventSink();

        FunctionToolDesc cronjob = new FunctionToolDesc("cronjob");
        cronjob.description("Manage cron jobs.");
        cronjob.doHandle(
                args -> {
                    handledArgs.add(new HashMap<String, Object>(args));
                    return "created";
                });

        invokeExecuteSingle(
                gateway,
                session,
                "system",
                "创建 cronjob：script=probe.py no_agent=true wrap_response=false",
                Collections.singletonList(cronjob),
                ConversationFeedbackSink.noop(),
                eventSink,
                false,
                config.getLlm(),
                null);

        assertThat(handledArgs).hasSize(1);
        assertThat(handledArgs.get(0).get("no_agent")).isEqualTo(Boolean.TRUE);
        assertThat(handledArgs.get(0).get("wrap_response")).isEqualTo(Boolean.FALSE);
        assertThat(eventSink.toolStartedArgs).hasSize(1);
        assertThat(eventSink.toolStartedArgs.get(0).get("no_agent")).isEqualTo(Boolean.TRUE);
        assertThat(eventSink.toolStartedArgs.get(0).get("wrap_response")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void shouldAuditTodoWritesAsSideEffectingAndReadsAsReadOnly() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.TODO_WRITE_THEN_READ);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-todo-audit-session");
        Map<String, ToolCallRecord> records = new LinkedHashMap<String, ToolCallRecord>();
        AgentRunContext runContext =
                new AgentRunContext(
                        recordingRunRepository(records),
                        "run-todo-audit",
                        session.getSessionId(),
                        session.getSourceKey());

        FunctionToolDesc todo = new FunctionToolDesc("todo");
        todo.description("Manage todos.");
        todo.doHandle(args -> args.containsKey("todos") ? "written" : "read");

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "替换 todo 后立即读取",
                        Collections.singletonList(todo),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("todo audit ok");
        List<ToolCallRecord> calls = new ArrayList<ToolCallRecord>(records.values());
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getArgsPreview()).contains("todos", "merge=false");
        assertThat(calls.get(0).isSideEffecting()).isTrue();
        assertThat(calls.get(0).isReadOnly()).isFalse();
        assertThat(calls.get(0).getExecutionPolicy()).isEqualTo("serial");
        assertThat(calls.get(1).getArgsPreview()).isEqualTo("{}");
        assertThat(calls.get(1).isSideEffecting()).isFalse();
        assertThat(calls.get(1).isReadOnly()).isTrue();
        assertThat(calls.get(1).getExecutionPolicy()).isEqualTo("parallel_readonly");
    }

    @Test
    void shouldAuditCronjobInspectAsReadOnlyAndCreateAsSideEffecting() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.CRONJOB_INSPECT_THEN_CREATE);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-cronjob-audit-session");
        Map<String, ToolCallRecord> records = new LinkedHashMap<String, ToolCallRecord>();
        AgentRunContext runContext =
                new AgentRunContext(
                        recordingRunRepository(records),
                        "run-cronjob-audit",
                        session.getSessionId(),
                        session.getSourceKey());

        FunctionToolDesc cronjob = new FunctionToolDesc("cronjob");
        cronjob.description("Manage cron jobs.");
        cronjob.doHandle(args -> "inspect".equals(args.get("action")) ? "inspected" : "created");

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "先检查定时任务再创建定时任务",
                        Collections.singletonList(cronjob),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("cronjob audit ok");
        List<ToolCallRecord> calls = new ArrayList<ToolCallRecord>(records.values());
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getArgsPreview()).contains("action=inspect");
        assertThat(calls.get(0).isSideEffecting()).isFalse();
        assertThat(calls.get(0).isReadOnly()).isTrue();
        assertThat(calls.get(0).getExecutionPolicy()).isEqualTo("parallel_readonly");
        assertThat(calls.get(1).getArgsPreview()).contains("action=create");
        assertThat(calls.get(1).isSideEffecting()).isTrue();
        assertThat(calls.get(1).isReadOnly()).isFalse();
        assertThat(calls.get(1).getExecutionPolicy()).isEqualTo("serial");
    }

    @Test
    void shouldRejectOwnedLoopToolCallWhenWebRunBudgetIsExceeded() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(5);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.THREE_TOOL_CALLS_THEN_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-tool-budget-session");
        Map<String, ToolCallRecord> records = new LinkedHashMap<String, ToolCallRecord>();
        List<String> events = new ArrayList<String>();
        AgentRunContext runContext =
                new AgentRunContext(
                        recordingRunRepository(records, events),
                        "run-tool-budget",
                        session.getSessionId(),
                        session.getSourceKey());
        runContext.setToolPolicy(Arrays.asList("session_search", "todo"), Integer.valueOf(2));
        final int[] searchCalls = new int[] {0};
        final int[] todoCalls = new int[] {0};

        FunctionToolDesc sessionSearch = new FunctionToolDesc("session_search");
        sessionSearch.description("Search sessions.");
        sessionSearch.doHandle(
                args -> {
                    searchCalls[0]++;
                    return "search-" + searchCalls[0];
                });
        FunctionToolDesc todo = new FunctionToolDesc("todo");
        todo.description("Manage todos.");
        todo.doHandle(
                args -> {
                    todoCalls[0]++;
                    return "todo-" + todoCalls[0];
                });

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "先搜索再读 todo，不要第三次调用工具",
                        Arrays.asList(sessionSearch, todo),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(searchCalls[0]).isEqualTo(1);
        assertThat(todoCalls[0]).isEqualTo(1);
        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("budget final");
        assertThat(events).contains("tool.policy.denied", "tool.policy.end");
        assertThat(runContext.getAttemptedToolCalls()).isEqualTo(3);
        List<ToolCallRecord> calls = new ArrayList<ToolCallRecord>(records.values());
        assertThat(calls).hasSize(3);
        assertThat(countToolCallsByStatus(calls, "completed")).isEqualTo(2);
        assertThat(countToolCallsByStatus(calls, "denied")).isEqualTo(1);
        ToolCallRecord denied = findToolCall(calls, "session_search", "denied");
        assertThat(denied).isNotNull();
        assertThat(denied.getResultPreview()).contains("本轮 Web 运行最多允许 2 次工具调用");
        List<ChatMessage> messages = MessageSupport.loadMessages(result.getNdjson());
        assertThat(messageContents(messages))
                .anyMatch(content -> content.contains("本轮 Web 运行最多允许 2 次工具调用"));
    }

    @Test
    void shouldRejectOwnedLoopToolCallOutsideAllowedTools() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.TEXT_ACTION);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-tool-allow-list-session");
        Map<String, ToolCallRecord> records = new LinkedHashMap<String, ToolCallRecord>();
        List<String> events = new ArrayList<String>();
        AgentRunContext runContext =
                new AgentRunContext(
                        recordingRunRepository(records, events),
                        "run-tool-allow-list",
                        session.getSessionId(),
                        session.getSourceKey());
        runContext.setToolPolicy(Collections.singletonList("todo"), Integer.valueOf(5));
        final int[] echoCalls = new int[] {0};

        FunctionToolDesc echo = new FunctionToolDesc("echo_tool");
        echo.description("Echo one value.");
        echo.doHandle(
                args -> {
                    echoCalls[0]++;
                    return "工具结果：" + args.get("value");
                });

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

        assertThat(echoCalls[0]).isEqualTo(0);
        assertThat(events).contains("tool.policy.denied", "tool.policy.end");
        List<ToolCallRecord> calls = new ArrayList<ToolCallRecord>(records.values());
        assertThat(calls).hasSize(1);
        ToolCallRecord denied = findToolCall(calls, "echo_tool", "denied");
        assertThat(denied).isNotNull();
        assertThat(denied.getResultPreview()).contains("本轮 Web 运行只允许调用工具 [todo]");
        assertThat(messageContents(MessageSupport.loadMessages(result.getNdjson())))
                .anyMatch(content -> content.contains("本轮 Web 运行只允许调用工具 [todo]"));
    }

    /** 验证人工审批暂停本轮时，未执行工具的轨迹会收口为待审批状态而非持续 running。 */
    @Test
    void shouldClosePendingApprovalToolTraceWhenOwnedLoopEnds() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config), null);
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.PENDING_APPROVAL);
        TestGateway gateway = new TestGateway(config, repository, model, approvalService);
        SessionRecord session = session("owned-loop-pending-approval-session");
        Map<String, ToolCallRecord> records = new LinkedHashMap<String, ToolCallRecord>();
        AgentRunContext runContext =
                new AgentRunContext(
                        recordingRunRepository(records),
                        "run-pending-approval",
                        session.getSessionId(),
                        session.getSourceKey());
        final int[] terminalCalls = new int[] {0};

        FunctionToolDesc terminal = new FunctionToolDesc("terminal");
        terminal.description("Execute a shell command.");
        terminal.doHandle(
                args -> {
                    terminalCalls[0]++;
                    return "unexpected execution";
                });

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "执行需要审批的测试命令",
                        Collections.singletonList(terminal),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(result.getAssistantMessage().getResultContent()).contains("需要审批");
        assertThat(terminalCalls[0]).isZero();
        assertThat(records.values()).hasSize(1);
        ToolCallRecord pending = records.values().iterator().next();
        assertThat(pending.getStatus()).isEqualTo("approval_required");
        assertThat(pending.getError()).contains("等待人工审批");
        assertThat(pending.getFinishedAt()).isGreaterThan(0L);
    }

    /** 工作区外文件写入必须在 handler 前暂停，审批恢复后只执行原工具调用一次。 */
    @Test
    void shouldPauseAndResumeOutsideWorkspaceWriteExactlyOnce() throws Exception {
        AppConfig config = config();
        config.getSecurity().setGuardrailMode("approval");
        RecordingSessionRepository repository = new RecordingSessionRepository();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config), null);
        File boundary =
                new File("target/owned-loop-file-approval-" + System.nanoTime())
                        .getCanonicalFile();
        File workspace = new File(boundary, "workspace").getCanonicalFile();
        File outside = new File(boundary, "outside.txt").getCanonicalFile();
        assertThat(workspace.mkdirs() || workspace.isDirectory()).isTrue();
        FakeChatModel model =
                new FakeChatModel(
                        config.getLlm().getModel(),
                        FakeMode.OUTSIDE_WORKSPACE_WRITE_APPROVAL,
                        outside.getAbsolutePath());
        TestGateway gateway = new TestGateway(config, repository, model, approvalService);
        SessionRecord session = session("owned-loop-file-approval-session");
        AgentRunContext runContext =
                new AgentRunContext(
                        null, "run-file-approval", session.getSessionId(), session.getSourceKey());
        runContext.setWorkspaceDir(workspace.getAbsolutePath());
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger approvalRequests = new AtomicInteger();
        approvalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        approvalRequests.incrementAndGet();
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {}
                });

        FunctionToolDesc fileWrite = new FunctionToolDesc("file_write");
        fileWrite.doHandle(
                args -> {
                    handlerCalls.incrementAndGet();
                    DangerousCommandApprovalService.grantCurrentThreadApproval(
                            "terminal", "unconsumed-test-command");
                    return "written:" + args.get("fileName");
                });

        LlmResult pendingResult =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "写入工作区外文件",
                        Collections.singletonList(fileWrite),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        runContext);

        assertThat(handlerCalls).hasValue(0);
        assertThat(approvalRequests).hasValue(1);
        assertThat(pendingResult.getAssistantMessage().getResultContent()).contains("需要审批");
        SqliteAgentSession approvalSession = new SqliteAgentSession(session, repository);
        assertThat(approvalService.getPendingApproval(approvalSession)).isNotNull();
        assertThat(
                        approvalService.approve(
                                approvalSession,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        LlmResult resumed =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        null,
                        Collections.singletonList(fileWrite),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        true,
                        config.getLlm(),
                        runContext);

        assertThat(handlerCalls).hasValue(1);
        assertThat(approvalRequests).hasValue(1);
        assertThat(resumed.getAssistantMessage().getResultContent()).contains("写入完成");
        assertThat(
                        DangerousCommandApprovalService.consumeCurrentThreadApproval(
                                "terminal", "unconsumed-test-command"))
                .isFalse();
        assertThat(
                        new SecurityPolicyService(config)
                                .checkWorkspaceWritePath(
                                        outside.getAbsolutePath(), workspace.getAbsolutePath())
                                .isApprovalRequired())
                .isTrue();
    }

    /** 多目标工作区外写入必须逐目标展示和审批，全部批准后才允许执行原始 handler。 */
    @Test
    void shouldApproveEachOutsideWorkspaceTargetBeforeRunningHandler() throws Exception {
        AppConfig config = config();
        config.getSecurity().setGuardrailMode("approval");
        RecordingSessionRepository repository = new RecordingSessionRepository();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config), null);
        File boundary =
                new File("target/owned-loop-multi-file-approval-" + System.nanoTime())
                        .getCanonicalFile();
        File workspace = new File(boundary, "workspace").getCanonicalFile();
        File first = new File(boundary, "first.txt").getCanonicalFile();
        File second = new File(boundary, "second.txt").getCanonicalFile();
        assertThat(workspace.mkdirs() || workspace.isDirectory()).isTrue();
        FakeChatModel model =
                new FakeChatModel(
                        config.getLlm().getModel(),
                        FakeMode.OUTSIDE_WORKSPACE_MULTI_WRITE_APPROVAL,
                        first.getAbsolutePath(),
                        second.getAbsolutePath());
        TestGateway gateway = new TestGateway(config, repository, model, approvalService);
        SessionRecord session = session("owned-loop-multi-file-approval-session");
        AgentRunContext runContext =
                new AgentRunContext(
                        null,
                        "run-multi-file-approval",
                        session.getSessionId(),
                        session.getSourceKey());
        runContext.setWorkspaceDir(workspace.getAbsolutePath());
        AtomicInteger handlerCalls = new AtomicInteger();
        List<String> requestedTargets = new ArrayList<String>();
        approvalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        requestedTargets.add(event.getCommand());
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {}
                });
        FunctionToolDesc fileWrite = new FunctionToolDesc("file_write");
        fileWrite.doHandle(
                args -> {
                    handlerCalls.incrementAndGet();
                    return "written";
                });

        invokeExecuteSingle(
                gateway,
                session,
                "system",
                "写入两个工作区外文件",
                Collections.singletonList(fileWrite),
                ConversationFeedbackSink.noop(),
                ConversationEventSink.noop(),
                false,
                config.getLlm(),
                runContext);
        SqliteAgentSession approvalSession = new SqliteAgentSession(session, repository);
        assertThat(handlerCalls).hasValue(0);
        assertThat(requestedTargets).containsExactly(first.getAbsolutePath());
        assertThat(
                        approvalService.approve(
                                approvalSession,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        invokeExecuteSingle(
                gateway,
                session,
                "system",
                null,
                Collections.singletonList(fileWrite),
                ConversationFeedbackSink.noop(),
                ConversationEventSink.noop(),
                true,
                config.getLlm(),
                runContext);
        assertThat(handlerCalls).hasValue(0);
        assertThat(requestedTargets)
                .containsExactly(first.getAbsolutePath(), second.getAbsolutePath());
        approvalSession = new SqliteAgentSession(session, repository);
        assertThat(
                        approvalService.approve(
                                approvalSession,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        LlmResult completed =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        null,
                        Collections.singletonList(fileWrite),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        true,
                        config.getLlm(),
                        runContext);

        assertThat(handlerCalls).hasValue(1);
        assertThat(requestedTargets).hasSize(2);
        assertThat(completed.getAssistantMessage().getResultContent()).contains("写入完成");
        SecurityPolicyService policy = new SecurityPolicyService(config);
        assertThat(
                        policy.checkWorkspaceWritePath(
                                        first.getAbsolutePath(), workspace.getAbsolutePath())
                                .isApprovalRequired())
                .isTrue();
        assertThat(
                        policy.checkWorkspaceWritePath(
                                        second.getAbsolutePath(), workspace.getAbsolutePath())
                                .isApprovalRequired())
                .isTrue();
    }

    /** 工具 handler 抛出异常时也必须清理尚未消费的命令和文件策略审批。 */
    @Test
    void shouldClearThreadApprovalsWhenOwnedToolHandlerFails() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.TEXT_ACTION);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-approval-cleanup-session");
        Path boundary =
                new File("target/owned-loop-approval-cleanup-" + System.nanoTime())
                        .getCanonicalFile()
                        .toPath();
        Path workspace = boundary.resolve("workspace");
        Path outside = boundary.resolve("outside.txt");
        Files.createDirectories(workspace);
        FunctionToolDesc failing = new FunctionToolDesc("echo_tool");
        failing.doHandle(
                args -> {
                    DangerousCommandApprovalService.grantCurrentThreadApproval(
                            "terminal", "failed-handler-command");
                    SecurityPolicyService.approveFilePolicyForCurrentThread(
                            "workspace_outside_write", outside.toString());
                    throw new IllegalStateException("expected handler failure");
                });

        invokeExecuteSingle(
                gateway,
                session,
                "system",
                "执行失败工具",
                Collections.singletonList(failing),
                ConversationFeedbackSink.noop(),
                ConversationEventSink.noop(),
                false,
                config.getLlm(),
                null);

        assertThat(
                        DangerousCommandApprovalService.consumeCurrentThreadApproval(
                                "terminal", "failed-handler-command"))
                .isFalse();
        assertThat(
                        new SecurityPolicyService(config)
                                .checkWorkspaceWritePath(outside.toString(), workspace.toString())
                                .isApprovalRequired())
                .isTrue();
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
        assertThat(eventSink.reasoningDeltas).noneMatch(delta -> delta.contains("<think>"));
        assertThat(eventSink.assistantDeltas).noneMatch(delta -> delta.contains("<think>"));
        assertThat(result.getRequestCount()).isEqualTo(1L);
        assertThat(result.getInputTokens()).isEqualTo(19L);
        assertThat(result.getOutputTokens()).isEqualTo(3L);
        assertThat(result.getReasoningTokens()).isEqualTo(2L);
        assertThat(result.getTotalTokens()).isEqualTo(24L);
        assertThat(result.getRawUsageJson()).contains("prompt_tokens");
    }

    /** 流式正文中断后应保留已接收内容并结束本轮，不能重放同一请求。 */
    @Test
    void shouldKeepVisiblePartialResponseWhenStreamFails() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_PARTIAL_THEN_ERROR);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-stream-partial-session");
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

        assertThat(model.calls).isEqualTo(1);
        assertThat(result.getAssistantMessage().getResultContent())
                .isEqualTo("已经完成一半\n\n（响应流意外中断，以上为已接收的部分内容）");
        assertThat(eventSink.assistantDeltas)
                .containsExactly("已经完成一半\n\n（响应流意外中断，以上为已接收的部分内容）");
        assertThat(MessageSupport.loadMessages(result.getNdjson()))
                .anyMatch(
                        message ->
                                message instanceof AssistantMessage
                                        && message.getContent().contains("响应流意外中断"));
    }

    /** 流中断前出现未完成工具调用时必须继续按失败处理，不能把工具前缀当成答复。 */
    @Test
    void shouldFailInterruptedStreamWithUnresolvedToolCall() throws Exception {
        AppConfig config = config();
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_TOOL_THEN_ERROR);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-stream-tool-error-session");
        RecordingEventSink eventSink = new RecordingEventSink();

        assertThatThrownBy(
                        () ->
                                invokeExecuteSingle(
                                        gateway,
                                        session,
                                        "system",
                                        "请调用工具",
                                        Collections.emptyList(),
                                        ConversationFeedbackSink.noop(),
                                        eventSink,
                                        false,
                                        config.getLlm(),
                                        null))
                .hasRootCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("stream interrupted");
        assertThat(eventSink.assistantDeltas).isEmpty();
    }

    /** 验证 llm.stream=true 即使没有事件订阅也会走 Solon AI 流式请求。 */
    @Test
    void shouldHonorConfiguredStreamWithoutEventSink() throws Exception {
        AppConfig config = config();
        config.getLlm().setStream(true);
        config.getReact().setMaxSteps(1);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model = new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-configured-stream-session");
        session.setReasoningEffortOverride("high");
        session.setServiceTierOverride("priority");

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请流式回复",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        config.getLlm(),
                        null);

        assertThat(result.isStreamed()).isTrue();
        assertThat(result.getAssistantMessage().getResultContent()).isEqualTo("流式答复");
        assertThat(gateway.capturedSession).isSameAs(session);
    }

    @Test
    void shouldNotEmitThinkingOnlyAggregationAsAssistantDelta() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(1);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_THINKING_TAGS_ONLY);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-thinking-only-session");
        RecordingEventSink eventSink = new RecordingEventSink();

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请只输出思考",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        eventSink,
                        false,
                        config.getLlm(),
                        null);

        assertThat(result.isStreamed()).isTrue();
        assertThat(result.getReasoningText()).contains("内部计划");
        assertThat(eventSink.reasoningDeltas).contains("内部计划");
        assertThat(eventSink.assistantDeltas).isEmpty();
        assertThat(eventSink.reasoningDeltas).noneMatch(delta -> delta.contains("<think>"));
    }

    @Test
    void shouldNotReplayFullAggregationWhenStreamedVisibleTextAlreadyEmitted() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(1);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(config.getLlm().getModel(), FakeMode.STREAM_AGGREGATION_DIFFERS);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-stream-aggregation-differs-session");
        RecordingEventSink eventSink = new RecordingEventSink();

        LlmResult result =
                invokeExecuteSingle(
                        gateway,
                        session,
                        "system",
                        "请输出 JSON",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        eventSink,
                        false,
                        config.getLlm(),
                        null);

        assertThat(result.isStreamed()).isTrue();
        assertThat(result.getAssistantMessage().getResultContent())
                .isEqualTo("{\"next_slice\":\"可更新 loop-reset-3 为 in_progress 继续闭环\"}");
        assertThat(result.getRawResponse())
                .isEqualTo("{\"next_slice\":\"可更新 loop-reset-3 为 in_progress 继续闭环\"}");
        assertThat(eventSink.assistantDeltas)
                .containsExactly("{\"next_slice\":\"可更新 loop-reset-3 为 in_progress继续闭环\"}");
        assertThat(String.join("", eventSink.assistantDeltas)).doesNotContain("}{");
    }

    @Test
    void shouldSuppressVisibleToolPreambleUntilFinalAnswer() throws Exception {
        AppConfig config = config();
        config.getReact().setMaxSteps(3);
        RecordingSessionRepository repository = new RecordingSessionRepository();
        FakeChatModel model =
                new FakeChatModel(
                        config.getLlm().getModel(), FakeMode.STREAM_VISIBLE_TOOL_PREAMBLE);
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
        assertThat(model.requestContents.get(0)).contains("上一轮用户说喜欢中文", "上一轮助手已确认", "这轮要记得上次内容");
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
                new AgentRunContext(
                        null, "run-memory", session.getSessionId(), session.getSourceKey());
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
                new FakeChatModel(
                        config.getLlm().getModel(), FakeMode.FAIL_FIRST_THEN_HISTORY_FINAL);
        TestGateway gateway = new TestGateway(config, repository, model);
        SessionRecord session = session("owned-loop-memory-retry-session");
        AgentRunContext runContext =
                new AgentRunContext(
                        null, "run-memory-retry", session.getSessionId(), session.getSourceKey());
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
        assertThat(model.requestContents.get(0)).anyMatch(content -> content.contains("失败后仍要称呼亮哥"));
        assertThat(model.requestContents.get(1)).anyMatch(content -> content.contains("失败后仍要称呼亮哥"));
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
                new AgentRunContext(
                        null,
                        "run-memory-recovery",
                        session.getSessionId(),
                        session.getSourceKey());
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
        config.getLlm().setApiKey("sk-test-valid-key");
        config.getLlm().setModel("owned-loop-model");
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
     * 按状态统计工具审计记录数量，用于确认策略拒绝不会被误判为完成。
     *
     * @param records 工具审计记录列表。
     * @param status 目标状态。
     * @return 返回匹配状态的记录数量。
     */
    private static int countToolCallsByStatus(List<ToolCallRecord> records, String status) {
        int count = 0;
        for (ToolCallRecord record : records) {
            if (record != null && status.equals(record.getStatus())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 查找指定工具和状态的审计记录，方便断言策略拒绝结果。
     *
     * @param records 工具审计记录列表。
     * @param toolName 工具名称。
     * @param status 目标状态。
     * @return 返回匹配记录；不存在时返回 null。
     */
    private static ToolCallRecord findToolCall(
            List<ToolCallRecord> records, String toolName, String status) {
        for (ToolCallRecord record : records) {
            if (record != null
                    && toolName.equals(record.getToolName())
                    && status.equals(record.getStatus())) {
                return record;
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

    /**
     * 构造只记录工具审计结果的运行仓储，避免单测依赖真实 SQLite。
     *
     * @param records 按工具调用标识保存的审计记录。
     * @return 返回运行仓储代理。
     */
    private static AgentRunRepository recordingRunRepository(Map<String, ToolCallRecord> records) {
        return recordingRunRepository(records, null);
    }

    /**
     * 构造只记录工具审计和事件类型的运行仓储，避免单测依赖真实 SQLite。
     *
     * @param records 按工具调用标识保存的审计记录。
     * @param events 需要记录的运行事件类型集合。
     * @return 返回运行仓储代理。
     */
    private static AgentRunRepository recordingRunRepository(
            Map<String, ToolCallRecord> records, List<String> events) {
        return (AgentRunRepository)
                Proxy.newProxyInstance(
                        AgentRunRepository.class.getClassLoader(),
                        new Class[] {AgentRunRepository.class},
                        (proxy, method, args) -> {
                            if ("saveToolCall".equals(method.getName())) {
                                ToolCallRecord record = (ToolCallRecord) args[0];
                                records.put(record.getToolCallId(), record);
                                return null;
                            }
                            if ("listToolCalls".equals(method.getName())) {
                                return new ArrayList<ToolCallRecord>(records.values());
                            }
                            if ("appendEvent".equals(method.getName())) {
                                if (events != null && args != null && args.length > 0) {
                                    Object event = args[0];
                                    try {
                                        Method getter = event.getClass().getMethod("getEventType");
                                        events.add(String.valueOf(getter.invoke(event)));
                                    } catch (Exception ignored) {
                                    }
                                }
                                return null;
                            }
                            Class<?> type = method.getReturnType();
                            if (Void.TYPE.equals(type)) {
                                return null;
                            }
                            if (Integer.TYPE.equals(type)) {
                                return Integer.valueOf(0);
                            }
                            if (Long.TYPE.equals(type)) {
                                return Long.valueOf(0L);
                            }
                            if (Boolean.TYPE.equals(type)) {
                                return Boolean.FALSE;
                            }
                            if (List.class.isAssignableFrom(type)) {
                                return Collections.emptyList();
                            }
                            return null;
                        });
    }

    private static class RecordingEventSink implements ConversationEventSink {
        private final List<String> assistantDeltas = new ArrayList<String>();
        private final List<String> reasoningDeltas = new ArrayList<String>();
        private final List<Map<String, Object>> toolStartedArgs =
                new ArrayList<Map<String, Object>>();

        @Override
        public void onAssistantDelta(String delta) {
            assistantDeltas.add(delta);
        }

        @Override
        public void onReasoningDelta(String delta) {
            reasoningDeltas.add(delta);
        }

        @Override
        public void onToolStarted(String toolName, Map<String, Object> args) {
            toolStartedArgs.add(new HashMap<String, Object>(args));
        }
    }

    private static class TestGateway extends SolonAiLlmGateway {
        private final ChatModel model;

        /** 记录真实请求链传入模型构建器的会话。 */
        private SessionRecord capturedSession;

        private TestGateway(
                AppConfig appConfig, SessionRepository sessionRepository, ChatModel model) {
            this(appConfig, sessionRepository, model, null);
        }

        /** 创建可选接入危险命令审批拦截器的测试网关。 */
        private TestGateway(
                AppConfig appConfig,
                SessionRepository sessionRepository,
                ChatModel model,
                DangerousCommandApprovalService approvalService) {
            super(
                    appConfig,
                    sessionRepository,
                    approvalService,
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

        @Override
        protected ChatModel buildChatModel(AppConfig.LlmConfig resolved, SessionRecord session) {
            capturedSession = session;
            return model;
        }
    }

    private enum FakeMode {
        TEXT_ACTION,
        LONG_TOOL_OUTPUT,
        /** 模拟协议层只返回 tool_calls 聚合结果但不写入 AgentSession 的场景。 */
        NATIVE_TOOL_WITHOUT_SESSION_APPEND,
        /** 模拟协议层返回数组根 arguments。 */
        INVALID_NATIVE_ARGUMENTS,
        CRONJOB_MISSING_BOOLEAN_ARGS,
        TODO_WRITE_THEN_READ,
        CRONJOB_INSPECT_THEN_CREATE,
        THREE_TOOL_CALLS_THEN_FINAL,
        PENDING_APPROVAL,
        OUTSIDE_WORKSPACE_WRITE_APPROVAL,
        OUTSIDE_WORKSPACE_MULTI_WRITE_APPROVAL,
        HISTORY_FINAL,
        FAIL_FIRST_THEN_HISTORY_FINAL,
        STREAM_FINAL,
        STREAM_PARTIAL_THEN_ERROR,
        STREAM_TOOL_THEN_ERROR,
        STREAM_THINKING_TAGS_ONLY,
        STREAM_AGGREGATION_DIFFERS,
        STREAM_VISIBLE_TOOL_PREAMBLE
    }

    private static class FakeChatModel extends ChatModel {
        private final FakeMode mode;

        /** 工作区外写入审批场景使用的目标路径。 */
        private final String approvalWritePath;

        /** 多目标审批场景使用的第二个工作区外路径。 */
        private final String secondApprovalWritePath;

        /** 记录每次模型请求实际看到的消息内容快照。 */
        private final List<List<String>> requestContents = new ArrayList<List<String>>();

        private int calls;

        private FakeChatModel(String model, FakeMode mode) {
            this(model, mode, null, null);
        }

        /** 创建可选携带工作区外写入目标的假模型。 */
        private FakeChatModel(String model, FakeMode mode, String approvalWritePath) {
            this(model, mode, approvalWritePath, null);
        }

        /** 创建可选携带两个工作区外写入目标的假模型。 */
        private FakeChatModel(
                String model,
                FakeMode mode,
                String approvalWritePath,
                String secondApprovalWritePath) {
            super(fakeConfig(model));
            this.mode = mode;
            this.approvalWritePath = approvalWritePath;
            this.secondApprovalWritePath = secondApprovalWritePath;
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
            } else if (model.mode == FakeMode.TODO_WRITE_THEN_READ) {
                int toolMessages = toolMessageCount(session.getMessages());
                if (toolMessages == 0) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要替换 todo\n"
                                            + "Action: todo\n"
                                            + "Action Input: {\"merge\":false,\"todos\":[{\"id\":\"audit-1\",\"content\":\"审计写入\",\"status\":\"pending\"}]}");
                } else if (toolMessages == 1) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要读回 todo\n" + "Action: todo\n" + "Action Input: {}");
                } else {
                    assistant = ChatMessage.ofAssistant("todo audit ok");
                }
            } else if (model.mode == FakeMode.CRONJOB_INSPECT_THEN_CREATE) {
                int toolMessages = toolMessageCount(session.getMessages());
                if (toolMessages == 0) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 先只读检查定时任务\n"
                                            + "Action: cronjob\n"
                                            + "Action Input: {\"action\":\"inspect\",\"job_id\":\"job-audit\",\"limit\":5}");
                } else if (toolMessages == 1) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 再创建定时任务\n"
                                            + "Action: cronjob\n"
                                            + "Action Input: {\"action\":\"create\",\"name\":\"job-new\",\"schedule\":\"2m\",\"deliver\":\"origin\",\"no_agent\":false,\"wrap_response\":true}");
                } else {
                    assistant = ChatMessage.ofAssistant("cronjob audit ok");
                }
            } else if (model.mode == FakeMode.THREE_TOOL_CALLS_THEN_FINAL) {
                int toolMessages = toolMessageCount(session.getMessages());
                if (toolMessages == 0) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 先检索会话\n"
                                            + "Action: session_search\n"
                                            + "Action Input: {\"query\":\"tool budget\"}");
                } else if (toolMessages == 1) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 再读取 todo\n" + "Action: todo\n" + "Action Input: {}");
                } else if (toolMessages == 2) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 尝试第三次检索\n"
                                            + "Action: session_search\n"
                                            + "Action Input: {\"query\":\"extra search\"}");
                } else {
                    assistant = ChatMessage.ofAssistant("budget final");
                }
            } else if (model.mode == FakeMode.PENDING_APPROVAL) {
                assistant =
                        ChatMessage.ofAssistant(
                                "Thought: 需要执行审批测试命令\n"
                                        + "Action: terminal\n"
                                        + "Action Input: {\"command\":\"rm -rf /tmp/solonclaw-owned-loop-approval\"}");
            } else if (model.mode == FakeMode.OUTSIDE_WORKSPACE_WRITE_APPROVAL
                    || model.mode == FakeMode.OUTSIDE_WORKSPACE_MULTI_WRITE_APPROVAL) {
                if (toolMessage == null) {
                    String pathArguments =
                            model.mode == FakeMode.OUTSIDE_WORKSPACE_MULTI_WRITE_APPROVAL
                                    ? "\"fileNames\":"
                                            + ONode.serialize(
                                                    Arrays.asList(
                                                            model.approvalWritePath,
                                                            model.secondApprovalWritePath))
                                    : "\"fileName\":"
                                            + ONode.serialize(model.approvalWritePath);
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要写入工作区外文件\n"
                                            + "Action: file_write\n"
                                            + "Action Input: {"
                                            + pathArguments
                                            + ",\"content\":\"approved\"}");
                } else {
                    assistant = ChatMessage.ofAssistant("写入完成");
                }
            } else if (toolMessage == null) {
                if (model.mode == FakeMode.CRONJOB_MISSING_BOOLEAN_ARGS) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要创建定时任务\n"
                                            + "Action: cronjob\n"
                                            + "Action Input: {\"action\":\"create\",\"schedule\":\"2h\",\"script\":\"probe.py\",\"deliver\":\"local\"}");
                } else if (model.mode == FakeMode.LONG_TOOL_OUTPUT) {
                    assistant =
                            ChatMessage.ofAssistant(
                                    "Thought: 需要读取长文件\n"
                                            + "Action: read_file\n"
                                            + "Action Input: {\"path\":\"workspace/logs/long-observation.json\"}");
                } else if (model.mode == FakeMode.NATIVE_TOOL_WITHOUT_SESSION_APPEND) {
                    assistant =
                            assistantWithToolCall(
                                    "call_native_echo", "echo_tool", "{\"value\":\"native\"}");
                    return new FakeResponse(model, options, assistant, false);
                } else if (model.mode == FakeMode.INVALID_NATIVE_ARGUMENTS) {
                    assistant =
                            assistantWithToolCall("call_invalid_echo", "echo_tool", "[\"bad\"]");
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
                        && model.mode != FakeMode.STREAM_PARTIAL_THEN_ERROR
                        && model.mode != FakeMode.STREAM_TOOL_THEN_ERROR
                        && model.mode != FakeMode.STREAM_THINKING_TAGS_ONLY
                        && model.mode != FakeMode.STREAM_AGGREGATION_DIFFERS
                        && model.mode != FakeMode.STREAM_VISIBLE_TOOL_PREAMBLE) {
                    return Flux.just(call());
                }
                if (prompt != null && !prompt.isEmpty()) {
                    session.addMessage(prompt);
                }
                model.calls++;
                model.requestContents.add(messageContents(session.getMessages()));
                if (model.mode == FakeMode.STREAM_PARTIAL_THEN_ERROR) {
                    AssistantMessage partial = ChatMessage.ofAssistant("已经完成一半");
                    return Flux.concat(
                            Flux.just(new FakeResponse(model, options, partial, true)),
                            Flux.error(new IOException("stream interrupted")));
                }
                if (model.mode == FakeMode.STREAM_TOOL_THEN_ERROR) {
                    AssistantMessage visible = ChatMessage.ofAssistant("准备调用工具");
                    AssistantMessage toolCall =
                            assistantWithToolCall(
                                    "准备调用工具",
                                    "call_interrupted",
                                    "echo_tool",
                                    "{\"value\":\"native\"}");
                    return Flux.concat(
                            Flux.just(
                                    new FakeResponse(model, options, visible, true),
                                    new FakeResponse(model, options, visible, true, toolCall)),
                            Flux.error(new IOException("stream interrupted")));
                }
                if (model.mode == FakeMode.STREAM_THINKING_TAGS_ONLY) {
                    AssistantMessage thinking = new AssistantMessage("<think>内部计划</think>", true);
                    return Flux.just(new FakeResponse(model, options, thinking, true));
                }
                if (model.mode == FakeMode.STREAM_AGGREGATION_DIFFERS) {
                    AssistantMessage visible =
                            ChatMessage.ofAssistant(
                                    "{\"next_slice\":\"可更新 loop-reset-3 为 in_progress继续闭环\"}");
                    AssistantMessage aggregation =
                            ChatMessage.ofAssistant(
                                    "{\"next_slice\":\"可更新 loop-reset-3 为 in_progress 继续闭环\"}");
                    session.addMessage(aggregation);
                    return Flux.just(new FakeResponse(model, options, visible, true, aggregation));
                }
                if (model.mode == FakeMode.STREAM_VISIBLE_TOOL_PREAMBLE) {
                    ToolMessage toolMessage = lastToolMessage(session.getMessages());
                    if (toolMessage == null) {
                        AssistantMessage visible = ChatMessage.ofAssistant("Need second read.");
                        AssistantMessage aggregation =
                                assistantWithToolCall(
                                        "Need second read.",
                                        "call_preamble_read",
                                        "read_file",
                                        "{\"path\":\"workspace/logs/page.json\"}");
                        session.addMessage(visible);
                        session.addMessage(aggregation);
                        return Flux.just(
                                new FakeResponse(model, options, visible, true, aggregation));
                    }
                    AssistantMessage finalJson = ChatMessage.ofAssistant("{\"pass\":true}");
                    session.addMessage(finalJson);
                    return Flux.just(new FakeResponse(model, options, finalJson, true));
                }
                AssistantMessage thinking = new AssistantMessage("<think>流式思考</think>", true);
                AssistantMessage visible = ChatMessage.ofAssistant("流式答复");
                session.addMessage(visible);
                return Flux.just(
                        new FakeResponse(model, options, thinking, true),
                        new FakeResponse(
                                model, options, visible, true, visible, streamFinalUsage()));
            } catch (IOException e) {
                return Flux.error(e);
            }
        }

        /**
         * 构造最终流式分片携带的模型用量，复现真实 provider 只在最后一个 chunk 返回 usage 的场景。
         *
         * @return 返回模拟的流式最终用量。
         */
        private AiUsage streamFinalUsage() {
            ONode source =
                    ONode.ofJson(
                            "{"
                                    + "\"prompt_tokens\":19,"
                                    + "\"completion_tokens\":3,"
                                    + "\"total_tokens\":24,"
                                    + "\"completion_tokens_details\":{\"reasoning_tokens\":2}"
                                    + "}");
            return new AiUsage(19L, 0L, 3L, 24L, source);
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

        private int toolMessageCount(List<ChatMessage> messages) {
            int count = 0;
            if (messages == null) {
                return 0;
            }
            for (ChatMessage message : messages) {
                if (message instanceof ToolMessage) {
                    count++;
                }
            }
            return count;
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
    }

    private static class FakeResponse implements ChatResponse {
        private final FakeChatModel model;
        private final ChatOptions options;
        private final AssistantMessage message;
        private final boolean stream;
        private final AssistantMessage aggregationMessage;

        /** 模拟协议响应携带的模型用量。 */
        private final AiUsage usage;

        private FakeResponse(
                FakeChatModel model,
                ChatOptions options,
                AssistantMessage message,
                boolean stream) {
            this(model, options, message, stream, message);
        }

        private FakeResponse(
                FakeChatModel model,
                ChatOptions options,
                AssistantMessage message,
                boolean stream,
                AssistantMessage aggregationMessage) {
            this(model, options, message, stream, aggregationMessage, null);
        }

        private FakeResponse(
                FakeChatModel model,
                ChatOptions options,
                AssistantMessage message,
                boolean stream,
                AssistantMessage aggregationMessage,
                AiUsage usage) {
            this.model = model;
            this.options = options;
            this.message = message;
            this.stream = stream;
            this.aggregationMessage = aggregationMessage;
            this.usage = usage;
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
        public AiUsage getUsage() {
            return usage;
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
        public SessionRecord cloneSession(
                String sourceKey, String sourceSessionId, String branchName) {
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
