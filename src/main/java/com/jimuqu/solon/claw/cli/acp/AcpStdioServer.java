package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Minimal ACP JSON-RPC stdio server. Stdout is reserved for protocol frames. */
public class AcpStdioServer {
    private static final int MAX_ACP_RESOURCE_BYTES = 512 * 1024;

    private final CliRuntime cliRuntime;
    private final AcpSessionManager sessionManager;
    private final SessionRepository sessionRepository;
    private final DashboardMcpService mcpService;
    private final DangerousCommandApprovalService dangerousCommandApprovalService;
    private final AppConfig appConfig;
    private final LlmProviderService llmProviderService;
    private final InputStream input;
    private final OutputStream output;

    public AcpStdioServer(CliRuntime cliRuntime) {
        this(cliRuntime, null);
    }

    public AcpStdioServer(CliRuntime cliRuntime, SessionRepository sessionRepository) {
        this(cliRuntime, sessionRepository, null);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService) {
        this(cliRuntime, sessionRepository, mcpService, null, null, System.in, System.out);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService,
            DangerousCommandApprovalService dangerousCommandApprovalService) {
        this(
                cliRuntime,
                sessionRepository,
                mcpService,
                dangerousCommandApprovalService,
                null,
                System.in,
                System.out);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService,
            AppConfig appConfig) {
        this(cliRuntime, sessionRepository, mcpService, null, appConfig, System.in, System.out);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AppConfig appConfig) {
        this(
                cliRuntime,
                sessionRepository,
                mcpService,
                dangerousCommandApprovalService,
                appConfig,
                System.in,
                System.out);
    }

