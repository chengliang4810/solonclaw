package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalDimensionSupport;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.tui.TuiEnvelope;
import com.jimuqu.solon.claw.tui.TuiGatewayService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
