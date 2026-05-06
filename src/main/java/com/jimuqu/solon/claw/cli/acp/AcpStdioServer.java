package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Minimal ACP JSON-RPC stdio server. Stdout is reserved for protocol frames. */
public class AcpStdioServer {
    private final CliRuntime cliRuntime;
    private final AcpSessionManager sessionManager;
    private final DashboardMcpService mcpService;
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
        this(cliRuntime, sessionRepository, mcpService, System.in, System.out);
    }

    public AcpStdioServer(CliRuntime cliRuntime, InputStream input, OutputStream output) {
        this(cliRuntime, null, null, input, output);
    }

    public AcpStdioServer(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService mcpService,
            InputStream input,
            OutputStream output) {
        this.cliRuntime = cliRuntime;
        this.sessionManager = new AcpSessionManager(cliRuntime, sessionRepository);
        this.mcpService = mcpService;
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
            error.put("message", e.getMessage());
            response.put("error", error);
            return ONode.serialize(response);
        }
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
        result.put("capabilities", capabilities);
        result.put("agent_capabilities", capabilities);
        result.put("auth_methods", new ArrayList<Object>());
        result.put("commands", commands());
        return result;
    }

    private Map<String, Object> authenticate(ONode params) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", false);
        result.put("authenticated", false);
        result.put(
                "message",
                "No ACP auth method is required because jimuqu-agent uses local runtime credentials.");
        return result;
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
        return state == null ? null : sessionResult(state);
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
        return sessionResult(state);
    }

    private Map<String, Object> forkSession(ONode params) throws Exception {
        String cwd = read(params, "cwd", read(params, "working_directory", null));
        AcpSessionManager.AcpSessionState state =
                sessionManager.fork(readSessionId(params), cwd, readMcpServers(params));
        registerMcpServers(state);
        return sessionResult(state);
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
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        boolean afterCursor = StrUtil.isBlank(cursor);
        for (AcpSessionManager.AcpSessionState state : sessionManager.list()) {
            if (StrUtil.isNotBlank(cwd) && !cwd.equals(state.getCwd())) {
                continue;
            }
            if (!afterCursor) {
                afterCursor = cursor.equals(state.getSessionId());
                continue;
            }
            sessions.add(sessionResult(state));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessions);
        result.put("next_cursor", null);
        return result;
    }

    private Map<String, Object> prompt(ONode params) throws Exception {
        String sessionId = readSessionId(params);
        AcpSessionManager.AcpSessionState state = sessionManager.require(sessionId);
        state.setCancelled(false);
        String text = extractPromptText(params.get("prompt"));
        state.append("user", text);

        AcpEventSink sink = new AcpEventSink();
        GatewayReply reply = cliRuntime.send(state.getSessionId(), text, sink);
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
        if (StrUtil.isNotBlank(sink.reasoningText())) {
            result.put("thought", sink.reasoningText());
        }
        return result;
    }

    private Map<String, Object> sessionResult(AcpSessionManager.AcpSessionState state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("cwd", state.getCwd());
        result.put("updated_at", state.getUpdatedAt());
        result.put("updatedAt", state.getUpdatedAt());
        if (StrUtil.isNotBlank(state.getTitle())) {
            result.put("title", state.getTitle());
        }
        result.put("history", state.getHistory());
        result.put("models", new ArrayList<Object>());
        result.put(
                "source_key",
                StrUtil.blankToDefault(state.getSourceKey(), cliRuntime.sourceKey(state.getSessionId())));
        result.put("mcp_servers", state.getMcpServers());
        result.put("mcp_tool_count", state.getMcpToolCount());
        result.put("mcp_changed_servers", state.getMcpChangedServers());
        if (StrUtil.isNotBlank(state.getModelId())) {
            result.put("model_id", state.getModelId());
            result.put("modelId", state.getModelId());
        }
        if (StrUtil.isNotBlank(state.getModeId())) {
            result.put("mode_id", state.getModeId());
            result.put("modeId", state.getModeId());
        }
        if (!state.getConfigOptions().isEmpty()) {
            result.put("config_options", state.getConfigOptions());
            result.put("configOptions", state.getConfigOptions());
        }
        return result;
    }

    private Map<String, Object> setSessionModel(ONode params) throws Exception {
        AcpSessionManager.AcpSessionState state =
                sessionManager.require(readSessionId(params));
        String modelId = read(params, "model_id", read(params, "modelId", read(params, "model", "")));
        if (StrUtil.isBlank(modelId)) {
            throw new IllegalArgumentException("model_id is required");
        }
        state.setModelId(modelId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("session_id", state.getSessionId());
        result.put("sessionId", state.getSessionId());
        result.put("model_id", state.getModelId());
        result.put("modelId", state.getModelId());
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
        result.put("value", value);
        result.put("config_options", state.getConfigOptions());
        result.put("configOptions", state.getConfigOptions());
        return result;
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
        addCommand(commands, "compress", "立即压缩当前会话上下文");
        addCommand(commands, "reset", "重置当前会话");
        addCommand(commands, "skills", "管理本地技能与 Skills Hub");
        addCommand(commands, "reload-mcp", "重新加载 MCP 工具");
        addCommand(commands, "busy", "查看或切换运行中输入策略");
        addCommand(commands, "goal", "设置跨轮长目标并自动继续");
        addCommand(commands, "recap", "显示恢复会话用的紧凑历史摘要");
        addCommand(commands, "trajectory", "导出 Hermes-style trajectory JSON");
        return commands;
    }

    private void addCommand(List<Map<String, Object>> commands, String name, String description) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("description", description);
        commands.add(item);
    }

    private String extractPromptText(ONode prompt) {
        if (prompt == null || prompt.isNull()) {
            return "";
        }
        if (prompt.isArray()) {
            StringBuilder buffer = new StringBuilder();
            for (int i = 0; i < prompt.size(); i++) {
                ONode block = prompt.get(i);
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                String text = read(block, "text", read(block, "content", ""));
                if (StrUtil.isBlank(text) && block.isValue()) {
                    text = block.getString();
                }
                buffer.append(StrUtil.nullToEmpty(text));
            }
            return buffer.toString().trim();
        }
        if (prompt.isObject()) {
            return read(prompt, "text", read(prompt, "content", ""));
        }
        return StrUtil.nullToEmpty(prompt.getString());
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
        return value == null ? "" : String.valueOf(value).trim();
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
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", StrUtil.nullToEmpty(text));
        blocks.add(block);
        return blocks;
    }

    private String read(ONode node, String key, String fallback) {
        if (node == null || node.isNull() || !node.isObject()) {
            return fallback;
        }
        ONode value = node.get(key);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return StrUtil.blankToDefault(value.getString(), fallback);
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
