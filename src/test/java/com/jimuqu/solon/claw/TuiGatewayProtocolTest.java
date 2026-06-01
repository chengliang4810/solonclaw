package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.cli.TerminalDimensionSupport;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.tui.TuiConnection;
import com.jimuqu.solon.claw.tui.TuiEnvelope;
import com.jimuqu.solon.claw.tui.TuiGatewayService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.websocket.WebSocket;

class TuiGatewayProtocolTest {
    @Test
    void shouldParseSessionIdFromCamelCaseEnvelope() {
        TuiEnvelope envelope =
                TuiEnvelope.parse(
                        "{\"id\":\"7\",\"method\":\"input.send\",\"sessionId\":\"s1\",\"params\":{\"input\":\"hello\",\"busy_mode\":\"queue\"}}");

        assertThat(envelope.getId()).isEqualTo("7");
        assertThat(envelope.getMethod()).isEqualTo("input.send");
        assertThat(envelope.getSessionId()).isEqualTo("s1");
        assertThat(envelope.getParams()).containsEntry("input", "hello");
        assertThat(envelope.getParams()).containsEntry("busy_mode", "queue");
    }

    @Test
    void shouldParseSessionIdFromSnakeCaseEnvelope() {
        TuiEnvelope envelope =
                TuiEnvelope.parse(
                        "{\"id\":\"8\",\"method\":\"session.resume\",\"session_id\":\"s2\",\"params\":{}}");

        assertThat(envelope.getId()).isEqualTo("8");
        assertThat(envelope.getMethod()).isEqualTo("session.resume");
        assertThat(envelope.getSessionId()).isEqualTo("s2");
    }

    @Test
    void shouldSanitizeTerminalResizeDimensions() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            TuiEnvelope envelope =
                    TuiEnvelope.parse(
                            "{\"id\":\"9\",\"method\":\"terminal.resize\",\"sessionId\":\"s1\",\"params\":{\"cols\":131072,\"rows\":99999}}");

            Map<String, Object> payload = resize(service, envelope);

            assertThat(payload)
                    .containsEntry("ok", Boolean.TRUE)
                    .containsEntry("cols", Integer.valueOf(TerminalDimensionSupport.MAX_COLUMNS))
                    .containsEntry("rows", Integer.valueOf(TerminalDimensionSupport.MAX_ROWS))
                    .containsEntry("sanitized", Boolean.TRUE);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldExposeClientContractOnSessionPayload() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            SessionRecord record = new SessionRecord();
            record.setSessionId("contract-session");
            record.setSourceKey("MEMORY:tui:contract-session");
            record.setBranchName("main");
            record.setTitle("contract check");
            record.setNdjson("");
            record.setCreatedAt(1700000000000L);
            record.setUpdatedAt(1700000001000L);

            Map<String, Object> payload = sessionPayload(service, record);

            assertThat(payload).containsEntry("client_contract", Integer.valueOf(1));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldBuildSessionStatusPayloadForTui() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            SessionRecord record = new SessionRecord();
            record.setSessionId("status-session");
            record.setSourceKey("MEMORY:tui:status-session");
            record.setBranchName("main");
            record.setTitle("Status check");
            record.setNdjson("");
            record.setModelOverride("fallback-model");
            record.setLastResolvedModel("chat-model");
            record.setLastResolvedProvider("local-provider");
            record.setCumulativeTotalTokens(12345L);
            record.setCreatedAt(1700000000000L);
            record.setUpdatedAt(1700000005000L);

            Map<String, Object> payload = sessionStatusPayload(service, record);

