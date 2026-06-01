package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.TerminalDimensionSupport;
import com.jimuqu.solon.claw.cli.TerminalCommandCatalog;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.gateway.feedback.ToolPreviewSupport;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.web.DashboardCronService;
import com.jimuqu.solon.claw.web.DashboardKanbanService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.terminal.Size;
import org.noear.solon.ai.chat.message.ChatMessage;

/** Agent terminal gateway service. */
public class TuiGatewayService implements TuiGatewayEventSink {
    private static final int MAX_HISTORY_EVENTS = 400;
    private static final int MAX_SESSION_LIST = 50;
    private static final String DEFAULT_BUSY_MODE = "interrupt";
    private static final int CLIENT_CONTRACT = 1;

    private final AppConfig appConfig;
    private final SessionRepository sessionRepository;
    private final ConversationOrchestrator conversationOrchestrator;
    private final CommandService commandService;
    private final AgentRunControlService agentRunControlService;
    private final LlmProviderService llmProviderService;
    private final TuiRunProjector runProjector;
    private final TuiApprovalProjector approvalProjector;
    private final TuiExtensionProjector extensionProjector;
    private final ExecutorService executor;
    private final ConcurrentMap<String, TuiSessionState> states =
            new ConcurrentHashMap<String, TuiSessionState>();
    private final ConcurrentMap<String, TuiConnection> connections =
            new ConcurrentHashMap<String, TuiConnection>();
    private final AtomicLong fallbackSeq = new AtomicLong(System.currentTimeMillis() * 1000L);

