package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.cli.TerminalSetupCommands;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.noear.snack4.ONode;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.WebSocketListener;

/** 终端 UI WebSocket 协议入口，负责把前端消息转交给本地终端运行时。 */
public class TerminalUiWebSocketListener implements WebSocketListener {
    /** 后台执行 prompt.submit，避免模型请求占住 WebSocket worker 导致其他 RPC 超时。 */
    private final ExecutorService promptExecutor;
    /** 复用本地终端运行时执行会话命令与用户输入。 */
    private final CliRuntime runtime;
    /** 构造终端 UI RPC 响应的服务。 */
    private final TerminalUiRpcService rpcService;
    /** 复用本地终端 setup/config/doctor 命令，避免这些命令落入模型运行时。 */
    private final TerminalSetupCommands setupCommands;
    /** 会话仓储用于把复制来的 TUI 会话 ID 绑定到 Java 终端运行时 source key。 */
    private final SessionRepository sessionRepository;
    /** 复用现有终端工具执行 TUI 的 shell.exec，保留安全检查、脱敏和输出裁剪。 */
    private final SolonClawShellSkill shellSkill;
    /** 危险命令审批服务，用于把 TUI 审批弹层选择写回真实审批流。 */
    private final DangerousCommandApprovalService approvalService;
    /** 复用 Dashboard 访问令牌策略保护远程 TUI WebSocket 控制面。 */
    private final DashboardAuthService dashboardAuthService;
    /** 当前 WebSocket 连接对应的审批观察器，断开连接时需要注销避免泄露。 */
    private final Map<WebSocket, TerminalUiApprovalObserver> approvalObservers =
            new java.util.concurrent.ConcurrentHashMap<WebSocket, TerminalUiApprovalObserver>();

    /** 创建终端 UI WebSocket 协议监听器。 */
    public TerminalUiWebSocketListener(CliRuntime runtime) {
        this(runtime, null, null);
    }

    /** 创建带应用配置的终端 UI WebSocket 协议监听器。 */
    public TerminalUiWebSocketListener(CliRuntime runtime, AppConfig appConfig) {
        this(runtime, appConfig, null);
    }

    /** 创建带应用配置与会话仓储的终端 UI WebSocket 协议监听器。 */
    public TerminalUiWebSocketListener(
            CliRuntime runtime, AppConfig appConfig, SessionRepository sessionRepository) {
        this(
                runtime,
                appConfig,
                sessionRepository,
                appConfig == null ? null : new SecurityPolicyService(appConfig),
                null);
    }