            assertThat(payload)
                    .containsEntry("session_id", "status-session")
                    .containsEntry("title", "Status check")
                    .containsEntry("model", "chat-model")
                    .containsEntry("provider", "local-provider")
                    .containsEntry("total_tokens", Long.valueOf(12345L))
                    .containsEntry("running", Boolean.FALSE)
                    .containsEntry("queued_count", Integer.valueOf(0))
                    .containsEntry("started_at", Long.valueOf(1700000000000L))
                    .containsEntry("last_active", Long.valueOf(1700000005000L));
            assertThat((String) payload.get("output"))
                    .contains("solon-claw TUI Status")
                    .contains("Session ID: status-session")
                    .contains("Title: Status check")
                    .contains("Model: chat-model (local-provider)")
                    .contains("Tokens: 12,345")
                    .contains("Agent Running: No");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldBuildSessionUsagePayloadForTui() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            SessionRecord record = new SessionRecord();
            record.setSessionId("usage-session");
            record.setCumulativeInputTokens(11L);
            record.setCumulativeOutputTokens(22L);
            record.setCumulativeReasoningTokens(33L);
            record.setCumulativeCacheReadTokens(44L);
            record.setCumulativeCacheWriteTokens(55L);
            record.setCumulativeTotalTokens(165L);
            record.setLastInputTokens(1L);
            record.setLastOutputTokens(2L);
            record.setLastReasoningTokens(3L);
            record.setLastCacheReadTokens(4L);
            record.setLastCacheWriteTokens(5L);
            record.setLastTotalTokens(15L);
            record.setLastUsageAt(1700000009000L);

            Map<String, Object> payload = sessionUsagePayload(service, record);

            assertThat(payload)
                    .containsEntry("session_id", "usage-session")
                    .containsEntry("calls", Integer.valueOf(1))
                    .containsEntry("calls_estimated", Boolean.TRUE)
                    .containsEntry("input", Long.valueOf(11L))
                    .containsEntry("output", Long.valueOf(22L))
                    .containsEntry("reasoning", Long.valueOf(33L))
                    .containsEntry("cache_read", Long.valueOf(44L))
                    .containsEntry("cache_write", Long.valueOf(55L))
                    .containsEntry("total", Long.valueOf(165L))
                    .containsEntry("input_tokens", Long.valueOf(11L))
                    .containsEntry("output_tokens", Long.valueOf(22L))
                    .containsEntry("reasoning_tokens", Long.valueOf(33L))
                    .containsEntry("cache_read_tokens", Long.valueOf(44L))
                    .containsEntry("cache_write_tokens", Long.valueOf(55L))
                    .containsEntry("total_tokens", Long.valueOf(165L))
                    .containsEntry("last_input_tokens", Long.valueOf(1L))
                    .containsEntry("last_output_tokens", Long.valueOf(2L))
                    .containsEntry("last_reasoning_tokens", Long.valueOf(3L))
                    .containsEntry("last_cache_read_tokens", Long.valueOf(4L))
                    .containsEntry("last_cache_write_tokens", Long.valueOf(5L))
                    .containsEntry("last_total_tokens", Long.valueOf(15L))
                    .containsEntry("last_usage_at", Long.valueOf(1700000009000L));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldResolveSessionIdFromRpcParams() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            TuiEnvelope envelope =
                    TuiEnvelope.parse(
                            "{\"id\":\"10\",\"method\":\"session.status\",\"params\":{\"session_id\":\"params-session\"}}");

            String sessionId = resolveSessionId(service, null, envelope);

            assertThat(sessionId).isEqualTo("params-session");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldResolveDeleteSessionIdFromRpcParams() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            TuiEnvelope envelope =
                    TuiEnvelope.parse(
                            "{\"id\":\"11\",\"method\":\"session.delete\",\"params\":{\"session_id\":\"delete-session\"}}");

            String sessionId = resolveTargetSessionId(service, envelope);