    public TuiGatewayService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            ConversationOrchestrator conversationOrchestrator,
            CommandService commandService,
            AgentRunControlService agentRunControlService,
            LlmProviderService llmProviderService,
            DashboardCronService cronService,
            DashboardKanbanService kanbanService,
            DashboardMcpService mcpService,
            CliRuntime cliRuntime,
            DangerousCommandApprovalService approvalService) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.conversationOrchestrator = conversationOrchestrator;
        this.commandService = commandService;
        this.agentRunControlService = agentRunControlService;
        this.llmProviderService = llmProviderService;
        this.runProjector = new TuiRunProjector(agentRunRepository);
        this.approvalProjector = new TuiApprovalProjector(sessionRepository, approvalService, this);
        this.extensionProjector =
                new TuiExtensionProjector(
                        cronService,
                        kanbanService,
                        mcpService,
                        cliRuntime,
                        sessionRepository,
                        approvalService,
                        appConfig);
        if (approvalService != null) {
            approvalService.addApprovalObserver(this.approvalProjector);
        }
        this.executor = BoundedExecutorFactory.fixed("tui-gateway", 4, 128);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public void onOpen(TuiConnection connection) {
        connections.put(connection.id(), connection);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("connection_id", safe(connection.id(), 120));
        payload.put("commands", TerminalCommandCatalog.slashCommands());
        payload.put("busy_modes", java.util.Arrays.asList("queue", "steer", "interrupt"));
        payload.put("client_contract", Integer.valueOf(CLIENT_CONTRACT));
        payload.put("status", "ready");
        connection.sendEvent("gateway.ready", null, payload);
        try {
            connection.sendResult(null, null, statusPayload());
            pushExtensionSnapshots(connection, null);
        } catch (Exception e) {
            connection.sendError(null, null, "STATUS_FAILED", safeError(e));
        }
    }

    public void onClose(TuiConnection connection) {
        if (connection != null) {
            connections.remove(connection.id());
        }
    }

    @Override
    public void publish(TuiEvent event) {
        if (event == null) {
            return;
        }
        String sessionId = event.getSessionId();
        if (StrUtil.isNotBlank(sessionId)) {
            TuiSessionState state = state(sessionId);
            remember(state, event);
        }
        for (TuiConnection connection : connections.values()) {
            if (StrUtil.isBlank(sessionId)
                    || StrUtil.isBlank(connection.getActiveSessionId())
                    || sessionId.equals(connection.getActiveSessionId())) {
                connection.sendEvent(event);
            }
        }
    }

    public void handle(TuiConnection connection, TuiEnvelope envelope) {
        String method = StrUtil.nullToEmpty(envelope.getMethod()).trim();
        try {
            if ("client.ready".equals(method)) {
                connection.sendResult(envelope.getId(), connection.getActiveSessionId(), statusPayload());
                pushExtensionSnapshots(connection, connection.getActiveSessionId());
            } else if ("session.start".equals(method)) {
                Map<String, Object> result = startSession(connection, envelope.getParams());
                connection.sendResult(envelope.getId(), stringValue(result.get("session_id")), result);
            } else if ("session.resume".equals(method)) {
                Map<String, Object> result = resumeSession(connection, envelope.getSessionId(), envelope.getParams());
                connection.sendResult(envelope.getId(), stringValue(result.get("session_id")), result);
            } else if ("session.list".equals(method)) {
                connection.sendResult(envelope.getId(), connection.getActiveSessionId(), listSessions());
            } else if ("session.most_recent".equals(method)) {
                connection.sendResult(envelope.getId(), connection.getActiveSessionId(), mostRecentSession());
            } else if ("session.status".equals(method)) {
                Map<String, Object> result = sessionStatus(connection, envelope);
                connection.sendResult(envelope.getId(), stringValue(result.get("session_id")), result);
            } else if ("session.usage".equals(method)) {
                Map<String, Object> result = sessionUsage(connection, envelope);
                connection.sendResult(envelope.getId(), stringValue(result.get("session_id")), result);
            } else if ("session.delete".equals(method)) {
                Map<String, Object> result = deleteSession(resolveTargetSessionId(envelope));
                connection.sendResult(envelope.getId(), stringValue(result.get("session_id")), result);
            } else if ("session.branch".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), branchSession(connection, envelope));
            } else if ("session.compress".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/compact"));
            } else if ("session.retry".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/retry"));
            } else if ("session.undo".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/undo"));
            } else if ("session.controls".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), sessionControls(connection, envelope));
            } else if ("run.replay".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), replayRuns(connection, envelope));
            } else if ("run.control".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), controlRun(connection, envelope));
            } else if ("input.send".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), submitInput(connection, envelope, "interrupt"));
            } else if ("input.queue".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), submitInput(connection, envelope, "queue"));
            } else if ("input.steer".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), submitInput(connection, envelope, "steer"));
            } else if ("input.interrupt".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), interrupt(connection, envelope.getSessionId()));
            } else if ("slash.run".equals(method) || "slash.confirm".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), submitSlash(connection, envelope));
            } else if ("model.options".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), modelOptions());
            } else if ("model.switch".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), switchModel(connection, envelope));
            } else if ("approval.list".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), approvalSnapshot(connection, envelope));
            } else if ("approval.approve".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), resolveApproval(connection, envelope, true));
            } else if ("approval.deny".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), resolveApproval(connection, envelope, false));
            } else if ("integration.snapshot".equals(method) || "status.snapshot".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), integrationSnapshot(connection, envelope));
            } else if ("cron.snapshot".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), extensionSnapshot(connection, envelope, "cron"));
            } else if ("kanban.snapshot".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), extensionSnapshot(connection, envelope, "kanban"));
            } else if ("mcp.snapshot".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), extensionSnapshot(connection, envelope, "mcp"));
            } else if ("acp.snapshot".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), extensionSnapshot(connection, envelope, "acp"));
            } else if ("mcp.reload".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/reload-mcp"));
            } else if ("cron.list".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/cron list"));
            } else if ("cron.run".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/cron run " + textParam(envelope, "job_id", "")));
            } else if ("kanban.open".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/kanban show " + textParam(envelope, "task_id", "")));
            } else if ("acp.status".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), runSlash(connection, envelope, "/acp status"));
            } else if ("terminal.resize".equals(method)) {
                connection.sendResult(envelope.getId(), envelope.getSessionId(), resize(envelope));
            } else {
                connection.sendError(envelope.getId(), envelope.getSessionId(), "UNKNOWN_METHOD", "Unsupported TUI method: " + safe(method, 120));
            }
        } catch (Exception e) {
            connection.sendError(envelope.getId(), envelope.getSessionId(), "TUI_FAILED", safeError(e));
        }
    }

    private Map<String, Object> startSession(TuiConnection connection, Map<String, Object> params)
            throws Exception {
        String requestedId = stringValue(params.get("session_id"));
        String sessionId = StrUtil.isBlank(requestedId) ? IdSupport.newId() : requestedId;
        String sourceKey = sourceKey(sessionId);
        SessionRecord session = sessionRepository.findById(sessionId);
        if (session == null) {
            session = new SessionRecord();
            session.setSessionId(sessionId);
            session.setSourceKey(sourceKey);
            session.setBranchName("main");
            session.setTitle(titleFrom(params, "新的终端会话"));
            session.setNdjson("");
            session.setCreatedAt(System.currentTimeMillis());
            session.setUpdatedAt(System.currentTimeMillis());
            String model = stringValue(params.get("model"));
            if (StrUtil.isNotBlank(model)) {
                session.setModelOverride(model);
            }
            sessionRepository.save(session);
        }
        sessionRepository.bindSource(sourceKey, sessionId);
        connection.setActiveSessionId(sessionId);
        TuiSessionState state = state(sessionId);
        state.busyMode = textParam(params, "busy_mode", defaultBusyMode());
        Map<String, Object> result = sessionPayload(sessionRepository.findById(sessionId));
        result.put("busy_mode", state.busyMode);
        replay(connection, sessionId, Long.valueOf(0L));
        replayProjected(connection, sessionId, 0L);
        connection.sendEvent("approval.snapshot", sessionId, approvalProjector.pendingSnapshot(sessionId));
        pushExtensionSnapshots(connection, sessionId);
        connection.sendEvent("session.created", sessionId, result);
        return result;
    }

    private Map<String, Object> resumeSession(
            TuiConnection connection, String sessionId, Map<String, Object> params)
            throws Exception {
        String sid = StrUtil.blankToDefault(sessionId, stringValue(params.get("session_id")));
        if (StrUtil.isBlank(sid)) {
            throw new IllegalArgumentException("session_id is required");
        }
        SessionRecord session = sessionRepository.findById(sid);
        if (session == null) {
            throw new IllegalArgumentException("session not found: " + sid);
        }
        sessionRepository.bindSource(sourceKey(sid), sid);
        connection.setActiveSessionId(sid);
        TuiSessionState state = state(sid);
        String busyMode = stringValue(params.get("busy_mode"));
        if (StrUtil.isNotBlank(busyMode)) {
            state.busyMode = normalizeBusyMode(busyMode);
        }
        Long afterSeq = longParam(params.get("after_seq"));
        if (afterSeq == null) {
            afterSeq = Long.valueOf(0L);
        }
        Map<String, Object> result = sessionPayload(session);
        result.put("busy_mode", state.busyMode);
        result.put("queued_count", Integer.valueOf(state.queue.size()));
        replay(connection, sid, afterSeq);
        replayProjected(connection, sid, afterSeq.longValue());
        connection.sendEvent("approval.snapshot", sid, approvalProjector.pendingSnapshot(sid));
        pushExtensionSnapshots(connection, sid);
        connection.sendEvent("session.resumed", sid, result);
        return result;
    }

    private Map<String, Object> listSessions() throws Exception {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (SessionRecord record : filterHumanSessions(sessionRepository.listRecent(MAX_SESSION_LIST * 2, 0))) {
            if (items.size() >= MAX_SESSION_LIST) {
                break;
            }
            items.add(sessionPayload(record));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", items);
        result.put("limit", Integer.valueOf(MAX_SESSION_LIST));
        return result;
    }

    private Map<String, Object> mostRecentSession() {
        if (sessionRepository == null) {
            return nullMostRecentSession();
        }
        try {
            return sessionMostRecent(sessionRepository.listRecent(MAX_SESSION_LIST * 2, 0));
        } catch (Exception e) {
            return nullMostRecentSession();
        }
    }

    private Map<String, Object> sessionMostRecent(List<SessionRecord> records) {
        List<SessionRecord> filtered = filterHumanSessions(records);
        if (filtered.isEmpty()) {
            return nullMostRecentSession();
        }
        SessionRecord record = filtered.get(0);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("session_id", safe(record.getSessionId(), 120));
        payload.put("title", safe(record.getTitle(), 400));
        payload.put("started_at", Long.valueOf(record.getCreatedAt()));
        payload.put("last_active", Long.valueOf(record.getUpdatedAt()));
        payload.put("source_key", safe(record.getSourceKey(), 400));
        return payload;
    }

    private Map<String, Object> nullMostRecentSession() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("session_id", null);
        return payload;
    }

    private List<SessionRecord> filterHumanSessions(List<SessionRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        List<SessionRecord> result = new ArrayList<SessionRecord>();
        for (SessionRecord record : records) {
            if (record == null) {
                continue;
            }
            if (isDelegateChildSession(record)) {
                continue;
            }
            result.add(record);
        }
        return result;
    }

    private boolean isDelegateChildSession(SessionRecord record) {
        if (record == null) {
            return false;
        }
        String sourceKey = StrUtil.nullToEmpty(record.getSourceKey());
        return sourceKey.contains(":delegate:");
    }

    private Map<String, Object> deleteSession(String sessionId) throws Exception {
        if (StrUtil.isBlank(sessionId)) {
            throw new IllegalArgumentException("session_id is required");
        }
        if (isActiveSession(sessionId)) {
            throw new IllegalArgumentException("cannot delete an active session");
        }
        sessionRepository.delete(sessionId);
        states.remove(sessionId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("session_id", safe(sessionId, 120));
        return result;
    }

    private boolean isActiveSession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return false;
        }
        for (TuiConnection candidate : connections.values()) {
            if (candidate != null && StrUtil.equals(sessionId, candidate.getActiveSessionId())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> sessionStatus(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        SessionRecord session = sessionRepository.findById(sid);
        if (session == null) {
            throw new IllegalArgumentException("session not found: " + sid);
        }
        return sessionStatusPayload(session);
    }

    private Map<String, Object> sessionUsage(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        SessionRecord session = sessionRepository.findById(sid);
        if (session == null) {
            throw new IllegalArgumentException("session not found: " + sid);
        }
        return sessionUsagePayload(session);
    }

    private Map<String, Object> branchSession(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        String branchName = textParam(envelope, "branch_name", "branch-" + System.currentTimeMillis());
        SessionRecord branched = sessionRepository.cloneSession(sourceKey(sid), sid, branchName);
        connection.setActiveSessionId(branched.getSessionId());
        Map<String, Object> payload = sessionPayload(branched);
        connection.sendEvent("session.created", branched.getSessionId(), payload);
        return payload;
    }

    private Map<String, Object> submitInput(TuiConnection connection, TuiEnvelope envelope, String fallbackMode)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        String input = textParam(envelope, "input", "");
        if (StrUtil.isBlank(input)) {
            throw new IllegalArgumentException("input is required");
        }
        String requestedMode = textParam(envelope, "busy_mode", fallbackMode);
        final TuiSessionState state = state(sid);
        String mode = normalizeBusyMode(StrUtil.blankToDefault(requestedMode, state.busyMode));
        state.busyMode = mode;

        if (state.running) {
            if ("queue".equals(mode)) {
                TuiQueuedInput queued = new TuiQueuedInput(input, textParam(envelope, "model", ""));
                state.queue.addLast(queued);
                Map<String, Object> payload = runStatePayload(state);
                payload.put("queued_input", safe(input, 400));
                emit(state, connection, "run.queued", sid, payload);
                return payload;
            }
            if ("steer".equals(mode)) {
                state.steerInstruction = input;
                Map<String, Object> payload = runStatePayload(state);
                payload.put("steer", safe(input, 1000));
                emit(state, connection, "run.busy", sid, payload);
                return payload;
            }
            interrupt(connection, sid);
        }

        startRun(connection, sid, input, textParam(envelope, "model", ""));
        return runStatePayload(state);
    }

    private Map<String, Object> submitSlash(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String command = textParam(envelope, "command", textParam(envelope, "input", ""));
        if (StrUtil.isBlank(command)) {
            throw new IllegalArgumentException("command is required");
        }
        if (!command.trim().startsWith("/")) {
            command = "/" + command.trim();
        }
        return runSlash(connection, envelope, command);
    }

    private Map<String, Object> runSlash(TuiConnection connection, TuiEnvelope envelope, String command)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        startRun(connection, sid, command, textParam(envelope, "model", ""));
        Map<String, Object> result = runStatePayload(state(sid));
        result.put("command", safe(command, 400));
        return result;
    }

    private void startRun(
            final TuiConnection connection, final String sessionId, final String input, final String model) {
        final TuiSessionState state = state(sessionId);
        state.running = true;
        state.finalEmitted = false;
        state.failed = false;
        state.currentRunId = IdSupport.newId();
        state.currentAssistant.setLength(0);

        Map<String, Object> started = runStatePayload(state);
        started.put("input", safe(input, 1000));
        emit(state, connection, "run.busy", sessionId, started);
        emit(state, connection, "user.message", sessionId, singleton("content", safe(input, 8000)));

        executor.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        executeRun(connection, state, sessionId, input, model);
                    }
                });
    }

    private void executeRun(
            TuiConnection connection,
            TuiSessionState state,
            String sessionId,
            String input,
            String model) {
        TuiEventSink eventSink = new TuiEventSink(connection, state, sessionId);
        try {
            SessionRecord session = prepareSession(sessionId, input, model);
            GatewayMessage message = buildMessage(session.getSessionId(), input, model);
            GatewayReply reply;
            if (input.trim().startsWith("/")) {
                reply = commandService.handle(message, input.trim(), eventSink);
            } else {
                reply = conversationOrchestrator.handleIncoming(message, eventSink);
            }
            if (reply != null && reply.isError()) {
                eventSink.onRunFailed(session.getSessionId(), new IllegalStateException(reply.getContent()));
            } else {
                String finalReply = reply == null ? state.currentAssistant.toString() : StrUtil.nullToEmpty(reply.getContent());
                if (!state.finalEmitted) {
                    eventSink.onRunCompleted(session.getSessionId(), finalReply, null);
                }
                Object runId = reply == null ? null : reply.getRuntimeMetadata().get("run_id");
                if (runId != null) {
                    state.currentAgentRunId = String.valueOf(runId);
                    emitProjected(connection, state, runProjector.projectRun(session.getSessionId(), String.valueOf(runId), state.lastSeq));
                }
            }
        } catch (Throwable e) {
            eventSink.onRunFailed(sessionId, e);
        } finally {
            state.running = false;
            state.currentRunId = null;
            if (!state.failed) {
                emit(state, connection, "run.completed", sessionId, runStatePayload(state));
            }
            drainQueue(connection, state, sessionId);
        }
    }

    private void drainQueue(TuiConnection connection, TuiSessionState state, String sessionId) {
        TuiQueuedInput next = state.queue.pollFirst();
        if (next == null) {
            emit(state, connection, "run.idle", sessionId, runStatePayload(state));
            return;
        }
        emit(state, connection, "run.queued", sessionId, runStatePayload(state));
        startRun(connection, sessionId, next.input, next.model);
    }

    private Map<String, Object> interrupt(TuiConnection connection, String sessionId) {
        String sid = StrUtil.blankToDefault(sessionId, connection.getActiveSessionId());
        if (StrUtil.isBlank(sid)) {
            throw new IllegalArgumentException("session_id is required");
        }
        String sourceKey = sourceKey(sid);
        AgentRunStopResult result =
                agentRunControlService == null ? null : agentRunControlService.stop(sourceKey);
        TuiSessionState state = state(sid);
        state.queue.clear();
        Map<String, Object> payload = runStatePayload(state);
        payload.put("stop_requested", Boolean.TRUE);
        if (result != null) {
            payload.put("active_run", Boolean.valueOf(result.isActiveRun()));
            payload.put("interrupted", Boolean.valueOf(result.isInterruptSent()));
            payload.put("agent_run_id", safe(result.getRunId(), 120));
            payload.put("started_at", Long.valueOf(result.getStartedAt()));
        }
        emit(state, connection, "run.interrupted", sid, payload);
        return payload;
    }

    private Map<String, Object> switchModel(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        String model = textParam(envelope, "model", "");
        if (StrUtil.isBlank(model)) {
            throw new IllegalArgumentException("model is required");
        }
        sessionRepository.setModelOverride(sid, model);
        Map<String, Object> payload = sessionPayload(sessionRepository.findById(sid));
        payload.put("model", safe(model, 400));
        connection.sendEvent("model.changed", sid, payload);
        return payload;
    }

    private Map<String, Object> approvalSnapshot(TuiConnection connection, TuiEnvelope envelope) {
        String sid = requireSessionId(connection, envelope);
        Map<String, Object> payload = approvalProjector.pendingSnapshot(sid);
        connection.sendEvent("approval.snapshot", sid, payload);
        return payload;
    }

    private Map<String, Object> resolveApproval(
            TuiConnection connection, TuiEnvelope envelope, boolean approve) throws Exception {
        String sid = requireSessionId(connection, envelope);
        Map<String, Object> payload =
                approvalProjector.resolve(
                        sid,
                        textParam(envelope, "selector", textParam(envelope, "approval_id", "")),
                        textParam(envelope, "scope", "once"),
                        approve,
                        "tui");
        connection.sendEvent("approval.snapshot", sid, payload);
        if (Boolean.TRUE.equals(payload.get("ok"))) {
            resumePendingAfterApproval(connection, sid);
        }
        return payload;
    }

    private void resumePendingAfterApproval(final TuiConnection connection, final String sessionId) {
        executor.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        TuiSessionState state = state(sessionId);
                        TuiEventSink eventSink = new TuiEventSink(connection, state, sessionId);
                        try {
                            GatewayReply reply =
                                    conversationOrchestrator.resumePending(
                                            sourceKey(sessionId), eventSink);
                            if (reply != null && reply.isError()) {
                                eventSink.onRunFailed(
                                        sessionId, new IllegalStateException(reply.getContent()));
                            }
                        } catch (Throwable e) {
                            eventSink.onRunFailed(sessionId, e);
                        }
                    }
                });
    }

    private Map<String, Object> replayRuns(TuiConnection connection, TuiEnvelope envelope) {
        String sid = requireSessionId(connection, envelope);
        Long afterSeq = longParam(envelope.getParams().get("after_seq"));
        replayProjected(connection, sid, afterSeq == null ? 0L : afterSeq.longValue());
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ok", Boolean.TRUE);
        payload.put("session_id", safe(sid, 160));
        payload.put("after_seq", Long.valueOf(afterSeq == null ? 0L : afterSeq.longValue()));
        return payload;
    }

    private Map<String, Object> integrationSnapshot(TuiConnection connection, TuiEnvelope envelope) {
        String sid = StrUtil.blankToDefault(envelope.getSessionId(), connection.getActiveSessionId());
        Map<String, Object> payload = extensionProjector.snapshot();
        pushExtensionSnapshots(connection, sid);
        return payload;
    }

    private Map<String, Object> extensionSnapshot(TuiConnection connection, TuiEnvelope envelope, String kind) {
        String sid = StrUtil.blankToDefault(envelope.getSessionId(), connection.getActiveSessionId());
        TuiEvent event = extensionProjector.snapshotEvent(sid, kind);
        connection.sendEvent(event);
        return event.getPayload();
    }

    private void pushExtensionSnapshots(TuiConnection connection, String sessionId) {
        for (TuiEvent event : extensionProjector.snapshotEvents(sessionId)) {
            connection.sendEvent(event);
        }
    }

    private Map<String, Object> controlRun(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        String runId = textParam(envelope, "run_id", state(sid).currentAgentRunId);
        String command = textParam(envelope, "command", "");
        if (StrUtil.isBlank(runId)) {
            throw new IllegalArgumentException("run_id is required");
        }
        if (StrUtil.isBlank(command)) {
            throw new IllegalArgumentException("command is required");
        }
        Map<String, Object> payload =
                agentRunControlService == null
                        ? new LinkedHashMap<String, Object>()
                        : agentRunControlService.controlRun(runId, command, envelope.getParams());
        emitProjected(connection, state(sid), runProjector.projectRun(sid, runId, 0L));
        return payload;
    }

    private Map<String, Object> sessionControls(TuiConnection connection, TuiEnvelope envelope)
            throws Exception {
        String sid = requireSessionId(connection, envelope);
        SessionRecord session = sessionRepository.findById(sid);
        Map<String, Object> payload = sessionPayload(session);
        payload.put("controls", java.util.Arrays.asList("/retry", "/undo", "/branch", "/resume", "/compact"));
        payload.put("compressed", Boolean.valueOf(session != null && StrUtil.isNotBlank(session.getCompressedSummary())));
        payload.put("compressed_summary", safe(session == null ? "" : session.getCompressedSummary(), 2000));
        payload.put("parent_session_id", safe(session == null ? "" : session.getParentSessionId(), 160));
        payload.put("branch_name", safe(session == null ? "" : session.getBranchName(), 160));
        connection.sendEvent("session.controls", sid, payload);
        return payload;
    }

    private Map<String, Object> modelOptions() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> providers = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry : llmProviderService.providers().entrySet()) {
            AppConfig.ProviderConfig provider = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", safe(entry.getKey(), 120));
            item.put("label", safe(provider == null ? entry.getKey() : provider.getName(), 120));
            item.put("dialect", safe(provider == null ? "" : provider.getDialect(), 120));
            item.put("default_model", safe(provider == null ? "" : provider.getDefaultModel(), 400));
            providers.add(item);
        }
        result.put("providers", providers);
        return result;
    }

    private Map<String, Object> resize(TuiEnvelope envelope) {
        Object rawCols = envelope.getParams().get("cols");
        Object rawRows = envelope.getParams().get("rows");
        Size size = TerminalDimensionSupport.sanitizeSize(rawCols, rawRows);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("cols", Integer.valueOf(size.getColumns()));
        payload.put("rows", Integer.valueOf(size.getRows()));
        payload.put(
                "sanitized",
                Boolean.valueOf(
                        !Integer.valueOf(size.getColumns()).equals(rawCols)
                                || !Integer.valueOf(size.getRows()).equals(rawRows)));
        payload.put("ok", Boolean.TRUE);
        return payload;
    }

    private Map<String, Object> statusPayload() throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "ready");
        result.put("commands", TerminalCommandCatalog.slashCommands());
        result.put("running_count", Integer.valueOf(runningCount()));
        result.put("client_contract", Integer.valueOf(CLIENT_CONTRACT));
        result.put("sessions", listSessions().get("sessions"));
        result.put("models", modelOptions().get("providers"));
        result.put("integrations", extensionProjector.snapshot());
        return result;
    }

    private int runningCount() {
        int count = 0;
        for (TuiSessionState state : states.values()) {
            if (state.running) {
                count++;
            }
        }
        return count;
    }

    private SessionRecord prepareSession(String sessionId, String input, String model) throws Exception {
        SessionRecord session = sessionRepository.findById(sessionId);
        if (session == null) {
            session = new SessionRecord();
            session.setSessionId(sessionId);
            session.setSourceKey(sourceKey(sessionId));
            session.setBranchName("main");
            session.setTitle(trimTitle(input));
            session.setNdjson("");
            session.setCreatedAt(System.currentTimeMillis());
            session.setUpdatedAt(System.currentTimeMillis());
            if (StrUtil.isNotBlank(model)) {
                session.setModelOverride(model);
            }
            sessionRepository.save(session);
        }
        if (StrUtil.isNotBlank(model)) {
            sessionRepository.setModelOverride(sessionId, model);
        }
        sessionRepository.bindSource(sourceKey(sessionId), sessionId);
        return sessionRepository.findById(sessionId);
    }

    private GatewayMessage buildMessage(String sessionId, String input, String model) {
        GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "tui", sessionId, input);
        message.setChatType("dm");
        message.setChatName("Agent Terminal");
        message.setUserName("dashboard");
        message.setSourceKeyOverride(sourceKey(sessionId));
        if (StrUtil.isNotBlank(model)) {
            message.setModelOverride(model);
        }
        return message;
    }

    private Map<String, Object> sessionPayload(SessionRecord record) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (record == null) {
            return payload;
        }
        List<ChatMessage> messages = MessageSupport.loadMessages(record.getNdjson());
        payload.put("session_id", safe(record.getSessionId(), 120));
        payload.put("id", safe(record.getSessionId(), 120));
        payload.put("title", safe(StrUtil.blankToDefault(record.getTitle(), "未命名会话"), 400));
        payload.put("branch_name", safe(StrUtil.blankToDefault(record.getBranchName(), "main"), 120));
        payload.put("parent_session_id", safe(record.getParentSessionId(), 120));
        payload.put("model", safe(StrUtil.blankToDefault(record.getLastResolvedModel(), record.getModelOverride()), 400));
        payload.put("provider", safe(record.getLastResolvedProvider(), 120));
        payload.put("message_count", Integer.valueOf(messages.size()));
        payload.put("total_tokens", Long.valueOf(record.getCumulativeTotalTokens()));
        payload.put("last_active", Long.valueOf(record.getUpdatedAt()));
        payload.put("started_at", Long.valueOf(record.getCreatedAt()));
        payload.put("preview", safe(StrUtil.blankToDefault(MessageSupport.getLastUserMessage(record.getNdjson()), record.getCompressedSummary()), 280));
        payload.put("client_contract", Integer.valueOf(CLIENT_CONTRACT));
        return payload;
    }

    private Map<String, Object> sessionStatusPayload(SessionRecord record) throws Exception {
        Map<String, Object> payload = sessionPayload(record);
        if (record == null) {
            return payload;
        }
        TuiSessionState state = state(record.getSessionId());
        String model = stringValue(payload.get("model"));
        String provider = stringValue(payload.get("provider"));
        long totalTokens = record.getCumulativeTotalTokens();
        payload.put("total_tokens", Long.valueOf(totalTokens));
        payload.put("running", Boolean.valueOf(state.running));
        payload.put("queued_count", Integer.valueOf(state.queue.size()));
        payload.put("output", statusOutput(payload, totalTokens, state.running, state.queue.size(), model, provider));
        return payload;
    }

    private Map<String, Object> sessionUsagePayload(SessionRecord record) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (record == null) {
            return payload;
        }
        int calls = usageCalls(record);
        payload.put("session_id", safe(record.getSessionId(), 120));
        payload.put("calls", Integer.valueOf(calls));
        payload.put("calls_estimated", Boolean.TRUE);
        payload.put("input", Long.valueOf(record.getCumulativeInputTokens()));
        payload.put("output", Long.valueOf(record.getCumulativeOutputTokens()));
        payload.put("reasoning", Long.valueOf(record.getCumulativeReasoningTokens()));
        payload.put("cache_read", Long.valueOf(record.getCumulativeCacheReadTokens()));
        payload.put("cache_write", Long.valueOf(record.getCumulativeCacheWriteTokens()));
        payload.put("total", Long.valueOf(record.getCumulativeTotalTokens()));
        payload.put("input_tokens", Long.valueOf(record.getCumulativeInputTokens()));
        payload.put("output_tokens", Long.valueOf(record.getCumulativeOutputTokens()));
        payload.put("reasoning_tokens", Long.valueOf(record.getCumulativeReasoningTokens()));
        payload.put("cache_read_tokens", Long.valueOf(record.getCumulativeCacheReadTokens()));
        payload.put("cache_write_tokens", Long.valueOf(record.getCumulativeCacheWriteTokens()));
        payload.put("total_tokens", Long.valueOf(record.getCumulativeTotalTokens()));
        payload.put("last_input_tokens", Long.valueOf(record.getLastInputTokens()));
        payload.put("last_output_tokens", Long.valueOf(record.getLastOutputTokens()));
        payload.put("last_reasoning_tokens", Long.valueOf(record.getLastReasoningTokens()));
        payload.put("last_cache_read_tokens", Long.valueOf(record.getLastCacheReadTokens()));
        payload.put("last_cache_write_tokens", Long.valueOf(record.getLastCacheWriteTokens()));
        payload.put("last_total_tokens", Long.valueOf(record.getLastTotalTokens()));
        payload.put("last_usage_at", Long.valueOf(record.getLastUsageAt()));
        return payload;
    }

    private int usageCalls(SessionRecord record) {
        if (record == null) {
            return 0;
        }
        return record.getCumulativeTotalTokens() > 0L
                        || record.getCumulativeInputTokens() > 0L
                        || record.getCumulativeOutputTokens() > 0L
                        || record.getLastTotalTokens() > 0L
                        || record.getLastInputTokens() > 0L
                        || record.getLastOutputTokens() > 0L
                ? 1
                : 0;
    }

    private String statusOutput(
            Map<String, Object> payload,
            long totalTokens,
            boolean running,
            int queuedCount,
            String model,
            String provider) {
        List<String> lines = new ArrayList<String>();
        lines.add("solon-claw TUI Status");
        lines.add("");
        lines.add("Session ID: " + stringValue(payload.get("session_id")));
        String title = stringValue(payload.get("title")).trim();
        if (StrUtil.isNotBlank(title)) {
            lines.add("Title: " + title);
        }
        lines.add(
                "Model: "
                        + StrUtil.blankToDefault(model, "(unknown)")
                        + " ("
                        + StrUtil.blankToDefault(provider, "unknown")
                        + ")");
        lines.add("Started: " + stringValue(payload.get("started_at")));
        lines.add("Last Active: " + stringValue(payload.get("last_active")));
        lines.add("Tokens: " + NumberFormat.getIntegerInstance(Locale.US).format(totalTokens));
        lines.add("Agent Running: " + (running ? "Yes" : "No"));
        lines.add("Queued: " + queuedCount);
        return String.join("\n", lines);
    }

    private Map<String, Object> runStatePayload(TuiSessionState state) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("running", Boolean.valueOf(state.running));
        payload.put("busy_mode", state.busyMode);
        payload.put("run_id", safe(state.currentRunId, 120));
        payload.put("agent_run_id", safe(state.currentAgentRunId, 120));
        payload.put("queued_count", Integer.valueOf(state.queue.size()));
        payload.put("has_steer", Boolean.valueOf(StrUtil.isNotBlank(state.steerInstruction)));
        return payload;
    }

    private void replay(TuiConnection connection, String sessionId, Long afterSeq) {
        TuiSessionState state = state(sessionId);
        long from = afterSeq == null ? 0L : afterSeq.longValue();
        synchronized (state.history) {
            for (Map<String, Object> event : state.history) {
                Object seq = event.get("seq");
                if (seq instanceof Number && ((Number) seq).longValue() <= from) {
                    continue;
                }
                connection.sendEvent(
                        stringValue(event.get("type")),
                        sessionId,
                        castPayload(event.get("payload")));
            }
        }
    }

    private void replayProjected(TuiConnection connection, String sessionId, long afterSeq) {
        for (TuiEvent event : runProjector.replaySession(sessionId, sourceKey(sessionId), afterSeq)) {
            state(sessionId).lastSeq = Math.max(state(sessionId).lastSeq, event.getSeq());
            connection.sendEvent(event);
        }
    }

    private void emitProjected(
            TuiConnection connection, TuiSessionState state, List<TuiEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (TuiEvent event : events) {
            remember(state, event);
            connection.sendEvent(event);
        }
    }

    private void emit(
            TuiSessionState state,
            TuiConnection connection,
            String type,
            String sessionId,
            Map<String, Object> payload) {
        long seq = nextSeq(state);
        TuiEvent event = new TuiEvent(type, sessionId, seq, seq, payload);
        remember(state, event);
        connection.sendEvent(event);
    }

    private void remember(TuiSessionState state, TuiEvent event) {
        if (state == null || event == null) {
            return;
        }
        Map<String, Object> safePayload =
                event.getPayload() == null ? new LinkedHashMap<String, Object>() : event.getPayload();
        Map<String, Object> historyEvent = new LinkedHashMap<String, Object>();
        historyEvent.put("type", event.getType());
        historyEvent.put("seq", Long.valueOf(event.getSeq()));
        historyEvent.put("payload", safePayload);
        state.lastSeq = Math.max(state.lastSeq, event.getSeq());
        synchronized (state.history) {
            state.history.addLast(historyEvent);
            while (state.history.size() > MAX_HISTORY_EVENTS) {
                state.history.removeFirst();
            }
        }
    }

    private long nextSeq(TuiSessionState state) {
        long next = fallbackSeq.incrementAndGet();
        if (state == null) {
            return next;
        }
        long stateNext = state.lastSeq + 1L;
        if (stateNext > next) {
            fallbackSeq.set(stateNext);
            return stateNext;
        }
        return next;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castPayload(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    private String requireSessionId(TuiConnection connection, TuiEnvelope envelope) {
        String sid = resolveSessionId(connection, envelope);
        if (StrUtil.isBlank(sid)) {
            throw new IllegalArgumentException("session_id is required");
        }
        if (connection != null) {
            connection.setActiveSessionId(sid);
        }
        return sid;
    }

    private String resolveSessionId(TuiConnection connection, TuiEnvelope envelope) {
        String sid = StrUtil.blankToDefault(envelope.getSessionId(), textParam(envelope, "session_id", ""));
        if (StrUtil.isBlank(sid) && connection != null) {
            sid = connection.getActiveSessionId();
        }
        return sid;
    }

    private String resolveTargetSessionId(TuiEnvelope envelope) {
        return StrUtil.blankToDefault(envelope.getSessionId(), textParam(envelope, "session_id", ""));
    }

    private TuiSessionState state(String sessionId) {
        TuiSessionState current = states.get(sessionId);
        if (current != null) {
            return current;
        }
        TuiSessionState created = new TuiSessionState();
        created.busyMode = defaultBusyMode();
        TuiSessionState existing = states.putIfAbsent(sessionId, created);
        return existing == null ? created : existing;
    }

    private String sourceKey(String sessionId) {
        return "MEMORY:tui:" + StrUtil.blankToDefault(sessionId, "");
    }

    private String defaultBusyMode() {
        String configured =
                appConfig == null || appConfig.getTask() == null
                        ? ""
                        : StrUtil.nullToEmpty(appConfig.getTask().getBusyPolicy());
        return normalizeBusyMode(StrUtil.blankToDefault(configured, DEFAULT_BUSY_MODE));
    }

    private String normalizeBusyMode(String value) {
        String normalized = StrUtil.blankToDefault(value, DEFAULT_BUSY_MODE).trim().toLowerCase(Locale.ROOT);
        if ("queue".equals(normalized) || "steer".equals(normalized) || "interrupt".equals(normalized)) {
            return normalized;
        }
        return DEFAULT_BUSY_MODE;
    }

    private String textParam(TuiEnvelope envelope, String key, String fallback) {
        return textParam(envelope.getParams(), key, fallback);
    }

    private String textParam(Map<String, Object> params, String key, String fallback) {
        Object value = params == null ? null : params.get(key);
        String text = stringValue(value);
        return StrUtil.isBlank(text) ? fallback : text;
    }

    private Long longParam(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        String text = stringValue(value);
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseLong(text));
        } catch (Exception e) {
            return null;
        }
    }

    private String titleFrom(Map<String, Object> params, String fallback) {
        String title = textParam(params, "title", fallback);
        return trimTitle(title);
    }

    private String trimTitle(String text) {
        String normalized = StrUtil.blankToDefault(text, "新的终端会话").replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= CompressionConstants.MAX_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, CompressionConstants.MAX_TITLE_LENGTH) + "...";
    }

    private Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "未知错误 / Unknown error";
        }
        return SecretRedactor.redact(
                StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName()), 1000);
    }

    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private final class TuiEventSink implements ConversationEventSink {
        private final TuiConnection connection;
        private final TuiSessionState state;
        private final String sessionId;

        private TuiEventSink(TuiConnection connection, TuiSessionState state, String sessionId) {
            this.connection = connection;
            this.state = state;
            this.sessionId = sessionId;
        }

        @Override
        public void onRunStarted(String sessionId) {
            Map<String, Object> payload = runStatePayload(state);
            payload.put("session_id", safe(sessionId, 120));
            emit(state, connection, "run.busy", this.sessionId, payload);
        }

        @Override
        public void onAssistantDelta(String delta) {
            if (StrUtil.isBlank(delta)) {
                return;
            }
            state.currentAssistant.append(delta);
            emit(state, connection, "assistant.delta", sessionId, singleton("delta", safe(delta, 8000)));
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (StrUtil.isBlank(delta)) {
                return;
            }
            emit(state, connection, "assistant.reasoning", sessionId, singleton("delta", safe(delta, 4000)));
        }

        @Override
        public void onToolStarted(String toolName, Map<String, Object> args) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tool", safe(toolName, 120));
            payload.put("preview", safe(ToolPreviewSupport.buildPreview(toolName, args, 80, false), 400));
            emit(state, connection, "tool.started", sessionId, payload);
        }

        @Override
        public void onToolCompleted(String toolName, String result, long durationMs) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("tool", safe(toolName, 120));
            payload.put("duration_ms", Long.valueOf(durationMs));
            payload.put("preview", safe(result, 1000));
            emit(state, connection, "tool.completed", sessionId, payload);
        }

        @Override
        public void onAttemptStarted(String runId, int attemptNo, String provider, String model) {
            state.currentAgentRunId = runId;
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safe(runId, 120));
            payload.put("attempt_no", Integer.valueOf(attemptNo));
            payload.put("provider", safe(provider, 120));
            payload.put("model", safe(model, 400));
            emit(state, connection, "attempt.started", sessionId, payload);
        }

        @Override
        public void onAttemptCompleted(String runId, int attemptNo, String status, String reason) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safe(runId, 120));
            payload.put("attempt_no", Integer.valueOf(attemptNo));
            payload.put("status", safe(status, 120));
            payload.put("reason", safe(reason, 1000));
            emit(state, connection, "attempt.completed", sessionId, payload);
        }

        @Override
        public void onCompressionDecision(
                String runId,
                boolean compressed,
                String reason,
                int estimatedTokens,
                int thresholdTokens) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safe(runId, 120));
            payload.put("compressed", Boolean.valueOf(compressed));
            payload.put("reason", safe(reason, 1000));
            payload.put("estimated_tokens", Integer.valueOf(estimatedTokens));
            payload.put("threshold_tokens", Integer.valueOf(thresholdTokens));
            emit(state, connection, "compression.decision", sessionId, payload);
        }

        @Override
        public void onRecoveryStarted(String runId, String recoveryType) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safe(runId, 120));
            payload.put("recovery_type", safe(recoveryType, 240));
            emit(state, connection, "recovery.started", sessionId, payload);
        }

        @Override
        public void onFallback(String runId, String fromProvider, String toProvider, String reason) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("agent_run_id", safe(runId, 120));
            payload.put("from_provider", safe(fromProvider, 120));
            payload.put("to_provider", safe(toProvider, 120));
            payload.put("reason", safe(reason, 1000));
            emit(state, connection, "fallback", sessionId, payload);
        }

        @Override
        public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
            state.finalEmitted = true;
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put(
                    "content",
                    safe(
                            StrUtil.blankToDefault(finalReply, state.currentAssistant.toString()),
                            12000));
            if (result != null) {
                Map<String, Object> usage = new LinkedHashMap<String, Object>();
                usage.put("input_tokens", Long.valueOf(result.getInputTokens()));
                usage.put("output_tokens", Long.valueOf(result.getOutputTokens()));
                usage.put("reasoning_tokens", Long.valueOf(result.getReasoningTokens()));
                usage.put("total_tokens", Long.valueOf(result.getTotalTokens()));
                payload.put("usage", usage);
            }
            emit(state, connection, "assistant.final", this.sessionId, payload);
        }

        @Override
        public void onRunFailed(String sessionId, Throwable error) {
            state.failed = true;
            emit(state, connection, "run.failed", this.sessionId, singleton("error", safeError(error)));
        }
    }

    private static class TuiSessionState {
        private volatile boolean running;
        private volatile String currentRunId;
        private volatile String busyMode;
        private volatile String steerInstruction;
        private volatile boolean finalEmitted;
        private volatile boolean failed;
        private volatile long lastSeq;
        private volatile String currentAgentRunId;
        private final StringBuilder currentAssistant = new StringBuilder();
        private final Deque<TuiQueuedInput> queue = new ArrayDeque<TuiQueuedInput>();
        private final Deque<Map<String, Object>> history = new ArrayDeque<Map<String, Object>>();
    }

    private static class TuiQueuedInput {
        private final String input;
        private final String model;

        private TuiQueuedInput(String input, String model) {
            this.input = input;
            this.model = model;
        }
    }
}