    /** 创建带安全策略与进程注册表的终端 UI WebSocket 协议监听器。 */
    public TerminalUiWebSocketListener(
            CliRuntime runtime,
            AppConfig appConfig,
            SessionRepository sessionRepository,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry) {
        this(
                runtime,
                appConfig,
                sessionRepository,
                securityPolicyService,
                processRegistry,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** 创建带完整后端服务适配的终端 UI WebSocket 协议监听器。 */
    public TerminalUiWebSocketListener(
            CliRuntime runtime,
            AppConfig appConfig,
            SessionRepository sessionRepository,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            DangerousCommandApprovalService approvalService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DashboardSkillsService dashboardSkillsService,
            SqlitePreferenceStore preferenceStore,
            BrowserRuntimeService browserRuntimeService,
            ContextCompressionService contextCompressionService,
            AttachmentPathResolver attachmentResolver,
            McpRuntimeService mcpRuntimeService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DelegationService delegationService,
            AgentRunControlService agentRunControlService,
            AgentRunRepository agentRunRepository,
            RuntimeSettingsService runtimeSettingsService,
            GlobalSettingRepository globalSettingRepository) {
        this.runtime = runtime;
        this.promptExecutor = Executors.newCachedThreadPool(new TerminalUiThreadFactory());
        this.sessionRepository = sessionRepository;
        this.approvalService = approvalService;
        this.dashboardAuthService = appConfig == null ? null : new DashboardAuthService(appConfig);
        this.rpcService =
                new TerminalUiRpcService(
                        appConfig,
                        sessionRepository,
                        localSkillService,
                        skillHubService,
                        checkpointService,
                        dashboardSkillsService,
                        preferenceStore,
                        browserRuntimeService,
                        contextCompressionService,
                        attachmentResolver,
                        processRegistry,
                        mcpRuntimeService,
                        gatewayRuntimeRefreshService,
                        delegationService,
                        agentRunControlService,
                        agentRunRepository,
                        runtimeSettingsService,
                        globalSettingRepository);
        this.setupCommands =
                appConfig == null
                        ? null
                        : new TerminalSetupCommands(
                                appConfig,
                                new TerminalModelPicker(appConfig, new LlmProviderService(appConfig)));
        this.shellSkill =
                new SolonClawShellSkill(
                        System.getProperty("user.dir"),
                        appConfig,
                        securityPolicyService,
                        processRegistry);
    }

    /** 连接建立后通知终端 UI 当前协议可用。 */
    @Override
    public void onOpen(WebSocket socket) {
        if (dashboardAuthService != null && !dashboardAuthService.isAuthorized(socket)) {
            socket.close(1008, "Unauthorized");
            return;
        }
        if (approvalService != null) {
            TerminalUiApprovalObserver observer = new TerminalUiApprovalObserver(socket);
            approvalObservers.put(socket, observer);
            approvalService.addApprovalObserver(observer);
        }
        send(socket, "server.ready", pair("protocol_version", Integer.valueOf(1)));
    }

    /** 处理终端 UI 发来的文本协议消息。 */
    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        try {
            ONode node = ONode.ofJson(StrUtil.blankToDefault(text, "{}"));
            String jsonrpc = node.get("jsonrpc").getString();
            if (StrUtil.isNotBlank(jsonrpc)) {
                handleRpc(socket, node);
                return;
            }
            String type = node.get("type").getString();
            ONode payload = node.get("payload");
            if ("client.hello".equals(type)) {
                send(socket, "server.hello", pair("protocol_version", Integer.valueOf(1)));
                return;
            }
            if ("chat.send".equals(type)) {
                handleChatSend(socket, payload);
                return;
            }
            send(socket, "error", pair("message", "Unsupported terminal UI message: " + type));
        } catch (Throwable e) {
            send(socket, "error", pair("message", safeError(e)));
        }
    }

    /** 拒绝二进制消息，终端 UI 协议只接受 JSON 文本。 */
    @Override
    public void onMessage(WebSocket socket, ByteBuffer bytes) throws IOException {
        send(socket, "error", pair("message", "Binary messages are not supported"));
    }

    /** 终端 UI 断开时无需额外清理，运行态由后端服务管理。 */
    @Override
    public void onClose(WebSocket socket) {
        TerminalUiApprovalObserver observer = approvalObservers.remove(socket);
        if (approvalService != null && observer != null) {
            approvalService.removeApprovalObserver(observer);
        }
    }

    /** 底层连接异常由 WebSocket 容器记录，这里不向业务运行时传播。 */
    @Override
    public void onError(WebSocket socket, Throwable error) {}

    /** 将终端 UI 输入转发给本地运行时并通过事件流返回结果。 */
    private void handleChatSend(WebSocket socket, ONode payload) throws Exception {
        String sessionId = payload.get("session_id").getString();
        bindApprovalObserver(socket, sessionId);
        bindRuntimeSource(sessionId);
        String input = payload.get("input").getString();
        if (StrUtil.isBlank(input)) {
            send(socket, "error", pair("message", "input must not be blank"));
            return;
        }
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket);
        GatewayReply reply = runtime.send(sessionId, input, sink);
        if (reply != null && reply.isError()) {
            sink.onRunFailed(sessionId, new IllegalStateException(reply.getContent()));
            return;
        }
        if (reply != null
                && !sink.hasAssistantDeltaSent()
                && !sink.hasRunCompleted()
                && StrUtil.isNotBlank(reply.getContent())) {
            sink.onAssistantDelta(reply.getContent());
        }
    }