    public AcpStdioServer(CliRuntime cliRuntime, InputStream input, OutputStream output) {
        this(cliRuntime, null, null, null, null, input, output);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService,
            InputStream input,
            OutputStream output) {
        this(cliRuntime, sessionRepository, mcpService, null, null, input, output);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AppConfig appConfig,
            InputStream input,
            OutputStream output) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.sessionManager = new AcpSessionManager(cliRuntime, sessionRepository);
        this.mcpService = mcpService;
        this.dangerousCommandApprovalService = dangerousCommandApprovalService;
        this.appConfig = appConfig;
        this.llmProviderService = appConfig == null ? null : new LlmProviderService(appConfig);
        this.input = input;
        this.output = output;
    }

    public int run() throws Exception {
        System.err.println("Starting jimuqu-agent ACP adapter");
        BufferedInputStream buffered = new BufferedInputStream(input);
        while (true) {
            String frame;
            try {
                frame = readFrame(buffered);
            } catch (EOFException e) {
                break;
            }
            if (StrUtil.isBlank(frame)) {
                continue;
            }
            writeFrame(handle(frame));
        }
        return 0;
    }

    public String handle(String json) {
        Object id = null;
        try {
            ONode request = ONode.ofJson(json);
            String method = request.get("method").getString();
            id = request.get("id").toData();
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", dispatch(method, request.get("params")));
            return ONode.serialize(response);
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("code", -32603);
            error.put(
                    "message",
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            1000));
            response.put("error", error);
            return ONode.serialize(response);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> initialize = initialize();
        result.put("enabled", true);
        result.put("transport", "stdio");
        result.put("command", "java -jar jimuqu-agent.jar acp");
        result.put("protocol_version", initialize.get("protocol_version"));
        result.put("protocolVersion", initialize.get("protocolVersion"));
        result.put("agent", initialize.get("agent"));
        result.put("agent_info", initialize.get("agent_info"));
        result.put("capabilities", initialize.get("capabilities"));
        result.put("agent_capabilities", initialize.get("agent_capabilities"));
        result.put("auth_methods", initialize.get("auth_methods"));
        result.put("commands", initialize.get("commands"));
        result.put("methods", supportedMethods());
        return result;
    }

    private List<String> supportedMethods() {
        List<String> methods = new ArrayList<String>();
        methods.add("initialize");
        methods.add("authenticate");
        methods.add("session/new");
        methods.add("session/load");
        methods.add("session/resume");
        methods.add("session/list");
        methods.add("session/fork");
        methods.add("session/cancel");
        methods.add("session/set_model");
        methods.add("session/set_mode");
        methods.add("session/set_config_option");
        methods.add("session/prompt");
        methods.add("permissions/list_open");
        methods.add("permissions/respond");
        return methods;
    }

    private Object dispatch(String method, ONode params) throws Exception {
        if ("initialize".equals(method)) {
            return initialize();
        }
        if ("authenticate".equals(method)) {
            return authenticate(params);
        }
        if ("session/new".equals(method) || "new_session".equals(method)) {
            return newSession(params);
        }
        if ("session/load".equals(method) || "load_session".equals(method)) {
            return loadSession(params);
        }
        if ("session/resume".equals(method) || "resume_session".equals(method)) {
            return resumeSession(params);
        }
        if ("session/list".equals(method) || "list_sessions".equals(method)) {
            return listSessions(params);
        }
        if ("session/fork".equals(method) || "fork_session".equals(method)) {
            return forkSession(params);
        }
        if ("session/cancel".equals(method) || "cancel".equals(method)) {
            return cancel(params);
        }
        if ("set_session_model".equals(method) || "session/set_model".equals(method)) {
            return setSessionModel(params);
        }
        if ("set_session_mode".equals(method) || "session/set_mode".equals(method)) {
            return setSessionMode(params);
        }
        if ("set_config_option".equals(method) || "session/set_config_option".equals(method)) {
            return setConfigOption(params);
        }
        if ("session/prompt".equals(method) || "prompt".equals(method)) {
            return prompt(params);
        }
        if ("permissions/list_open".equals(method) || "permissions_list_open".equals(method)) {
            return permissionsListOpen(params);
        }
        if ("permissions/respond".equals(method) || "permissions_respond".equals(method)) {
            return permissionsRespond(params);
        }
        throw new IllegalArgumentException("Unsupported ACP method: " + method);
    }

    private Map<String, Object> initialize() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("protocol_version", 1);
        result.put("protocolVersion", 1);
        result.put("agent", implementation("jimuqu-agent", "Jimuqu Agent"));
        result.put("agent_info", implementation("jimuqu-agent", "Jimuqu Agent"));
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("load_session", true);
        capabilities.put("fork_session", true);
        capabilities.put("resume_session", true);
        capabilities.put("list_session", true);
        capabilities.put("slash_commands", true);
        capabilities.put("mcp_servers", true);
        capabilities.put("prompt_capabilities", promptCapabilities());
        capabilities.put("session_capabilities", sessionCapabilities());
        result.put("capabilities", capabilities);
        result.put("agent_capabilities", agentCapabilities(capabilities));
        result.put("auth_methods", authMethods());
        result.put("commands", commands());
        return result;
    }

    private Map<String, Object> agentCapabilities(Map<String, Object> flatCapabilities) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(flatCapabilities);
        result.put("prompt_capabilities", promptCapabilities());
        result.put("promptCapabilities", result.get("prompt_capabilities"));
        result.put("session_capabilities", sessionCapabilities());
        result.put("sessionCapabilities", result.get("session_capabilities"));
        return result;
    }

    private Map<String, Object> promptCapabilities() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("image", Boolean.TRUE);
        return result;
    }

    private Map<String, Object> sessionCapabilities() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("fork", new LinkedHashMap<String, Object>());
        result.put("list", new LinkedHashMap<String, Object>());
        result.put("resume", new LinkedHashMap<String, Object>());
        return result;
    }

    private Map<String, Object> authenticate(ONode params) {
        String provider = detectAuthProvider();
        String methodId = read(params, "method_id", read(params, "methodId", ""));
        if (StrUtil.isNotBlank(provider)
                && StrUtil.isNotBlank(methodId)
                && provider.equals(methodId.trim().toLowerCase())) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", true);
            result.put("authenticated", true);
            result.put("method_id", safeAcpText(provider));
            result.put("methodId", safeAcpText(provider));
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", false);
        result.put("authenticated", false);
        result.put(
                "message",
                "No ACP auth method is required because jimuqu-agent uses local runtime credentials.");
        return result;
    }

    private List<Map<String, Object>> authMethods() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        String provider = detectAuthProvider();
        if (StrUtil.isBlank(provider)) {
            return result;
        }
        Map<String, Object> method = new LinkedHashMap<String, Object>();
        method.put("id", safeAcpText(provider));
        method.put("name", safeAcpText(provider + " runtime credentials"));
        method.put(
                "description",
                safeAcpText(
                        "Authenticate jimuqu-agent using the currently configured "
                                + provider
                                + " runtime credentials."));
        result.add(method);
        return result;
    }

    private String detectAuthProvider() {
        if (appConfig == null || appConfig.getProviders() == null || llmProviderService == null) {
            return null;
        }
        String providerKey = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
        if (StrUtil.isBlank(providerKey)) {
            providerKey = StrUtil.nullToEmpty(appConfig.getLlm().getProvider()).trim();
        }
        if (StrUtil.isBlank(providerKey)) {
            return null;
        }
        try {
            LlmProviderService.ResolvedProvider resolved = llmProviderService.resolveProvider(providerKey, "");
            if (resolved != null && SecretValueGuard.hasUsableSecret(resolved.getApiKey())) {
                return StrUtil.nullToEmpty(resolved.getProviderKey()).trim().toLowerCase();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Map<String, Object> newSession(ONode params) throws Exception {
        String cwd = read(params, "cwd", read(params, "working_directory", "."));
        AcpSessionManager.AcpSessionState state =
                sessionManager.create(cwd, readMcpServers(params));
        registerMcpServers(state);
        return sessionResult(state);
    }

    private Map<String, Object> loadSession(ONode params) throws Exception {
        String cwd = read(params, "cwd", read(params, "working_directory", null));
        AcpSessionManager.AcpSessionState state =
                sessionManager.get(readSessionId(params));
        if (state != null && StrUtil.isNotBlank(cwd)) {
            state.setCwd(cwd);
            state.setMcpServers(readMcpServers(params));
            registerMcpServers(state);
        }
        return state == null ? null : sessionResultWithUpdates(state, true);
    }

    private Map<String, Object> resumeSession(ONode params) throws Exception {
        String sessionId = readSessionId(params);
        AcpSessionManager.AcpSessionState state = sessionManager.get(sessionId);
        if (state == null) {
            String cwd = read(params, "cwd", read(params, "working_directory", "."));
            state = sessionManager.create(cwd, readMcpServers(params));
        } else {
            String cwd = read(params, "cwd", read(params, "working_directory", null));
            if (StrUtil.isNotBlank(cwd)) {
                state.setCwd(cwd);
            }
            state.setMcpServers(readMcpServers(params));
        }
        registerMcpServers(state);
        return sessionResultWithUpdates(state, true);
    }

    private Map<String, Object> forkSession(ONode params) throws Exception {
        String cwd = read(params, "cwd", read(params, "working_directory", null));
        AcpSessionManager.AcpSessionState state =
                sessionManager.fork(readSessionId(params), cwd, readMcpServers(params));
        registerMcpServers(state);
        return sessionResultWithUpdates(state, true);
    }

    private Map<String, Object> cancel(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        state.setCancelled(true);
        AgentRunStopResult stopResult = cliRuntime.stop(state.getSessionId());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("session_id", state.getSessionId());
        result.put("active_run", stopResult.isActiveRun());
        result.put("interrupt_sent", stopResult.isInterruptSent());
        result.put("run_id", stopResult.getRunId());
        return result;
    }

    private Map<String, Object> listSessions(ONode params) throws Exception {
        String cursor = read(params, "cursor", null);
        String cwd = read(params, "cwd", read(params, "working_directory", null));
        int limit = readInt(params, "limit", 50);
        if (limit <= 0) {
            limit = 50;
        }
        limit = Math.min(limit, 100);
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        boolean afterCursor = StrUtil.isBlank(cursor);
        String nextCursor = null;
        for (AcpSessionManager.AcpSessionState state : sessionManager.list()) {
            if (StrUtil.isNotBlank(cwd) && !cwd.equals(state.getCwd())) {
                continue;
            }
            if (!afterCursor) {
                afterCursor = cursor.equals(state.getSessionId());
                continue;
            }
            if (sessions.size() >= limit) {
                nextCursor = sessions.isEmpty()
                        ? state.getSessionId()
                        : String.valueOf(sessions.get(sessions.size() - 1).get("session_id"));
                break;
            }
            sessions.add(sessionResult(state));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessions);
        result.put("next_cursor", nextCursor);
        result.put("nextCursor", nextCursor);
        return result;
    }

    private Map<String, Object> prompt(ONode params) throws Exception {
        String sessionId = readSessionId(params);
        AcpSessionManager.AcpSessionState state = sessionManager.require(sessionId);
        state.setCancelled(false);
        String text = extractPromptText(params.get("prompt"));
        List<MessageAttachment> attachments = extractPromptAttachments(params.get("prompt"));
        state.append("user", text);

        AcpEventSink sink = new AcpEventSink();
        GatewayReply reply =
                cliRuntime.send(
                        state.getSessionId(),
                        text,
                        attachments,
                        sink,
                        existingCwdOrNull(state.getCwd()));
        String finalText = sink.assistantText();
        if (StrUtil.isBlank(finalText) && reply != null) {
            finalText = StrUtil.nullToEmpty(reply.getContent());
        }
        if (sink.isFailed()) {
            finalText = StrUtil.blankToDefault(sink.getError(), "ACP prompt failed");
        }
        state.append("assistant", finalText);
        sessionManager.refresh(state);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("stop_reason", state.isCancelled() ? "cancelled" : "end_turn");
        result.put("stopReason", state.isCancelled() ? "cancelled" : "end_turn");
        result.put("message", message("assistant", finalText));
        result.put("content", contentBlocks(finalText));
        result.put("session_updates", promptUpdates(state, sink, reply));
        result.put("sessionUpdates", result.get("session_updates"));
        Map<String, Object> usage = usage(reply, state);
        if (!usage.isEmpty()) {
            result.put("usage", usage);
        }
        if (StrUtil.isNotBlank(sink.reasoningText())) {
            result.put("thought", sink.reasoningText());
        }
        return result;
    }

    private Map<String, Object> sessionResult(AcpSessionManager.AcpSessionState state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("cwd", safeAcpPathRef(state.getCwd()));
        result.put("updated_at", state.getUpdatedAt());
        result.put("updatedAt", state.getUpdatedAt());
        if (StrUtil.isNotBlank(state.getTitle())) {
            result.put("title", safeAcpText(state.getTitle()));
        }
        result.put("history", safeAcpList(state.getHistory()));
        result.put("models", modelState(state));
        result.put(
                "source_key",
                safeAcpText(
                        StrUtil.blankToDefault(
                                state.getSourceKey(), cliRuntime.sourceKey(state.getSessionId()))));
        result.put("mcp_servers", safeAcpList(state.getMcpServers()));
        result.put("mcp_tool_count", state.getMcpToolCount());
        result.put("mcp_changed_servers", safeAcpList(state.getMcpChangedServers()));
        if (StrUtil.isNotBlank(state.getModelId())) {
            result.put("model_id", safeAcpText(state.getModelId()));
            result.put("modelId", safeAcpText(state.getModelId()));
        }
        if (StrUtil.isNotBlank(state.getModeId())) {
            result.put("mode_id", safeAcpText(state.getModeId()));
            result.put("modeId", safeAcpText(state.getModeId()));
        }
        if (!state.getConfigOptions().isEmpty()) {
            result.put("config_options", safeAcpMap(state.getConfigOptions()));
            result.put("configOptions", safeAcpMap(state.getConfigOptions()));
        }
        return result;
    }

    private Map<String, Object> sessionResultWithUpdates(
            AcpSessionManager.AcpSessionState state, boolean includeHistory) throws Exception {
        Map<String, Object> result = sessionResult(state);
        List<Map<String, Object>> updates = sessionLifecycleUpdates(state, includeHistory);
        result.put("session_updates", updates);
        result.put("sessionUpdates", updates);
        return result;
    }

    private Map<String, Object> modelState(AcpSessionManager.AcpSessionState state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> availableModels = new ArrayList<Map<String, Object>>();
        String currentModelId = "";
        if (appConfig == null || appConfig.getProviders() == null || appConfig.getProviders().isEmpty()) {
            result.put("available_models", availableModels);
            result.put("availableModels", availableModels);
            result.put("current_model_id", currentModelId);
            result.put("currentModelId", currentModelId);
            return result;
        }

        String providerKey = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
        String model = "";
        if (state != null && StrUtil.isNotBlank(state.getModelId())) {
            String[] parsed = parseModelChoice(state.getModelId(), providerKey);
            providerKey = parsed[0];
            model = parsed[1];
        }
        if (StrUtil.isBlank(providerKey)) {
            providerKey = StrUtil.nullToEmpty(appConfig.getLlm().getProvider()).trim();
        }

        LlmProviderService.ResolvedProvider current = null;
        try {
            current = llmProviderService == null ? null : llmProviderService.resolveProvider(providerKey, model);
        } catch (Exception e) {
            current = null;
        }
        if (current != null) {
            currentModelId = modelChoiceId(current.getProviderKey(), current.getModel());
            addModelInfo(availableModels, current, true);
        }

        addFallbackModelInfos(availableModels, currentModelId);
        result.put("available_models", availableModels);
        result.put("availableModels", availableModels);
        result.put("current_model_id", safeAcpText(currentModelId));
        result.put("currentModelId", safeAcpText(currentModelId));
        return result;
    }

    private void addFallbackModelInfos(List<Map<String, Object>> availableModels, String currentModelId) {
        if (llmProviderService == null || appConfig.getFallbackProviders() == null) {
            return;
        }
        for (AppConfig.FallbackProviderConfig fallback : appConfig.getFallbackProviders()) {
            if (fallback == null || StrUtil.isBlank(fallback.getProvider())) {
                continue;
            }
            try {
                LlmProviderService.ResolvedProvider resolved =
                        llmProviderService.resolveProvider(fallback.getProvider(), fallback.getModel());
                String modelId = modelChoiceId(resolved.getProviderKey(), resolved.getModel());
                addModelInfo(availableModels, resolved, modelId.equals(currentModelId));
            } catch (Exception ignored) {
                // Runtime provider validation handles broken fallback entries elsewhere.
            }
        }
    }

    private void addModelInfo(
            List<Map<String, Object>> availableModels,
            LlmProviderService.ResolvedProvider provider,
            boolean current) {
        String modelId = modelChoiceId(provider.getProviderKey(), provider.getModel());
        if (StrUtil.isBlank(modelId) || hasModelInfo(availableModels, modelId)) {
            return;
        }
        Map<String, Object> modelInfo = new LinkedHashMap<String, Object>();
        modelInfo.put("model_id", safeAcpText(modelId));
        modelInfo.put("modelId", safeAcpText(modelId));
        modelInfo.put("name", safeAcpText(StrUtil.blankToDefault(provider.getModel(), modelId)));
        modelInfo.put(
                "description",
                safeAcpText(
                        "Provider: "
                                + StrUtil.blankToDefault(provider.getLabel(), provider.getProviderKey())
                                + (current ? " - current" : "")));
        availableModels.add(modelInfo);
    }

    private boolean hasModelInfo(List<Map<String, Object>> availableModels, String modelId) {
        for (Map<String, Object> item : availableModels) {
            if (modelId.equals(item.get("model_id")) || modelId.equals(item.get("modelId"))) {
                return true;
            }
        }
        return false;
    }

    private String modelChoiceId(String providerKey, String model) {
        String normalizedModel = StrUtil.nullToEmpty(model).trim();
        if (StrUtil.isBlank(normalizedModel)) {
            return "";
        }
        String normalizedProvider = StrUtil.nullToEmpty(providerKey).trim().toLowerCase();
        return StrUtil.isBlank(normalizedProvider) ? normalizedModel : normalizedProvider + ":" + normalizedModel;
    }

    private String[] parseModelChoice(String rawModel, String currentProvider) {
        String model = StrUtil.nullToEmpty(rawModel).trim();
        String provider = StrUtil.nullToEmpty(currentProvider).trim();
        if (model.contains(":")) {
            String[] parts = model.split(":", 2);
            if (StrUtil.isNotBlank(parts[0]) && StrUtil.isNotBlank(parts[1])) {
                provider = parts[0].trim().toLowerCase();
                model = parts[1].trim();
            }
        }
        return new String[] {provider, model};
    }

    private Map<String, Object> setSessionModel(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        String modelId = read(params, "model_id", read(params, "modelId", read(params, "model", "")));
        if (StrUtil.isBlank(modelId)) {
            throw new IllegalArgumentException("model_id is required");
        }
        sessionManager.setModelOverride(state, modelId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("model_id", safeAcpText(state.getModelId()));
        result.put("modelId", safeAcpText(state.getModelId()));
        return result;
    }

    private Map<String, Object> setSessionMode(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        String modeId = read(params, "mode_id", read(params, "modeId", read(params, "mode", "")));
        if (StrUtil.isBlank(modeId)) {
            throw new IllegalArgumentException("mode_id is required");
        }
        state.setModeId(modeId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("mode_id", state.getModeId());
        result.put("modeId", state.getModeId());
        return result;
    }

    private Map<String, Object> setConfigOption(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        String configId =
                read(params, "config_id", read(params, "configId", read(params, "key", "")));
        if (StrUtil.isBlank(configId)) {
            throw new IllegalArgumentException("config_id is required");
        }
        ONode valueNode = params == null ? null : params.get("value");
        Object value = valueNode == null || valueNode.isNull() ? null : valueNode.toData();
        state.setConfigOption(configId, value);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("config_id", configId.trim());
        result.put("configId", configId.trim());
        result.put("value", safeAcpValue(value));
        result.put("config_options", safeAcpMap(state.getConfigOptions()));
        result.put("configOptions", safeAcpMap(state.getConfigOptions()));
        return result;
    }

    private Map<String, Object> permissionsListOpen(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        List<DangerousCommandApprovalService.PendingApproval> pending =
                dangerousCommandApprovalService == null
                        ? new ArrayList<DangerousCommandApprovalService.PendingApproval>()
                        : dangerousCommandApprovalService.listPendingApprovals(agentSession(state));

        List<Map<String, Object>> permissions = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < pending.size(); i++) {
            permissions.add(permissionItem(pending.get(i), i + 1));
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("permissions", permissions);
        result.put("pending", permissions);
        result.put("count", permissions.size());
        return result;
    }

    private Map<String, Object> permissionsRespond(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        String selector =
                read(
                        params,
                        "id",
                        read(params, "permission_id", read(params, "approval_id", read(params, "approvalId", ""))));
        String outcome = permissionOutcome(params);
        String command = approvalCommand(selector, outcome);
        GatewayReply reply =
                cliRuntime.send(
                        state.getSessionId(),
                        command,
                        null,
                        ConversationEventSink.noop(),
                        existingCwdOrNull(state.getCwd()));
        sessionManager.refresh(state);
        String safeSelector = DangerousCommandApprovalService.safeApprovalSelectorToken(selector);
        if (safeSelector == null) {
            safeSelector = "__invalid_selector__";
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", reply != null && !reply.isError());
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("id", safeAcpText(safeSelector));
        result.put("outcome", normalizedPermissionOutcome(outcome));
        result.put("message", safeAcpText(reply == null ? "" : StrUtil.nullToEmpty(reply.getContent())));
        result.put("content", contentBlocks(reply == null ? "" : reply.getContent()));
        return result;
    }

    private Map<String, Object> permissionItem(
            DangerousCommandApprovalService.PendingApproval pending, int index) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String selector = DangerousCommandApprovalService.approvalSelector(pending);
        item.put("id", safePermissionPreview(selector, 160));
        item.put("approval_id", safePermissionPreview(selector, 160));
        item.put("approvalId", safePermissionPreview(selector, 160));
        item.put("index", Integer.valueOf(index));
        item.put("tool_name", safePermissionPreview(pending.getToolName(), 160));
        item.put("toolName", safePermissionPreview(pending.getToolName(), 160));
        item.put("command", safePermissionPreview(pending.getCommand(), 3000));
        item.put("description", safePermissionPreview(pending.getDescription(), 1000));
        item.put("pattern_key", safePermissionPreview(pending.getPatternKey(), 400));
        item.put("patternKey", safePermissionPreview(pending.getPatternKey(), 400));
        item.put("pattern_keys", safePermissionList(pending.effectivePatternKeys(), 400));
        item.put("patternKeys", safePermissionList(pending.effectivePatternKeys(), 400));
        String redactedApprovalKey = redactedApprovalKey(pending.approvalKey());
        item.put("approval_key", redactedApprovalKey);
        item.put("approvalKey", redactedApprovalKey);
        item.put("created_at", Long.valueOf(pending.getCreatedAt()));
        item.put("createdAt", Long.valueOf(pending.getCreatedAt()));
        item.put("expires_at", Long.valueOf(pending.getExpiresAt()));
        item.put("expiresAt", Long.valueOf(pending.getExpiresAt()));
        item.put("expires_in_seconds", Long.valueOf(expiresInSeconds(pending.getExpiresAt())));
        item.put("expiresInSeconds", Long.valueOf(expiresInSeconds(pending.getExpiresAt())));
        item.put("expired", Boolean.valueOf(isExpired(pending.getExpiresAt())));
        item.put("scope_options", scopeOptions(pending));
        item.put("scopeOptions", scopeOptions(pending));
        item.put("permanent_allowed", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        item.put("permanentAllowed", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        item.put("options", permissionOptions(pending));
        return item;
    }

    private String safePermissionPreview(String value, int maxLength) {
        return StrUtil.nullToEmpty(SecretRedactor.redact(value, maxLength));
    }

    private List<String> safePermissionList(List<String> values, int maxLength) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                result.add(safePermissionPreview(value, maxLength));
            }
        }
        return result;
    }

    private String redactedApprovalKey(String approvalKey) {
        String redacted = SecretRedactor.redact(StrUtil.nullToEmpty(approvalKey), 1000);
        int split = redacted.lastIndexOf(':');
        if (split >= 0 && split < redacted.length() - 1) {
            return redacted.substring(0, split + 1) + "***";
        }
        return redacted;
    }

    private long expiresInSeconds(long expiresAt) {
        if (expiresAt <= 0L) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    private boolean isExpired(long expiresAt) {
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

    private List<String> scopeOptions(DangerousCommandApprovalService.PendingApproval pending) {
        List<String> scopes = new ArrayList<String>();
        scopes.add("once");
        scopes.add("session");
        if (pending == null || pending.isPermanentApprovalAllowed()) {
            scopes.add("always");
        }
        return scopes;
    }

    private List<Map<String, Object>> permissionOptions(
            DangerousCommandApprovalService.PendingApproval pending) {
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        addPermissionOption(options, "allow_once", "allow_once", "Allow once");
        addPermissionOption(options, "allow_session", "allow_session", "Allow for session");
        if (pending == null || pending.isPermanentApprovalAllowed()) {
            addPermissionOption(options, "allow_always", "allow_always", "Allow always");
        }
        addPermissionOption(options, "deny", "reject_once", "Deny");
        return options;
    }

    private void addPermissionOption(
            List<Map<String, Object>> options, String id, String kind, String name) {
        Map<String, Object> option = new LinkedHashMap<String, Object>();
        option.put("id", id);
        option.put("option_id", id);
        option.put("optionId", id);
        option.put("kind", kind);
        option.put("name", name);
        options.add(option);
    }

    private String approvalCommand(String selector, String outcome) {
        String normalized = normalizedPermissionOutcome(outcome);
        String target =
                DangerousCommandApprovalService.safeApprovalSelectorToken(selector);
        if (target == null) {
            return "deny".equals(normalized) ? "/deny __invalid_selector__" : "/approve __invalid_selector__";
        }
        if ("deny".equals(normalized)) {
            return StrUtil.isBlank(target) ? "/deny" : "/deny " + target;
        }
        if ("always".equals(normalized)) {
            return StrUtil.isBlank(target) ? "/approve always" : "/approve " + target + " always";
        }
        if ("session".equals(normalized)) {
            return StrUtil.isBlank(target) ? "/approve session" : "/approve " + target + " session";
        }
        return StrUtil.isBlank(target) ? "/approve" : "/approve " + target;
    }

    private String normalizedPermissionOutcome(String outcome) {
        String value = StrUtil.nullToEmpty(outcome).trim().toLowerCase();
        if ("allow_always".equals(value) || "always".equals(value)) {
            return "always";
        }
        if ("allow_session".equals(value) || "session".equals(value)) {
            return "session";
        }
        if ("allow_once".equals(value) || "allow".equals(value) || "once".equals(value)) {
            return "once";
        }
        return "deny";
    }

    private String permissionOutcome(ONode params) {
        String optionId =
                StrUtil.nullToEmpty(read(params, "option_id", read(params, "optionId", "")))
                        .trim()
                        .toLowerCase();
        String value =
                StrUtil.nullToEmpty(
                                read(
                                        params,
                                        "outcome",
                                        read(params, "choice", read(params, "decision", ""))))
                        .trim()
                        .toLowerCase();
        if ("selected".equals(value)) {
            if ("allow_once".equals(optionId)
                    || "allow_session".equals(optionId)
                    || "allow_always".equals(optionId)
                    || "allow".equals(optionId)
                    || "once".equals(optionId)
                    || "session".equals(optionId)
                    || "always".equals(optionId)) {
                return optionId;
            }
            return "allow_once";
        }
        if (StrUtil.isNotBlank(optionId) && StrUtil.isBlank(value)) {
            return optionId;
        }
        return value;
    }

    private SqliteAgentSession agentSession(AcpSessionManager.AcpSessionState state)
            throws Exception {
        if (sessionRepository == null) {
            throw new IllegalStateException("ACP session repository is required for permissions");
        }
        SessionRecord record = sessionRepository.findById(state.getSessionId());
        if (record == null) {
            throw new IllegalArgumentException("ACP session not found: " + state.getSessionId());
        }
        return new SqliteAgentSession(record, sessionRepository);
    }

    private Map<String, Object> implementation(String name, String title) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("title", title);
        result.put("version", "0");
        return result;
    }

    private List<Map<String, Object>> commands() {
        List<Map<String, Object>> commands = new ArrayList<Map<String, Object>>();
        addCommand(commands, "help", "显示帮助");
        addCommand(commands, "status", "查看当前会话状态");
        addCommand(commands, "model", "查看或切换模型");
        addCommand(commands, "tools", "查看或管理工具开关");
        addCommand(commands, "context", "查看上下文与压缩状态");
        addCommand(commands, "compact", "立即压缩当前会话上下文");
        addCommand(commands, "reset", "重置当前会话");
        addCommand(commands, "skills", "管理本地技能与 Skills Hub");
        addCommand(commands, "reload-mcp", "重新加载 MCP 工具");
        addCommand(commands, "acp", "查看 ACP 本地适配器能力快照");
        addCommand(commands, "busy", "查看或切换运行中输入策略");
        addCommand(commands, "steer", "向运行中任务注入修正或引导");
        addCommand(commands, "queue", "将提示排到当前任务之后执行");
        addCommand(commands, "kanban", "管理协作看板、任务抽屉、执行流水和多 Agent 派发");
        addCommand(commands, "restart", "等待运行中任务 drain 后重启网关");
        addCommand(commands, "version", "查看版本信息");
        addCommand(commands, "goal", "设置跨轮长目标并自动继续");
        addCommand(commands, "recap", "显示恢复会话用的紧凑历史摘要");
        addCommand(commands, "trajectory", "导出会话 trajectory JSON");
        return commands;
    }

    private void addCommand(List<Map<String, Object>> commands, String name, String description) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("description", description);
        if ("model".equals(name)) {
            item.put("input", unstructuredInput("模型 ID，例如 openai:gpt-5.1"));
        } else if ("busy".equals(name)) {
            item.put("input", unstructuredInput("status、queue、steer、interrupt 或 reject"));
        } else if ("steer".equals(name)) {
            item.put("input", unstructuredInput("给运行中任务的修正或引导"));
        } else if ("queue".equals(name)) {
            item.put("input", unstructuredInput("下一轮要执行的提示"));
        } else if ("kanban".equals(name)) {
            item.put("input", unstructuredInput("list、create、schema、drawer、pipeline、retry、history、guide 或 dispatch"));
        } else if ("compact".equals(name)) {
            item.put("input", unstructuredInput("可选关注主题"));
        } else if ("goal".equals(name)) {
            item.put("input", unstructuredInput("长目标描述"));
        } else if ("acp".equals(name)) {
            item.put("input", unstructuredInput("status"));
        }
        commands.add(item);
    }

    private Map<String, Object> unstructuredInput(String hint) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("kind", "unstructured");
        input.put("hint", hint);
        return input;
    }

    private List<Map<String, Object>> promptUpdates(
            AcpSessionManager.AcpSessionState state, AcpEventSink sink, GatewayReply reply)
            throws Exception {
        List<Map<String, Object>> updates = sessionLifecycleUpdates(state, false);
        updates.addAll(sink.updates());
        Map<String, Object> usageUpdate = usageUpdate(reply, state);
        if (!usageUpdate.isEmpty()) {
            updates.add(usageUpdate);
        }
        return updates;
    }

    private List<Map<String, Object>> sessionLifecycleUpdates(
            AcpSessionManager.AcpSessionState state, boolean includeHistory) throws Exception {
        List<Map<String, Object>> updates = new ArrayList<Map<String, Object>>();
        Map<String, Object> commandsUpdate = new LinkedHashMap<String, Object>();
        commandsUpdate.put("session_update", "available_commands_update");
        commandsUpdate.put("type", "available_commands_update");
        commandsUpdate.put("available_commands", commands());
        commandsUpdate.put("availableCommands", commandsUpdate.get("available_commands"));
        updates.add(commandsUpdate);
        if (includeHistory && state != null) {
            updates.addAll(historyReplayUpdates(state));
        }
        Map<String, Object> usageUpdate = usageUpdate(null, state);
        if (!usageUpdate.isEmpty()) {
            updates.add(usageUpdate);
        }
        return updates;
    }

    private List<Map<String, Object>> historyReplayUpdates(AcpSessionManager.AcpSessionState state) {
        List<Map<String, Object>> updates = new ArrayList<Map<String, Object>>();
        if (state == null) {
            return updates;
        }
        Map<String, Map<String, Object>> activeToolCalls = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> message : state.getHistory()) {
            if (message == null) {
                continue;
            }
            String role = StrUtil.nullToEmpty(String.valueOf(message.get("role"))).trim().toLowerCase();
            if ("user".equals(role) || "assistant".equals(role)) {
                String text = historyMessageText(message.get("content"));
                if (StrUtil.isNotBlank(text)) {
                    Map<String, Object> update = new LinkedHashMap<String, Object>();
                    update.put(
                            "session_update",
                            "user".equals(role) ? "user_message_chunk" : "agent_message_chunk");
                    update.put("type", update.get("session_update"));
                    update.put("content", textBlock(safeAcpText(text)));
                    updates.add(update);
                }
            }
            if ("assistant".equals(role)) {
                updates.addAll(historyToolStartUpdates(message.get("tool_calls"), activeToolCalls));
            }
            if ("tool".equals(role)) {
                Map<String, Object> update = historyToolCompleteUpdate(message, activeToolCalls);
                if (update != null) {
                    updates.add(update);
                }
            }
        }
        return updates;
    }

    private List<Map<String, Object>> historyToolStartUpdates(
            Object rawToolCalls, Map<String, Map<String, Object>> activeToolCalls) {
        List<Map<String, Object>> updates = new ArrayList<Map<String, Object>>();
        if (!(rawToolCalls instanceof List)) {
            return updates;
        }
        for (Object raw : (List<?>) rawToolCalls) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<?, ?> toolCall = (Map<?, ?>) raw;
            String toolCallId = historyToolCallId(toolCall);
            if (StrUtil.isBlank(toolCallId)) {
                continue;
            }
            String toolName = historyToolCallName(toolCall);
            Map<String, Object> args = historyToolCallArgs(toolCall);
            Map<String, Object> active = new LinkedHashMap<String, Object>();
            active.put("tool_name", toolName);
            active.put("args", args);
            activeToolCalls.put(toolCallId, active);
            updates.add(toolStartUpdate(toolCallId, toolName, args));
        }
        return updates;
    }

    private Map<String, Object> historyToolCompleteUpdate(
            Map<String, Object> message, Map<String, Map<String, Object>> activeToolCalls) {
        String toolCallId = StrUtil.nullToEmpty(String.valueOf(message.get("tool_call_id"))).trim();
        String toolName = StrUtil.nullToEmpty(String.valueOf(message.get("tool_name"))).trim();
        Map<String, Object> active = StrUtil.isBlank(toolCallId) ? null : activeToolCalls.remove(toolCallId);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        if (active != null) {
            toolName = StrUtil.blankToDefault(toolName, String.valueOf(active.get("tool_name")));
            Object rawArgs = active.get("args");
            if (rawArgs instanceof Map) {
                args.putAll((Map<String, Object>) rawArgs);
            }
        }
        if (StrUtil.isBlank(toolCallId) || StrUtil.isBlank(toolName)) {
            return null;
        }
        return toolCompleteUpdate(toolCallId, toolName, historyMessageText(message.get("content")), args, 0L);
    }

    private String historyToolCallId(Map<?, ?> toolCall) {
        if (toolCall == null) {
            return "";
        }
        Object id = toolCall.get("id");
        if (id == null) {
            id = toolCall.get("call_id");
        }
        if (id == null) {
            id = toolCall.get("tool_call_id");
        }
        return StrUtil.nullToEmpty(String.valueOf(id)).trim();
    }

    private String historyToolCallName(Map<?, ?> toolCall) {
        Object function = toolCall == null ? null : toolCall.get("function");
        if (function instanceof Map) {
            Object name = ((Map<?, ?>) function).get("name");
            if (name != null && StrUtil.isNotBlank(String.valueOf(name))) {
                return String.valueOf(name);
            }
        }
        Object name = toolCall == null ? null : toolCall.get("name");
        return StrUtil.blankToDefault(name == null ? "" : String.valueOf(name), "unknown_tool");
    }

    private Map<String, Object> historyToolCallArgs(Map<?, ?> toolCall) {
        Object function = toolCall == null ? null : toolCall.get("function");
        Object rawArgs = null;
        if (function instanceof Map) {
            rawArgs = ((Map<?, ?>) function).get("arguments");
        }
        if (rawArgs == null && toolCall != null) {
            rawArgs = toolCall.get("arguments");
        }
        if (rawArgs == null && toolCall != null) {
            rawArgs = toolCall.get("args");
        }
        return objectMap(rawArgs);
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (value instanceof String && StrUtil.isNotBlank((String) value)) {
            try {
                Object parsed = ONode.ofJson((String) value).toData();
                if (parsed instanceof Map) {
                    return objectMap(parsed);
                }
            } catch (Exception e) {
                result.put("raw", value);
            }
        }
        return result;
    }

    private Map<String, Object> toolStartUpdate(
            String toolCallId, String toolName, Map<String, Object> args) {
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("session_update", "tool_call_start");
        update.put("type", "tool_call_start");
        update.put("tool_call_id", toolCallId);
        update.put("toolCallId", toolCallId);
        update.put("tool_name", StrUtil.blankToDefault(toolName, "tool"));
        update.put("toolName", StrUtil.blankToDefault(toolName, "tool"));
        update.put("kind", toolKind(toolName));
        update.put("title", toolTitle(toolName, args));
        update.put("args", safeAcpMap(args));
        return update;
    }

    private Map<String, Object> toolCompleteUpdate(
            String toolCallId,
            String toolName,
            String result,
            Map<String, Object> args,
            long durationMs) {
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("session_update", "tool_call_update");
        update.put("type", "tool_call_update");
        update.put("tool_call_id", toolCallId);
        update.put("toolCallId", toolCallId);
        update.put("tool_name", StrUtil.blankToDefault(toolName, "tool"));
        update.put("toolName", StrUtil.blankToDefault(toolName, "tool"));
        update.put("kind", toolKind(toolName));
        update.put("status", "completed");
        update.put("duration_ms", Long.valueOf(durationMs));
        update.put("durationMs", Long.valueOf(durationMs));
        update.put("content", textBlock(truncate(safeAcpText(result), 5000)));
        if (args != null && !args.isEmpty()) {
            update.put("args", safeAcpMap(args));
        }
        return update;
    }

    private String toolKind(String toolName) {
        String name = StrUtil.nullToEmpty(toolName);
        if ("read_file".equals(name) || "skill_view".equals(name) || "skills_list".equals(name)) {
            return "read";
        }
        if ("write_file".equals(name) || "patch".equals(name) || "skill_manage".equals(name)) {
            return "edit";
        }
        if ("search_files".equals(name) || "session_search".equals(name)) {
            return "search";
        }
        if ("terminal".equals(name)
                || "execute_shell".equals(name)
                || "process".equals(name)
                || "execute_code".equals(name)
                || "delegate_task".equals(name)) {
            return "execute";
        }
        if ("web_search".equals(name) || "web_extract".equals(name)) {
            return "fetch";
        }
        return "other";
    }

    private String toolTitle(String toolName, Map<String, Object> args) {
        String name = StrUtil.blankToDefault(toolName, "tool");
        Object command = args == null ? null : args.get("command");
        Object path = args == null ? null : args.get("path");
        Object query = args == null ? null : args.get("query");
        if (command != null && StrUtil.isNotBlank(String.valueOf(command))) {
            return name + ": " + truncate(safeAcpText(String.valueOf(command)), 100);
        }
        if (path != null && StrUtil.isNotBlank(String.valueOf(path))) {
            return name + ": " + safeAcpPathRef(String.valueOf(path));
        }
        if (query != null && StrUtil.isNotBlank(String.valueOf(query))) {
            return name + ": " + truncate(safeAcpText(String.valueOf(query)), 100);
        }
        return name;
    }

    private Map<String, Object> safeAcpMap(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (values == null || values.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            result.put(entry.getKey(), safeAcpValue(entry.getValue()));
        }
        return result;
    }

    private List<Object> safeAcpList(List<?> values) {
        List<Object> result = new ArrayList<Object>();
        if (values == null || values.isEmpty()) {
            return result;
        }
        for (Object value : values) {
            result.add(safeAcpValue(value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object safeAcpValue(Object value) {
        if (value instanceof String) {
            return safeAcpText((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> nested = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    nested.put(String.valueOf(entry.getKey()), safeAcpValue(entry.getValue()));
                }
            }
            return nested;
        }
        if (value instanceof List) {
            List<Object> nested = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                nested.add(safeAcpValue(item));
            }
            return nested;
        }
        return value;
    }

    private String safeAcpText(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 8000);
    }

    private String safeAcpPathRef(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (StrUtil.isBlank(text) || ".".equals(text)) {
            return StrUtil.blankToDefault(text, ".");
        }
        String name = new File(text).getName();
        if (StrUtil.isBlank(name)) {
            name = "cwd";
        }
        return "path://" + SecretRedactor.redact(name, 400);
    }

    private String existingCwdOrNull(String cwd) {
        if (StrUtil.isBlank(cwd)) {
            return null;
        }
        File dir = new File(cwd.trim());
        return dir.exists() && dir.isDirectory() ? dir.getAbsolutePath() : null;
    }

    private String historyMessageText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof List) {
            StringBuilder builder = new StringBuilder();
            List<?> values = (List<?>) content;
            for (Object value : values) {
                String part = historyMessageText(value);
                if (StrUtil.isBlank(part)) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(part.trim());
            }
            return builder.toString();
        }
        if (content instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) content;
            Object text = map.get("text");
            if (text == null) {
                text = map.get("content");
            }
            return historyMessageText(text);
        }
        return StrUtil.nullToEmpty(String.valueOf(content)).trim();
    }

    private Map<String, Object> usageUpdate(GatewayReply reply, AcpSessionManager.AcpSessionState state)
            throws Exception {
        Map<String, Object> usage = usage(reply, state);
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        if (usage.isEmpty()) {
            return update;
        }
        update.put("session_update", "usage_update");
        update.put("type", "usage_update");
        long used =
                Math.max(
                        longValue(usage.get("context_estimate_tokens")),
                        longValue(usage.get("total_tokens")));
        long size = longValue(usage.get("context_window_tokens"));
        update.put("used", Long.valueOf(used));
        update.put("size", Long.valueOf(size > 0L ? size : Math.max(used, 0L)));
        update.put("usage", usage);
        return update;
    }

    private Map<String, Object> usage(GatewayReply reply, AcpSessionManager.AcpSessionState state)
            throws Exception {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        Map<String, Object> runtime = reply == null ? null : reply.getRuntimeMetadata();
        putLong(usage, "input_tokens", runtimeValue(runtime, "input_tokens"));
        putLong(usage, "output_tokens", runtimeValue(runtime, "output_tokens"));
        putLong(usage, "total_tokens", runtimeValue(runtime, "total_tokens"));
        putLong(usage, "thought_tokens", runtimeValue(runtime, "reasoning_tokens"));
        putLong(usage, "cached_read_tokens", runtimeValue(runtime, "cache_read_tokens"));
        putLong(usage, "cached_write_tokens", runtimeValue(runtime, "cache_write_tokens"));
        putLong(usage, "context_estimate_tokens", runtimeValue(runtime, "context_estimate_tokens"));
        putLong(usage, "context_window_tokens", runtimeValue(runtime, "context_window_tokens"));
        if (sessionRepository != null && state != null) {
            SessionRecord record = sessionRepository.findById(state.getSessionId());
            if (record != null) {
                putLongIfMissing(usage, "input_tokens", Long.valueOf(record.getLastInputTokens()));
                putLongIfMissing(usage, "output_tokens", Long.valueOf(record.getLastOutputTokens()));
                putLongIfMissing(usage, "total_tokens", Long.valueOf(record.getLastTotalTokens()));
                putLongIfMissing(usage, "thought_tokens", Long.valueOf(record.getLastReasoningTokens()));
                putLongIfMissing(usage, "cached_read_tokens", Long.valueOf(record.getLastCacheReadTokens()));
                putLongIfMissing(usage, "cached_write_tokens", Long.valueOf(record.getLastCacheWriteTokens()));
            }
        }
        return usage;
    }

    private Object runtimeValue(Map<String, Object> runtime, String key) {
        if (runtime == null || runtime.isEmpty()) {
            return null;
        }
        Object value = runtime.get(key);
        if (value == null) {
            value = runtime.get(camelCase(key));
        }
        return value;
    }

    private String camelCase(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '_') {
                upper = true;
                continue;
            }
            result.append(upper ? Character.toUpperCase(ch) : ch);
            upper = false;
        }
        return result.toString();
    }

    private void putLong(Map<String, Object> usage, String key, Object value) {
        long number = longValue(value);
        if (number > 0L) {
            usage.put(key, Long.valueOf(number));
        }
    }

    private void putLongIfMissing(Map<String, Object> usage, String key, Object value) {
        if (usage.containsKey(key)) {
            return;
        }
        putLong(usage, key, value);
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private String extractPromptText(ONode prompt) {
        if (prompt == null || prompt.isNull()) {
            return "";
        }
        if (prompt.isArray()) {
            StringBuilder buffer = new StringBuilder();
            for (int i = 0; i < prompt.size(); i++) {
                appendPromptPart(buffer, promptBlockText(prompt.get(i)));
            }
            return buffer.toString().trim();
        }
        if (prompt.isObject()) {
            return promptBlockText(prompt);
        }
        return StrUtil.nullToEmpty(prompt.getString());
    }

    private List<MessageAttachment> extractPromptAttachments(ONode prompt) {
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        if (prompt == null || prompt.isNull()) {
            return attachments;
        }
        if (prompt.isArray()) {
            for (int i = 0; i < prompt.size(); i++) {
                appendPromptAttachment(attachments, prompt.get(i));
            }
            return attachments;
        }
        if (prompt.isObject()) {
            appendPromptAttachment(attachments, prompt);
        }
        return attachments;
    }

    private void appendPromptAttachment(List<MessageAttachment> attachments, ONode block) {
        if (block == null || block.isNull() || !block.isObject()) {
            return;
        }
        String type = read(block, "type", "");
        if ("resource_link".equals(type)) {
            appendResourceLinkAttachment(attachments, block);
        } else if ("resource".equals(type)) {
            appendEmbeddedResourceAttachment(attachments, block);
        } else if ("image".equals(type)) {
            appendDirectImageAttachment(attachments, block);
        }
    }

    private void appendResourceLinkAttachment(List<MessageAttachment> attachments, ONode block) {
        String uri = read(block, "uri", "");
        if (StrUtil.isBlank(uri)) {
            return;
        }
        String name = read(block, "name", "");
        String title = read(block, "title", "");
        String mimeType = readMimeType(block);
        Path path = localResourcePath(uri);
        if (path == null) {
            return;
        }
        String imageMime = StrUtil.blankToDefault(imageMime(mimeType), imageMimeFromPath(path));
        if (StrUtil.isBlank(imageMime)) {
            return;
        }
        try {
            long size = Files.size(path);
            if (size > MAX_ACP_RESOURCE_BYTES) {
                return;
            }
            byte[] data = readFilePrefix(path, (int) size);
            MessageAttachment attachment = imageAttachment(uri, name, title, imageMime);
            attachment.setLocalPath(path.toAbsolutePath().toString());
            attachment.setData(Base64.getEncoder().encodeToString(data));
            attachments.add(attachment);
        } catch (Exception ignored) {
        }
    }

    private void appendEmbeddedResourceAttachment(List<MessageAttachment> attachments, ONode block) {
        ONode resource = block.get("resource");
        if (resource == null || resource.isNull() || !resource.isObject()) {
            return;
        }
        String mimeType = readMimeType(resource);
        String imageMime = imageMime(mimeType);
        if (StrUtil.isBlank(imageMime)) {
            return;
        }
        String blob = read(resource, "blob", read(resource, "data", ""));
        if (StrUtil.isBlank(blob)) {
            return;
        }
        byte[] data = decodeBase64OrUtf8(blob);
        if (data.length > MAX_ACP_RESOURCE_BYTES) {
            return;
        }
        String uri = read(resource, "uri", "");
        MessageAttachment attachment =
                imageAttachment(uri, read(resource, "name", ""), read(resource, "title", ""), imageMime);
        attachment.setData(Base64.getEncoder().encodeToString(data));
        attachments.add(attachment);
    }

    private void appendDirectImageAttachment(List<MessageAttachment> attachments, ONode block) {
        String mimeType = StrUtil.blankToDefault(readMimeType(block), "image/png");
        String imageMime = imageMime(mimeType);
        if (StrUtil.isBlank(imageMime)) {
            imageMime = "image/png";
        }
        String uri = read(block, "uri", "");
        String data = read(block, "data", "");
        if (StrUtil.isBlank(uri) && StrUtil.isBlank(data)) {
            return;
        }
        MessageAttachment attachment =
                imageAttachment(uri, read(block, "name", ""), read(block, "title", ""), imageMime);
        if (StrUtil.isNotBlank(data)) {
            byte[] bytes = decodeBase64OrUtf8(data);
            if (bytes.length > MAX_ACP_RESOURCE_BYTES) {
                return;
            }
            attachment.setData(Base64.getEncoder().encodeToString(bytes));
        } else {
            attachment.setUrl(uri);
        }
        attachments.add(attachment);
    }

    private MessageAttachment imageAttachment(String uri, String name, String title, String mimeType) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("image");
        attachment.setOriginalName(resourceDisplayName(uri, name, title));
        attachment.setMimeType(mimeType);
        return attachment;
    }

    private void appendPromptPart(StringBuilder buffer, String text) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(text.trim());
    }

    private String promptBlockText(ONode block) {
        if (block == null || block.isNull()) {
            return "";
        }
        if (block.isValue()) {
            return StrUtil.nullToEmpty(block.getString());
        }
        if (!block.isObject()) {
            return "";
        }
        String type = read(block, "type", "");
        if ("resource_link".equals(type)) {
            return resourceLinkText(block);
        }
        if ("resource".equals(type)) {
            return embeddedResourceText(block);
        }
        if ("image".equals(type)) {
            return directImageText(block);
        }
        return read(block, "text", read(block, "content", ""));
    }

    private String resourceLinkText(ONode block) {
        String uri = read(block, "uri", "");
        if (StrUtil.isBlank(uri)) {
            return "";
        }
        String name = read(block, "name", "");
        String title = read(block, "title", "");
        String mimeType = readMimeType(block);
        Path path = localResourcePath(uri);
        if (path == null) {
            return formatResourceText(
                    uri,
                    name,
                    title,
                    "[Resource link only; cannot read non-file ACP resource URI directly]");
        }
        String imageMime = StrUtil.blankToDefault(imageMime(mimeType), imageMimeFromPath(path));
        if (StrUtil.isNotBlank(imageMime)) {
            return imageResourceNote(uri, name, title, path, imageMime);
        }
        try {
            long size = Files.size(path);
            int readSize = (int) Math.min(size, (long) MAX_ACP_RESOURCE_BYTES);
            byte[] data = readFilePrefix(path, readSize);
            String text = decodeText(data, mimeType);
            if (text == null) {
                return formatResourceText(
                        uri,
                        name,
                        title,
                        "[Binary file omitted: " + size + " bytes, mime=" + displayMime(mimeType) + "]");
            }
            String body = text;
            if (size > MAX_ACP_RESOURCE_BYTES) {
                body = body + "\n\n[Truncated to " + MAX_ACP_RESOURCE_BYTES + " of " + size + " bytes]";
            }
            return formatResourceText(uri, name, title, body);
        } catch (Exception e) {
            return formatResourceText(
                    uri,
                    name,
                    title,
                    "[File read failed: "
                            + safeAcpResourceText(
                                    StrUtil.blankToDefault(
                                            e.getMessage(), e.getClass().getSimpleName()))
                            + "]");
        }
    }

    private String embeddedResourceText(ONode block) {
        ONode resource = block.get("resource");
        if (resource == null || resource.isNull() || !resource.isObject()) {
            return "";
        }
        String uri = read(resource, "uri", "");
        String name = read(resource, "name", "");
        String title = read(resource, "title", "");
        String mimeType = readMimeType(resource);
        String text = read(resource, "text", "");
        if (StrUtil.isNotBlank(text)) {
            return formatResourceText(uri, name, title, text);
        }
        String blob = read(resource, "blob", read(resource, "data", ""));
        if (StrUtil.isBlank(blob)) {
            return "";
        }
        byte[] data = decodeBase64OrUtf8(blob);
        String imageMime = imageMime(mimeType);
        if (StrUtil.isNotBlank(imageMime)) {
            if (data.length > MAX_ACP_RESOURCE_BYTES) {
                return formatResourceText(
                        uri,
                        name,
                        title,
                        "[Embedded image too large to inline: "
                                + data.length
                                + " bytes, cap="
                                + MAX_ACP_RESOURCE_BYTES
                                + "]");
            }
            return "[Attached image: " + resourceDisplayName(uri, name, title) + "]"
                    + (StrUtil.isBlank(uri) ? "" : "\nURI: " + safeAcpResourceText(uri))
                    + "\nMIME: "
                    + imageMime
                    + "\nBytes: "
                    + data.length;
        }
        int readSize = Math.min(data.length, MAX_ACP_RESOURCE_BYTES);
        byte[] prefix = new byte[readSize];
        System.arraycopy(data, 0, prefix, 0, readSize);
        String decoded = decodeText(prefix, mimeType);
        if (decoded == null) {
            return formatResourceText(
                    uri,
                    name,
                    title,
                    "[Binary embedded file omitted: "
                            + data.length
                            + " bytes, mime="
                            + displayMime(mimeType)
                            + "]");
        }
        if (data.length > MAX_ACP_RESOURCE_BYTES) {
            decoded =
                    decoded
                            + "\n\n[Truncated to "
                            + MAX_ACP_RESOURCE_BYTES
                            + " of "
                            + data.length
                            + " bytes]";
        }
        return formatResourceText(uri, name, title, decoded);
    }

    private String directImageText(ONode block) {
        String uri = read(block, "uri", "");
        String name = read(block, "name", "");
        String title = read(block, "title", "");
        String mimeType = StrUtil.blankToDefault(readMimeType(block), "image/png");
        String data = read(block, "data", "");
        int bytes = StrUtil.isBlank(data) ? 0 : decodeBase64OrUtf8(data).length;
        StringBuilder note = new StringBuilder();
        note.append("[Attached image: ").append(resourceDisplayName(uri, name, title)).append(']');
        if (StrUtil.isNotBlank(uri)) {
            note.append("\nURI: ").append(safeAcpResourceText(uri));
        }
        note.append("\nMIME: ").append(mimeType);
        if (bytes > 0) {
            note.append("\nBytes: ").append(bytes);
        }
        return note.toString();
    }

    private String imageResourceNote(
            String uri, String name, String title, Path path, String imageMime) {
        try {
            long size = Files.size(path);
            if (size > MAX_ACP_RESOURCE_BYTES) {
                return formatResourceText(
                        uri,
                        name,
                        title,
                        "[Image too large to inline: "
                                + size
                                + " bytes, cap="
                                + MAX_ACP_RESOURCE_BYTES
                                + "]");
            }
            return "[Attached image: "
                    + resourceDisplayName(uri, name, title)
                    + "]\nURI: "
                    + safeAcpResourceText(uri)
                    + "\nMIME: "
                    + imageMime
                    + "\nBytes: "
                    + size;
        } catch (Exception e) {
            return formatResourceText(
                    uri,
                    name,
                    title,
                    "[Image read failed: "
                            + safeAcpResourceText(
                                    StrUtil.blankToDefault(
                                            e.getMessage(), e.getClass().getSimpleName()))
                            + "]");
        }
    }

    private String formatResourceText(String uri, String name, String title, String body) {
        StringBuilder result = new StringBuilder();
        result.append("[Attached file: ").append(resourceDisplayName(uri, name, title)).append(']');
        if (StrUtil.isNotBlank(uri)) {
            result.append("\nURI: ").append(safeAcpResourceText(uri));
        }
        result.append("\n\n").append(StrUtil.nullToEmpty(body));
        return result.toString();
    }

    private String resourceDisplayName(String uri, String name, String title) {
        if (StrUtil.isNotBlank(title) && StrUtil.isNotBlank(name) && !title.trim().equals(name.trim())) {
            return safeAcpResourceText(title.trim()) + " (" + safeAcpResourceText(name.trim()) + ")";
        }
        if (StrUtil.isNotBlank(title)) {
            return safeAcpResourceText(title.trim());
        }
        if (StrUtil.isNotBlank(name)) {
            return safeAcpResourceText(name.trim());
        }
        Path path = localResourcePath(uri);
        if (path != null && path.getFileName() != null) {
            return safeAcpResourceText(path.getFileName().toString());
        }
        String value = StrUtil.nullToEmpty(uri).trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < value.length()) {
            return safeAcpResourceText(value.substring(slash + 1));
        }
        return safeAcpResourceText(StrUtil.blankToDefault(value, "attachment"));
    }

    private String safeAcpResourceText(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000);
    }

    private String readMimeType(ONode node) {
        return read(node, "mimeType", read(node, "mime_type", ""));
    }

    private String displayMime(String mimeType) {
        return StrUtil.blankToDefault(mimeType, "unknown");
    }

    private Path localResourcePath(String uri) {
        if (StrUtil.isBlank(uri)) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            if ("file".equalsIgnoreCase(parsed.getScheme())) {
                return Paths.get(parsed);
            }
            if (parsed.getScheme() != null) {
                return null;
            }
        } catch (Exception ignored) {
        }
        try {
            return Paths.get(uri);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readFilePrefix(Path path, int maxBytes) throws IOException {
        InputStream in = Files.newInputStream(path);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[Math.min(8192, Math.max(maxBytes, 1))];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = in.read(chunk, 0, Math.min(chunk.length, remaining));
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private String decodeText(byte[] data, String mimeType) {
        if (data == null) {
            return "";
        }
        if (!isTextMime(mimeType) && looksBinary(data)) {
            return null;
        }
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            if (isTextMime(mimeType)) {
                return new String(data, StandardCharsets.UTF_8);
            }
            return null;
        }
    }

    private boolean isTextMime(String mimeType) {
        String value = StrUtil.nullToEmpty(mimeType).trim().toLowerCase();
        if (value.startsWith("text/")) {
            return true;
        }
        return "application/json".equals(value)
                || "application/javascript".equals(value)
                || "application/typescript".equals(value)
                || "application/xml".equals(value)
                || "application/x-yaml".equals(value)
                || "application/yaml".equals(value)
                || "application/toml".equals(value)
                || "application/x-ndjson".equals(value);
    }

    private boolean looksBinary(byte[] data) {
        int limit = Math.min(data.length, 4096);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private byte[] decodeBase64OrUtf8(String value) {
        String data = StrUtil.nullToEmpty(value).trim();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma >= 0) {
            data = data.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(data);
        } catch (Exception e) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String imageMime(String mimeType) {
        String value = StrUtil.nullToEmpty(mimeType).trim().toLowerCase();
        return value.startsWith("image/") ? value : "";
    }

    private String imageMimeFromPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "";
    }

    private String readSessionId(ONode params) {
        return read(params, "session_id", read(params, "sessionId", ""));
    }

    private void registerMcpServers(AcpSessionManager.AcpSessionState state) throws Exception {
        if (mcpService == null || state == null || state.getMcpServers().isEmpty()) {
            return;
        }
        for (Object server : state.getMcpServers()) {
            Map<String, Object> body = mcpBody(server);
            if (body.isEmpty()) {
                continue;
            }
            mcpService.save(body);
        }
        DashboardMcpService.McpReloadResult reloadResult = mcpService.reloadAll();
        state.setMcpToolCount(reloadResult.getToolCount());
        state.setMcpChangedServers(reloadResult.getChangedServers());
    }

    private Map<String, Object> mcpBody(Object server) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (server instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) server;
            String name = stringValue(source.get("name"));
            String command = stringValue(source.get("command"));
            String url = StrUtil.blankToDefault(stringValue(source.get("url")), stringValue(source.get("endpoint")));
            String transport = stringValue(source.get("transport"));
            String serverId = StrUtil.blankToDefault(name, stableMcpId(command, url));
            body.put("serverId", serverId);
            body.put("name", StrUtil.blankToDefault(name, serverId));
            body.put(
                    "transport",
                    StrUtil.blankToDefault(
                            transport, StrUtil.isNotBlank(command) ? "stdio" : "http"));
            body.put("endpoint", url);
            body.put("command", command);
            body.put("args", source.get("args"));
            body.put("auth", source.get("auth"));
            body.put("oauth", source.get("oauth"));
            body.put("capabilities", source.get("capabilities"));
            body.put("tools", source.get("tools"));
            body.put("enabled", Boolean.TRUE);
            return body;
        }
        String name = stringValue(server);
        if (StrUtil.isBlank(name)) {
            return body;
        }
        body.put("serverId", name);
        body.put("name", name);
        body.put("transport", "stdio");
        body.put("command", name);
        body.put("enabled", Boolean.TRUE);
        return body;
    }

    private String stableMcpId(String command, String endpoint) {
        String source = StrUtil.blankToDefault(command, endpoint);
        if (StrUtil.isBlank(source)) {
            return "acp-mcp";
        }
        return "acp-" + source.replaceAll("[^A-Za-z0-9_.-]+", "-");
    }

    private String stringValue(Object value) {
        return value == null ? "" : SecretRedactor.stripDisplayControls(String.valueOf(value)).trim();
    }

    private List<Object> readMcpServers(ONode node) {
        return readObjectList(node, "mcp_servers");
    }

    private List<Object> readObjectList(ONode node, String key) {
        List<Object> values = new ArrayList<Object>();
        if (node == null || node.isNull() || !node.isObject()) {
            return values;
        }
        ONode value = node.get(key);
        if (value == null || value.isNull()) {
            return values;
        }
        if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                ONode item = value.get(i);
                Object data = item == null || item.isNull() ? null : item.toData();
                if (data != null) {
                    values.add(data);
                }
            }
            return values;
        }
        Object data = value.toData();
        if (data != null) {
            values.add(data);
        }
        return values;
    }

    private Map<String, Object> message(String role, String text) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("role", role);
        result.put("content", contentBlocks(text));
        return result;
    }

    private List<Map<String, Object>> contentBlocks(String text) {
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        blocks.add(textBlock(text));
        return blocks;
    }

    private Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", safeAcpText(text));
        return block;
    }

    private String truncate(String value, int limit) {
        String text = StrUtil.nullToEmpty(value);
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 40))
                + "\n... ("
                + text.length()
                + " chars total, truncated)";
    }

    private String read(ONode node, String key, String fallback) {
        if (node == null || node.isNull() || !node.isObject()) {
            return fallback;
        }
        ONode value = node.get(key);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return SecretRedactor.stripDisplayControls(
                StrUtil.blankToDefault(value.getString(), fallback));
    }

    private int readInt(ONode node, String key, int fallback) {
        if (node == null || node.isNull() || !node.isObject()) {
            return fallback;
        }
        ONode value = node.get(key);
        if (value == null || value.isNull()) {
            return fallback;
        }
        try {
            return value.getInt();
        } catch (Exception e) {
            try {
                return Integer.parseInt(value.getString());
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    private String readFrame(BufferedInputStream in) throws IOException {
        in.mark(1);
        int first = in.read();
        if (first < 0) {
            throw new EOFException();
        }
        in.reset();
        if (first == '{' || first == '[') {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException();
            }
            return line;
        }

        int contentLength = -1;
        while (true) {
            String header = readLine(in);
            if (header == null) {
                throw new EOFException();
            }
            if (header.length() == 0) {
                break;
            }
            int colon = header.indexOf(':');
            if (colon > 0
                    && "content-length".equalsIgnoreCase(header.substring(0, colon).trim())) {
                contentLength = Integer.parseInt(header.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IOException("Missing Content-Length header");
        }
        byte[] payload = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = in.read(payload, offset, contentLength - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) {
                return buffer.size() == 0 ? null : buffer.toString("UTF-8");
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        return buffer.toString("UTF-8");
    }

    private void writeFrame(String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + bytes.length + "\r\n\r\n";
        synchronized (output) {
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        }
    }
}
