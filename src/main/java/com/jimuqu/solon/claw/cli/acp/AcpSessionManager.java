package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.chat.message.ChatMessage;

/** ACP session state backed by the normal session repository when available. */
public class AcpSessionManager {
    private static final int LIST_LIMIT = 100;

    private final CliRuntime cliRuntime;
    private final SessionRepository sessionRepository;
    private final Map<String, AcpSessionState> sessions =
            new LinkedHashMap<String, AcpSessionState>();

    public AcpSessionManager(CliRuntime cliRuntime, SessionRepository sessionRepository) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
    }

    public synchronized AcpSessionState create(String cwd) throws Exception {
        return create(cwd, new ArrayList<Object>());
    }

    public synchronized AcpSessionState create(String cwd, List<Object> mcpServers)
            throws Exception {
        SessionRecord record = null;
        if (sessionRepository != null) {
            long now = System.currentTimeMillis();
            record = new SessionRecord();
            record.setSessionId(IdSupport.newId());
            String sourceKey = cliRuntime.sourceKey(record.getSessionId());
            record.setSourceKey(sourceKey);
            record.setBranchName("main");
            record.setNdjson("");
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            sessionRepository.save(record);
            sessionRepository.bindSource(sourceKey, record.getSessionId());
        }
        AcpSessionState state =
                record == null
                        ? new AcpSessionState(
                                IdSupport.newId(),
                                StrUtil.blankToDefault(cwd, "."))
                        : fromRecord(record, cwd);
        state.setMcpServers(mcpServers);
        sessions.put(state.getSessionId(), state);
        return state;
    }

    public synchronized AcpSessionState get(String sessionId) throws Exception {
        AcpSessionState state = sessions.get(sessionId);
        if (state != null) {
            return state;
        }
        if (sessionRepository == null || StrUtil.isBlank(sessionId)) {
            return null;
        }
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            return null;
        }
        state = fromRecord(record, ".");
        sessions.put(state.getSessionId(), state);
        return state;
    }

    public synchronized AcpSessionState require(String sessionId) throws Exception {
        AcpSessionState state = get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("ACP session not found: " + sessionId);
        }
        return state;
    }

    public synchronized List<AcpSessionState> list() throws Exception {
        if (sessionRepository == null) {
            return new ArrayList<AcpSessionState>(sessions.values());
        }
        List<AcpSessionState> result = new ArrayList<AcpSessionState>();
        for (SessionRecord record : sessionRepository.listRecent(LIST_LIMIT)) {
            AcpSessionState cached = sessions.get(record.getSessionId());
            AcpSessionState state = cached == null ? fromRecord(record, ".") : cached;
            sessions.put(state.getSessionId(), state);
            result.add(state);
        }
        return result;
    }

    public synchronized AcpSessionState fork(String sessionId) throws Exception {
        return fork(sessionId, null, new ArrayList<Object>());
    }

    public synchronized AcpSessionState fork(String sessionId, String cwd, List<Object> mcpServers)
            throws Exception {
        AcpSessionState source = require(sessionId);
        SessionRecord clone = null;
        if (sessionRepository != null) {
            String sourceKey = cliRuntime.sourceKey(sessionId + ":fork:" + System.nanoTime());
            clone = sessionRepository.cloneSession(sourceKey, sessionId, "acp-fork");
            sourceKey = cliRuntime.sourceKey(clone.getSessionId());
            clone.setSourceKey(sourceKey);
            clone.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(clone);
            sessionRepository.bindSource(sourceKey, clone.getSessionId());
        }
        AcpSessionState fork =
                clone == null
                        ? new AcpSessionState(
                                IdSupport.newId(),
                                StrUtil.blankToDefault(cwd, source.getCwd()))
                        : fromRecord(clone, StrUtil.blankToDefault(cwd, source.getCwd()));
        if (clone == null) {
            fork.getHistory().addAll(source.getHistory());
        }
        fork.setMcpServers(
                mcpServers == null || mcpServers.isEmpty() ? source.getMcpServers() : mcpServers);
        sessions.put(fork.getSessionId(), fork);
        return fork;
    }

    public synchronized void refresh(AcpSessionState state) throws Exception {
        if (sessionRepository == null || state == null) {
            return;
        }
        SessionRecord record = sessionRepository.findById(state.getSessionId());
        if (record == null) {
            return;
        }
        state.setUpdatedAt(record.getUpdatedAt());
        state.setHistory(history(record));
    }

    private AcpSessionState fromRecord(SessionRecord record, String cwd) throws Exception {
        AcpSessionState state =
                new AcpSessionState(
                        record.getSessionId(), StrUtil.blankToDefault(cwd, "."));
        state.setSourceKey(
                StrUtil.blankToDefault(
                        record.getSourceKey(), cliRuntime.sourceKey(record.getSessionId())));
        state.setTitle(record.getTitle());
        state.setUpdatedAt(record.getUpdatedAt());
        state.setHistory(history(record));
        return state;
    }

    private List<Map<String, Object>> history(SessionRecord record) throws Exception {
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        if (record == null || StrUtil.isBlank(record.getNdjson())) {
            return history;
        }
        List<ChatMessage> messages = MessageSupport.loadMessages(record.getNdjson());
        for (ChatMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", String.valueOf(message.getRole()).toLowerCase());
            item.put("content", StrUtil.nullToEmpty(message.getContent()));
            history.add(item);
        }
        return history;
    }

    public static class AcpSessionState {
        private final String sessionId;
        private String cwd;
        private String sourceKey;
        private String title;
        private final List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        private List<Object> mcpServers = new ArrayList<Object>();
        private int mcpToolCount;
        private List<String> mcpChangedServers = new ArrayList<String>();
        private String modelId;
        private String modeId;
        private Map<String, Object> configOptions = new LinkedHashMap<String, Object>();
        private long updatedAt = System.currentTimeMillis();
        private volatile boolean cancelled;

        private AcpSessionState(String sessionId, String cwd) {
            this.sessionId = sessionId;
            this.cwd = cwd;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getCwd() {
            return cwd;
        }

        public void setCwd(String cwd) {
            this.cwd = StrUtil.blankToDefault(cwd, ".");
            touch();
        }

        public List<Map<String, Object>> getHistory() {
            return history;
        }

        public void setHistory(List<Map<String, Object>> history) {
            this.history.clear();
            if (history != null) {
                this.history.addAll(history);
            }
        }

        public String getSourceKey() {
            return sourceKey;
        }

        public void setSourceKey(String sourceKey) {
            this.sourceKey = sourceKey;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public List<Object> getMcpServers() {
            return new ArrayList<Object>(mcpServers);
        }

        public void setMcpServers(List<Object> mcpServers) {
            this.mcpServers =
                    mcpServers == null
                            ? new ArrayList<Object>()
                            : new ArrayList<Object>(mcpServers);
            touch();
        }

        public int getMcpToolCount() {
            return mcpToolCount;
        }

        public void setMcpToolCount(int mcpToolCount) {
            this.mcpToolCount = mcpToolCount;
            touch();
        }

        public List<String> getMcpChangedServers() {
            return new ArrayList<String>(mcpChangedServers);
        }

        public void setMcpChangedServers(List<String> mcpChangedServers) {
            this.mcpChangedServers =
                    mcpChangedServers == null
                            ? new ArrayList<String>()
                            : new ArrayList<String>(mcpChangedServers);
            touch();
        }

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = StrUtil.nullToEmpty(modelId).trim();
            touch();
        }

        public String getModeId() {
            return modeId;
        }

        public void setModeId(String modeId) {
            this.modeId = StrUtil.nullToEmpty(modeId).trim();
            touch();
        }

        public Map<String, Object> getConfigOptions() {
            return new LinkedHashMap<String, Object>(configOptions);
        }

        public void setConfigOption(String key, Object value) {
            if (StrUtil.isBlank(key)) {
                return;
            }
            this.configOptions.put(key.trim(), value);
            touch();
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
            touch();
        }

        public void append(String role, String text) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", role);
            item.put("content", StrUtil.nullToEmpty(text));
            item.put("created_at", System.currentTimeMillis());
            history.add(item);
            touch();
        }

        private void touch() {
            updatedAt = System.currentTimeMillis();
        }
    }
}