            assertThat(sessionId).isEqualTo("delete-session");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldRejectDeletingActiveTuiSession() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(
                        null,
                        new TerminalSessionBrowserTest.FakeSessionRepository(
                                Arrays.asList(session("active-session", "MEMORY:tui:active-session"))),
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
        try {
            TuiConnection connection = new TuiConnection(new FakeWebSocket("active-connection"));
            connection.setActiveSessionId("active-session");
            service.onOpen(connection);

            assertThatThrownBy(() -> deleteSession(service, "active-session"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot delete an active session");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldHideDelegateChildSessionsFromTuiSessionList() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            List<SessionRecord> filtered =
                    filterHumanSessions(
                            service,
                            Arrays.asList(
                                    session("parent", "MEMORY:room:user"),
                                    session("tool-room", "MEMORY:tool-room:user"),
                                    session("child", "MEMORY:room:user:delegate:abc123")));

            assertThat(filtered)
                    .extracting(SessionRecord::getSessionId)
                    .containsExactly("parent", "tool-room");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldReturnMostRecentHumanTuiSession() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            Map<String, Object> payload =
                    sessionMostRecent(
                            service,
                            Arrays.asList(
                                    session("child", "MEMORY:room:user:delegate:abc123"),
                                    session("human", "MEMORY:room:user")));

            assertThat(payload)
                    .containsEntry("session_id", "human")
                    .containsEntry("title", "human")
                    .containsEntry("source_key", "MEMORY:room:user");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldReturnNullMostRecentWhenOnlyDelegateSessionsExist() throws Exception {
        TuiGatewayService service =
                new TuiGatewayService(null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            Map<String, Object> payload =
                    sessionMostRecent(
                            service,
                            Arrays.asList(session("child", "MEMORY:room:user:delegate:abc123")));

            assertThat(payload).containsEntry("session_id", null);
        } finally {
            service.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnOnlyLiveActiveTuiSessionsInConnectionOrder() throws Exception {
        SessionRecord first = session("first-live", "MEMORY:tui:first-live");
        first.setTitle("First live");
        first.setCumulativeTotalTokens(7L);
        SessionRecord second = session("second-live", "MEMORY:tui:second-live");
        second.setTitle("Second live");
        second.setCumulativeTotalTokens(11L);
        TuiGatewayService service =
                new TuiGatewayService(
                        null,
                        new TerminalSessionBrowserTest.FakeSessionRepository(
                                Arrays.asList(
                                        first,
                                        session("stored-only", "MEMORY:tui:stored-only"),
                                        second)),
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
        try {
            FakeWebSocket firstSocket = new FakeWebSocket("connection-1");
            TuiConnection firstConnection = new TuiConnection(firstSocket);
            firstConnection.setActiveSessionId("first-live");
            TuiConnection inactiveConnection = new TuiConnection(new FakeWebSocket("connection-2"));
            TuiConnection secondConnection = new TuiConnection(new FakeWebSocket("connection-3"));
            secondConnection.setActiveSessionId("second-live");
            TuiConnection duplicateConnection = new TuiConnection(new FakeWebSocket("connection-4"));
            duplicateConnection.setActiveSessionId("first-live");
            service.onOpen(firstConnection);
            service.onOpen(inactiveConnection);
            service.onOpen(secondConnection);
            service.onOpen(duplicateConnection);

            TuiEnvelope envelope =
                    TuiEnvelope.parse(
                            "{\"id\":\"12\",\"method\":\"session.active_list\",\"params\":{\"current_session_id\":\"second-live\"}}");
            service.handle(firstConnection, envelope);

            Map<String, Object> result = firstSocket.lastSentMap();
            assertThat(result).containsEntry("type", "rpc.result").containsEntry("id", "12");
            List<Map<String, Object>> sessions =
                    (List<Map<String, Object>>) ((Map<String, Object>) result.get("payload")).get("sessions");
            assertThat(sessions)
                    .extracting(item -> item.get("session_id"))
                    .containsExactly("first-live", "second-live");
            assertThat(sessions.get(0))
                    .containsEntry("id", "first-live")
                    .containsEntry("title", "First live")
                    .containsEntry("current", Boolean.FALSE)
                    .containsEntry("running", Boolean.FALSE)
                    .containsEntry("queued_count", Integer.valueOf(0))
                    .containsEntry("message_count", Integer.valueOf(0))
                    .containsEntry("client_contract", Integer.valueOf(1))
                    .containsEntry("status", "idle");
            assertThat(sessions.get(0)).containsKeys("started_at", "last_active");
            assertThat(((Number) sessions.get(0).get("total_tokens")).longValue()).isEqualTo(7L);
            assertThat(sessions.get(1))
                    .containsEntry("id", "second-live")
                    .containsEntry("title", "Second live")
                    .containsEntry("current", Boolean.TRUE)
                    .containsKeys(
                            "running",
                            "queued_count",
                            "started_at",
                            "last_active",
                            "message_count",
                            "client_contract",
                            "status");
            assertThat(((Number) sessions.get(1).get("total_tokens")).longValue()).isEqualTo(11L);
        } finally {
            service.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resize(TuiGatewayService service, TuiEnvelope envelope)
            throws Exception {
        Method method = TuiGatewayService.class.getDeclaredMethod("resize", TuiEnvelope.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, envelope);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionPayload(TuiGatewayService service, SessionRecord record)
            throws Exception {
        Method method = TuiGatewayService.class.getDeclaredMethod("sessionPayload", SessionRecord.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, record);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionStatusPayload(TuiGatewayService service, SessionRecord record)
            throws Exception {
        Method method =
                TuiGatewayService.class.getDeclaredMethod(
                        "sessionStatusPayload", SessionRecord.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, record);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionUsagePayload(TuiGatewayService service, SessionRecord record)
            throws Exception {
        Method method =
                TuiGatewayService.class.getDeclaredMethod(
                        "sessionUsagePayload", SessionRecord.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, record);
    }

    private String resolveSessionId(TuiGatewayService service, Object connection, TuiEnvelope envelope)
            throws Exception {
        Method method =
                TuiGatewayService.class.getDeclaredMethod(
                        "resolveSessionId",
                        com.jimuqu.solon.claw.tui.TuiConnection.class,
                        TuiEnvelope.class);
        method.setAccessible(true);
        return (String) method.invoke(service, connection, envelope);
    }

    private String resolveTargetSessionId(TuiGatewayService service, TuiEnvelope envelope)
            throws Exception {
        Method method =
                TuiGatewayService.class.getDeclaredMethod("resolveTargetSessionId", TuiEnvelope.class);
        method.setAccessible(true);
        return (String) method.invoke(service, envelope);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deleteSession(
            TuiGatewayService service, String sessionId) throws Exception {
        Method method =
                TuiGatewayService.class.getDeclaredMethod("deleteSession", String.class);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(service, sessionId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<SessionRecord> filterHumanSessions(
            TuiGatewayService service, List<SessionRecord> records) throws Exception {
        Method method = TuiGatewayService.class.getDeclaredMethod("filterHumanSessions", List.class);
        method.setAccessible(true);
        return (List<SessionRecord>) method.invoke(service, records);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionMostRecent(
            TuiGatewayService service, List<SessionRecord> records) throws Exception {
        Method method = TuiGatewayService.class.getDeclaredMethod("sessionMostRecent", List.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, records);
    }

    private SessionRecord session(String id, String sourceKey) {
        SessionRecord record = new SessionRecord();
        record.setSessionId(id);
        record.setSourceKey(sourceKey);
        record.setBranchName("main");
        record.setTitle(id);
        record.setNdjson("");
        record.setCreatedAt(1700000000000L);
        record.setUpdatedAt(1700000001000L);
        return record;
    }

    private static class FakeWebSocket implements WebSocket {
        private final String id;
        private final List<String> sent = new ArrayList<String>();

        FakeWebSocket(String id) {
            this.id = id;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> lastSentMap() {
            assertThat(sent).isNotEmpty();
            return (Map<String, Object>) ONode.deserialize(sent.get(sent.size() - 1), Map.class);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return "";
        }

        @Override
        public void nameAs(String name) {}

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String url() {
            return "";
        }

        @Override
        public String path() {
            return "";
        }

        @Override
        public void pathNew(String path) {}

        @Override
        public MultiMap<String> paramMap() {
            return new MultiMap<String>();
        }

        @Override
        public String param(String name) {
            return null;
        }

        @Override
        public String paramOrDefault(String name, String def) {
            return def;
        }

        @Override
        public void param(String name, String value) {}

        @Override
        public InetSocketAddress remoteAddress() {
            return null;
        }

        @Override
        public InetSocketAddress localAddress() {
            return null;
        }

        @Override
        public Map<String, Object> attrMap() {
            return Collections.emptyMap();
        }

        @Override
        public boolean attrHas(String name) {
            return false;
        }

        @Override
        public <T> T attr(String name) {
            return null;
        }

        @Override
        public <T> T attrOrDefault(String name, T def) {
            return def;
        }

        @Override
        public <T> void attr(String name, T value) {}

        @Override
        public long getIdleTimeout() {
            return 0L;
        }

        @Override
        public void setIdleTimeout(long timeout) {}

        @Override
        public Future<Void> send(String text) {
            sent.add(text);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Future<Void> send(ByteBuffer binary) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {}

        @Override
        public void close(int code, String reason) {}
    }
}