    /** 处理终端 UI GatewayClient 使用的 JSON-RPC 请求帧。 */
    private void handleRpc(WebSocket socket, ONode node) throws Exception {
        String id = node.get("id").getString();
        String method = node.get("method").getString();
        ONode params = node.get("params");
        try {
            if ("prompt.submit".equals(method)) {
                handlePromptSubmit(socket, id, params);
                return;
            }
            if ("slash.exec".equals(method)) {
                sendRpcResult(socket, id, slashExec(socket, params));
                return;
            }
            if ("command.dispatch".equals(method)) {
                sendRpcResult(socket, id, commandDispatch(params));
                return;
            }
            if ("config.set".equals(method)) {
                handleConfigSet(socket, id, params);
                return;
            }
            if ("approval.respond".equals(method)) {
                sendRpcResult(socket, id, approvalRespond(socket, params));
                return;
            }
            Object result = rpcResult(method, params);
            bindApprovalObserverAfterSessionRpc(socket, method, result);
            sendRpcResult(socket, id, result);
        } catch (Throwable e) {
            sendRpcError(socket, id, safeError(e));
        }
    }

    /** 将终端 UI 的 prompt.submit RPC 交给后台线程，避免阻塞同一 WebSocket 上的配置与补全 RPC。 */
    private void handlePromptSubmit(WebSocket socket, String id, ONode params) throws Exception {
        String sessionId = params.get("session_id").getString();
        bindApprovalObserver(socket, sessionId);
        bindRuntimeSource(sessionId);
        String input = params.get("text").getString();
        if (StrUtil.isBlank(input)) {
            sendRpcResult(socket, id, rpcService.ok());
            return;
        }
        sendRpcResult(socket, id, rpcService.ok());
        promptExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        runPromptSubmit(socket, sessionId, input);
                    }
                });
    }

    /** 在后台线程执行模型请求并持续向终端 UI 推送事件流。 */
    private void runPromptSubmit(WebSocket socket, String sessionId, String input) {
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);
        try {
            GatewayReply reply = runtime.send(sessionId, input, sink);
            if (reply != null && reply.isError()) {
                sink.onRunFailed(sessionId, new IllegalStateException(reply.getContent()));
                return;
            }
            if (reply != null
                    && !sink.hasAssistantDeltaSent()
                    && !sink.hasRunCompleted()
                    && StrUtil.isNotBlank(reply.getContent())) {
                sink.onAssistantDelta(reply.getContent());
            }
        } catch (Throwable e) {
            sink.onRunFailed(sessionId, new IllegalStateException(safeError(e), e));
        }
    }

    /** 处理配置写入，并补齐终端 UI 对 skin.changed 事件的期待。 */
    private void handleConfigSet(WebSocket socket, String id, ONode params) throws Exception {
        Object result =
                rpcService.configSet(
                        params.get("key").getString(),
                        params.get("value").getString(),
                        params.get("session_id").getString());
        sendRpcResult(socket, id, result);
        if ("skin".equals(params.get("key").getString())) {
            send(socket, "skin.changed", rpcService.skinPayload(params.get("value").getString()));
        }
    }


    /** 根据 RPC 方法名生成终端 UI 前端所需的响应载荷。 */
    private Object rpcResult(String method, ONode params) throws Exception {
        if ("setup.status".equals(method)) {
            return rpcService.setupStatus();
        }
        if ("commands.catalog".equals(method)) {
            return rpcService.commandsCatalog();
        }
        if ("complete.slash".equals(method)) {
            return rpcService.completeSlash(params.get("text").getString());
        }
        if ("complete.path".equals(method)) {
            return rpcService.completePath(params.get("word").getString());
        }
        if ("session.create".equals(method)) {
            return rpcService.sessionCreate("MEMORY:terminal-ui:bootstrap");
        }
        if ("session.activate".equals(method)) {
            return rpcService.sessionActivate(params.get("session_id").getString());
        }
        if ("session.resume".equals(method)) {
            return rpcService.sessionResume(params.get("session_id").getString());
        }
        if ("session.close".equals(method) || "session.interrupt".equals(method) || "terminal.resize".equals(method)) {
            return rpcService.ok();
        }
        if ("session.save".equals(method)) {
            return rpcService.sessionSave(params.get("session_id").getString());
        }
        if ("session.most_recent".equals(method)) {
            return rpcService.sessionMostRecent();
        }
        if ("session.active_list".equals(method)) {
            return rpcService.activeSessions(params.get("current_session_id").getString());
        }
        if ("session.list".equals(method)) {
            return rpcService.sessionList(params.get("limit").getInt());
        }
        if ("session.delete".equals(method)) {
            return rpcService.sessionDelete(params.get("session_id").getString());
        }
        if ("session.status".equals(method)) {
            return rpcService.sessionStatus(params.get("session_id").getString());
        }
        if ("session.usage".equals(method)) {
            return rpcService.sessionUsage(params.get("session_id").getString());
        }
        if ("session.undo".equals(method)) {
            return runSlash(params.get("session_id").getString(), "undo");
        }
        if ("session.branch".equals(method)) {
            return rpcService.sessionBranch(
                    params.get("session_id").getString(), params.get("name").getString());
        }
        if ("session.title".equals(method)) {
            return rpcService.sessionTitle(
                    params.get("session_id").getString(), params.get("title").getString());
        }
        if ("session.compress".equals(method)) {
            return rpcService.sessionCompress(
                    params.get("session_id").getString(), params.get("focus_topic").getString());
        }
        if ("session.steer".equals(method)) {
            return rpcService.sessionSteer(
                    params.get("session_id").getString(), params.get("text").getString());
        }
        if ("config.get".equals(method)) {
            if ("full".equals(params.get("key").getString())) {
                return rpcService.fullConfig();
            }
            if ("mtime".equals(params.get("key").getString())) {
                return rpcService.configMtime();
            }
            return rpcService.configValue(params.get("key").getString());
        }
        if ("model.options".equals(method)) {
            return rpcService.modelOptions();
        }
        if ("model.save_key".equals(method)) {
            return rpcService.modelSaveKey(
                    params.get("slug").getString(), params.get("api_key").getString());
        }
        if ("model.disconnect".equals(method)) {
            return rpcService.modelDisconnect(params.get("slug").getString());
        }
        if ("channel.options".equals(method)) {
            return rpcService.channelOptions();
        }
        if ("channel.status".equals(method)) {
            return rpcService.channelStatus(params.get("channel").getString());
        }
        if ("channel.save".equals(method)) {
            return rpcService.channelSave(
                    params.get("channel").getString(),
                    stringMap(params.get("values")),
                    params.get("session_id").getString());
        }
        if ("shell.exec".equals(method)) {
            return shellExec(params.get("command").getString());
        }
        if ("prompt.background".equals(method)) {
            return rpcService.promptBackground(
                    params.get("session_id").getString(), params.get("text").getString());
        }
        if ("image.attach".equals(method)) {
            return rpcService.imageAttach(params.get("path").getString());
        }
        if ("input.detect_drop".equals(method)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("matched", Boolean.FALSE);
            return result;
        }
        if ("paste.collapse".equals(method)) {
            return rpcService.pasteCollapse(params.get("text").getString());
        }
        if ("clipboard.paste".equals(method)) {
            return rpcService.clipboardPaste();
        }
        if ("process.stop".equals(method)) {
            return rpcService.processStop();
        }
        if ("reload.mcp".equals(method)) {
            return rpcService.reloadMcp(params.get("confirm").getBoolean(), params.get("always").getBoolean());
        }
        if ("reload.env".equals(method)) {
            return rpcService.reloadEnv();
        }
        if ("browser.manage".equals(method)) {
            return rpcService.browserManage(params.get("action").getString(), params.get("url").getString());
        }
        if ("rollback.list".equals(method)) {
            return rpcService.rollbackList(params.get("session_id").getString());
        }
        if ("rollback.diff".equals(method)) {
            return rpcService.rollbackDiff(checkpointId(params));
        }
        if ("rollback.restore".equals(method)) {
            return rpcService.rollbackRestore(checkpointId(params), params.get("file_path").getString());
        }
        if ("skills.manage".equals(method)) {
            return rpcService.skillsManage(
                    params.get("action").getString(),
                    params.get("query").getString(),
                    params.get("page").getInt());
        }
        if ("skills.reload".equals(method)) {
            return rpcService.skillsReload();
        }
        if ("tools.configure".equals(method)) {
            return rpcService.toolsConfigure(params.get("action").getString(), stringList(params.get("names")));
        }
        if ("voice.toggle".equals(method)) {
            return rpcService.voiceToggle();
        }
        if ("voice.record".equals(method)) {
            return rpcService.voiceRecord();
        }
        if ("sudo.respond".equals(method)
                || "secret.respond".equals(method)
                || "clarify.respond".equals(method)) {
            return rpcService.acknowledge();
        }
        if ("delegation.status".equals(method)) {
            return rpcService.delegationStatus();
        }
        if ("delegation.pause".equals(method)) {
            return rpcService.delegationPause(params.get("paused").getBoolean());
        }
        if ("subagent.interrupt".equals(method)) {
            return rpcService.subagentInterrupt(params.get("subagent_id").getString());
        }
        if ("spawn_tree.list".equals(method) || "spawn_tree.save".equals(method)) {
            return rpcService.spawnTreeList(
                    params.get("session_id").getString(), params.get("limit").getInt());
        }
        if ("spawn_tree.load".equals(method)) {
            return rpcService.spawnTreeLoad(params.get("path").getString());
        }
        throw new IllegalArgumentException(method + " is not available in the current backend");
    }

    /** 执行终端 UI 透传到后端的 slash 命令。 */
    private Map<String, Object> slashExec(WebSocket socket, ONode params) throws Exception {
        String normalized = StrUtil.nullToEmpty(params.get("command").getString()).trim();
        String sessionId = params.get("session_id").getString();
        bindApprovalObserver(socket, sessionId);
        bindRuntimeSource(sessionId);
        ConversationEventSink eventSink =
                shouldStreamSlashCommand(normalized)
                        ? new TerminalUiWebSocketEventSink(socket, true)
                        : ConversationEventSink.noop();
        return runSlash(sessionId, normalized, eventSink);
    }

    /**
     * 判断 slash.exec 是否需要把后续运行事件推回 TUI，审批命令恢复运行后必须可见。
     *
     * @param command 已去掉斜杠的命令正文。
     * @return 如果命令可能恢复被挂起运行则返回 true。
     */
    private boolean shouldStreamSlashCommand(String command) {
        String text =
                StrUtil.nullToEmpty(command)
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT);
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String first = text.split("\\s+", 2)[0];
        return "approve".equals(first) || "deny".equals(first) || "cancel".equals(first);
    }

    /** 通过统一 CliRuntime 执行 slash 命令并转换为 RPC 输出。 */
    private Map<String, Object> runSlash(String sessionId, String command) throws Exception {
        return runSlash(sessionId, command, ConversationEventSink.noop());
    }

    /** 将终端 UI 审批弹层选择转换为真实后端 /approve 或 /deny 流程。 */
    private Map<String, Object> approvalRespond(WebSocket socket, ONode params) throws Exception {
        String sessionId = params.get("session_id").getString();
        String choice =
                StrUtil.nullToEmpty(params.get("choice").getString())
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT);
        if (StrUtil.isBlank(choice) && params.get("approved").getBoolean()) {
            choice = "once";
        }
        if (StrUtil.isBlank(sessionId)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("output", "");
            result.put("ok", Boolean.FALSE);
            result.put("warning", "missing_session_id");
            return result;
        }
        bindRuntimeSource(sessionId);
        String command;
        if ("deny".equals(choice)) {
            command = "deny";
        } else if ("always".equals(choice)) {
            command = "approve always";
        } else if ("session".equals(choice)) {
            command = "approve session";
        } else {
            command = "approve";
        }
        String selector = StrUtil.nullToEmpty(params.get("approval_id").getString()).trim();
        if (StrUtil.isNotBlank(selector)) {
            command = appendApprovalSelector(command, selector);
        }
        Map<String, Object> result =
                runSlash(
                        sessionId,
                        command,
                        new TerminalUiWebSocketEventSink(socket, true));
        result.put("ok", Boolean.valueOf(!result.containsKey("warning")));
        return result;
    }

    /** 将 TUI 审批卡片携带的安全选择器插入 /approve 或 /deny 命令。 */
    private static String appendApprovalSelector(String command, String selector) {
        String normalized = StrUtil.nullToEmpty(command).trim();
        String id = StrUtil.nullToEmpty(selector).trim();
        if (StrUtil.isBlank(id)) {
            return normalized;
        }
        if ("deny".equals(normalized)) {
            return "deny " + id;
        }
        if (normalized.startsWith("approve ")) {
            String scope = normalized.substring("approve ".length()).trim();
            if ("session".equals(scope) || "always".equals(scope)) {
                return "approve " + id + " " + scope;
            }
        }
        return normalized + " " + id;
    }

    /**
     * 通过统一 CliRuntime 执行 slash 命令并转换为 RPC 输出，同时把长运行事件推给终端 UI。
     *
     * @param sessionId 当前会话标识。
     * @param command 命令正文。
     * @param eventSink 事件Sink参数。
     * @return 返回终端 UI RPC 输出。
     */
    private Map<String, Object> runSlash(
            String sessionId, String command, ConversationEventSink eventSink) throws Exception {
        String normalized = StrUtil.nullToEmpty(command).trim();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (StrUtil.isBlank(normalized)) {
            result.put("output", "");
            return result;
        }
        String line = normalized.startsWith("/") ? normalized : "/" + normalized;
        if (setupCommands != null && setupCommands.isSetupCommand(line)) {
            result.put("output", setupCommands.render(line));
            return result;
        }
        GatewayReply reply = runtime.send(sessionId, line, eventSink);
        result.put("output", reply == null ? "" : StrUtil.nullToEmpty(reply.getContent()));
        if (reply != null && reply.isError()) {
            result.put("warning", reply.getContent());
        }
        if (reply != null && reply.getSessionId() != null) {
            result.put("session_id", reply.getSessionId());
        }
        if ("undo".equals(normalized)) {
            result.put("removed", reply != null && !reply.isError() ? Integer.valueOf(2) : Integer.valueOf(0));
        }
        return result;
    }

    /** 审批响应会复用 slash 命令链，执行前需要确保 TUI 会话 ID 可被 CliRuntime 找到。 */
    private void bindRuntimeSource(String sessionId) throws Exception {
        if (sessionRepository == null || StrUtil.isBlank(sessionId)) {
            return;
        }
        if (sessionRepository.findById(sessionId) == null) {
            return;
        }
        sessionRepository.bindSource(runtime.sourceKey(sessionId), sessionId);
    }

    /** 将当前 WebSocket 与活动会话绑定，用于只向本 TUI 推送对应会话的审批弹层。 */
    private void bindApprovalObserver(WebSocket socket, String sessionId) {
        TerminalUiApprovalObserver observer = approvalObservers.get(socket);
        if (observer != null) {
            observer.bindSession(sessionId);
        }
    }

    /**
     * 会话生命周期 RPC 成功后同步当前 TUI socket 的审批观察器，避免恢复会话后首次安全策略审批
     * 无法投递到前端弹层。
     */
    @SuppressWarnings("unchecked")
    private void bindApprovalObserverAfterSessionRpc(WebSocket socket, String method, Object result) {
        if (!"session.create".equals(method) && !"session.activate".equals(method) && !"session.resume".equals(method)) {
            return;
        }
        if (!(result instanceof Map)) {
            return;
        }
        Object sessionId = ((Map<String, Object>) result).get("session_id");
        bindApprovalObserver(socket, sessionId == null ? "" : String.valueOf(sessionId));
    }

    /** 处理终端 UI 命令分发兜底，优先复用 Java 后端统一命令服务。 */
    private Map<String, Object> commandDispatch(ONode params) throws Exception {
        String name = StrUtil.nullToEmpty(params.get("name").getString()).trim();
        String arg = StrUtil.nullToEmpty(params.get("arg").getString()).trim();
        String sessionId = params.get("session_id").getString();
        String command = name + (StrUtil.isBlank(arg) ? "" : " " + arg);
        Map<String, Object> executed = runSlash(sessionId, command);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "exec");
        result.put("output", executed.get("output"));
        return result;
    }

    /** 执行 TUI 的感叹号 shell 命令并返回原前端需要的 stdout/stderr/code。 */
    private Map<String, Object> shellExec(String command) throws Exception {
        String normalized = StrUtil.nullToEmpty(command).trim();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (StrUtil.isBlank(normalized)) {
            result.put("code", Integer.valueOf(0));
            result.put("stdout", "");
            result.put("stderr", "");
            return result;
        }

        ONode terminal = ONode.ofJson(
                shellSkill.terminal(
                        normalized,
                        Boolean.FALSE,
                        Integer.valueOf(30),
                        System.getProperty("user.dir"),
                        Boolean.FALSE));
        int exitCode = terminal.get("exit_code").getInt();
        String output = StrUtil.nullToEmpty(terminal.get("output").getString());
        String error = StrUtil.nullToEmpty(terminal.get("error").getString());
        result.put("code", Integer.valueOf(exitCode));
        result.put("stdout", output);
        result.put("stderr", error);
        return result;
    }

    /** 把 JSON 数组节点转换为字符串列表，忽略空项。 */
    private List<String> stringList(ONode node) {
        List<String> values = new ArrayList<String>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (ONode item : node.getArray()) {
            String value = item.getString();
            if (StrUtil.isNotBlank(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    /** 把 JSON 对象节点转换为字符串映射，供渠道 setup RPC 写入工作区配置。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(ONode node) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (node == null) {
            return values;
        }
        Object data = node.toData();
        if (!(data instanceof Map)) {
            return values;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) data).entrySet()) {
            values.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return values;
    }

    /** 读取终端 UI rollback RPC 的 checkpoint 标识。 */
    private String checkpointId(ONode params) {
        return params.get("hash").getString();
    }

    /** 生成可展示给 TUI 的错误摘要，避免空异常消息造成协议错误难以诊断。 */
    private String safeError(Throwable error) {
        if (error == null) {
            return "request failed";
        }
        String message = error.getMessage();
        if (StrUtil.isBlank(message)) {
            message = error.getClass().getSimpleName();
        }
        String type = error.getClass().getSimpleName();
        if (!message.contains(type)) {
            message = type + ": " + message;
        }
        return StrUtil.blankToDefault(message, "request failed");
    }

    /** 发送服务端到终端 UI 的 JSON 事件。 */
    private void send(WebSocket socket, String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("payload", payload);
        socket.send(ONode.serialize(event));
    }

    /** 发送 JSON-RPC 成功响应。 */
    private void sendRpcResult(WebSocket socket, String id, Object result) {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("result", result);
        socket.send(ONode.serialize(frame));
    }

    /** 发送 JSON-RPC 错误响应。 */
    private void sendRpcError(WebSocket socket, String id, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("message", message);
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("error", error);
        socket.send(ONode.serialize(frame));
    }

    /** 构造单字段事件载荷。 */
    private Map<String, Object> pair(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }

    /** 终端 UI 后台任务线程工厂，便于日志中识别 prompt.submit 执行位置。 */
    private static class TerminalUiThreadFactory implements ThreadFactory {
        /** 线程序号，用于给后台 prompt worker 生成稳定名称。 */
        private final AtomicInteger sequence = new AtomicInteger(1);

        /** 创建后台 prompt.submit 执行线程。 */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "terminal-ui-prompt-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
