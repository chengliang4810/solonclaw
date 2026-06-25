package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.tool.runtime.ApprovalAuditObserver;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ApprovalAuditObserverTest {
    @Test
    void shouldRedactSecretsFromApprovalAuditDescription() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        service.addApprovalObserver(new ApprovalAuditObserver(repository));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-1");
        SqliteAgentSession session = new SqliteAgentSession(record);

        service.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "Security scan saw token=ghp_auditsecret123 and password=audit-password",
                "rm -rf workspace/cache --token ghp_commandsecret123");

        assertThat(repository.events).hasSize(1);
        ApprovalAuditEvent event = repository.events.get(0);
        assertThat(event.getDescription()).doesNotContain("ghp_auditsecret123");
        assertThat(event.getDescription()).doesNotContain("audit-password");
        assertThat(event.getDescription()).contains("token=***").contains("password=***");
        assertThat(event.getCommandPreview()).doesNotContain("ghp_commandsecret123");
    }

    @Test
    void shouldRedactEncodedSecretsFromApprovalAuditEvent() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        service.addApprovalObserver(new ApprovalAuditObserver(repository));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-encoded-audit");
        SqliteAgentSession session = new SqliteAgentSession(record);

        service.storePendingApproval(
                session,
                "execute_shell",
                "url_policy?api%255Fkey=audit-encoded-secret",
                "encoded audit https://example.test/callback?api%255Fkey=audit-encoded-secret",
                "curl https://example.test/callback?api%255Fkey=audit-encoded-secret");

        assertThat(repository.events).hasSize(1);
        ApprovalAuditEvent event = repository.events.get(0);
        assertThat(event.getCommandPreview())
                .contains("api%255Fkey=***")
                .doesNotContain("audit-encoded-secret");
        assertThat(event.getDescription())
                .contains("api%255Fkey=***")
                .doesNotContain("audit-encoded-secret");
        assertThat(event.getPatternKeysJson())
                .contains("api%255Fkey=***")
                .doesNotContain("audit-encoded-secret");
        assertThat(event.getApprovalKey())
                .contains("api%255Fkey=***")
                .doesNotContain("audit-encoded-secret");
    }

    @Test
    void shouldRedactSecretsFromApprovalAuditApprover() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        service.addApprovalObserver(new ApprovalAuditObserver(repository));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-approver");
        SqliteAgentSession session = new SqliteAgentSession(record);

        service.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        service.approve(
                session,
                DangerousCommandApprovalService.ApprovalScope.ONCE,
                "ops token=ghp_approver123");

        assertThat(repository.events).hasSize(2);
        ApprovalAuditEvent response = repository.events.get(1);
        assertThat(response.getChoice()).isEqualTo("once");
        assertThat(response.getOutcome())
                .isEqualTo(DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_APPROVED);
        assertThat(response.getStatus()).isEqualTo("approved");
        assertThat(response.isApproved()).isTrue();
        assertThat(response.getApprover()).doesNotContain("ghp_approver123");
        assertThat(response.getApprover()).contains("token=***");
    }

    @Test
    void shouldStripDisplayControlsAndSecretsFromApprovalAuditIdentifiers() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        service.addApprovalObserver(new ApprovalAuditObserver(repository));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session\u202E-audit");
        SqliteAgentSession session = new SqliteAgentSession(record);

        service.storePendingApproval(
                session,
                "execute_shell\u202E",
                "tirith:token_ghp_patternsecret123\u202E",
                "Security scan\u202E saw token=ghp_auditsecret123",
                "rm -rf workspace/cache\u202E --token ghp_commandsecret123");

        assertThat(repository.events).hasSize(1);
        ApprovalAuditEvent event = repository.events.get(0);
        assertThat(event.getSessionId()).isEqualTo("session-audit");
        assertThat(event.getToolName()).isEqualTo("execute_shell");
        assertThat(event.getPatternKeysJson())
                .contains("tirith:token_ghp_***")
                .doesNotContain("\u202E")
                .doesNotContain("patternsecret123");
        assertThat(event.getApprovalKey())
                .contains("tirith:token_ghp_***")
                .doesNotContain("\u202E")
                .doesNotContain("patternsecret123");
        assertThat(event.getDescription())
                .contains("token=***")
                .doesNotContain("\u202E")
                .doesNotContain("ghp_auditsecret123");
        assertThat(event.getCommandPreview())
                .contains("rm -rf workspace/cache --token ***")
                .doesNotContain("\u202E")
                .doesNotContain("ghp_commandsecret123");
    }

    @Test
    void shouldRedactSecretLikeApprovalIdInApprovalAuditEvent() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        ApprovalAuditObserver observer = new ApprovalAuditObserver(repository);
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setApprovalId("approval-ghp_auditid12345");
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");

        Constructor<DangerousCommandApprovalService.ApprovalRequestEvent> constructor =
                DangerousCommandApprovalService.ApprovalRequestEvent.class.getDeclaredConstructor(
                        String.class, DangerousCommandApprovalService.PendingApproval.class);
        constructor.setAccessible(true);
        DangerousCommandApprovalService.ApprovalRequestEvent event =
                constructor.newInstance("session-approval-id", pending);

        observer.onApprovalRequest(event);

        assertThat(repository.events).hasSize(1);
        assertThat(repository.events.get(0).getApprovalId())
                .contains("approval-ghp_***")
                .doesNotContain("ghp_auditid12345");
    }

    @Test
    void shouldRedactSecretLikeCommandHashInApprovalAuditEvent() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        ApprovalAuditObserver observer = new ApprovalAuditObserver(repository);
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");
        pending.setCommandHash("hash-ghp_audithash12345");

        Constructor<DangerousCommandApprovalService.ApprovalRequestEvent> constructor =
                DangerousCommandApprovalService.ApprovalRequestEvent.class.getDeclaredConstructor(
                        String.class, DangerousCommandApprovalService.PendingApproval.class);
        constructor.setAccessible(true);
        DangerousCommandApprovalService.ApprovalRequestEvent event =
                constructor.newInstance("session-command-hash", pending);

        observer.onApprovalRequest(event);

        assertThat(repository.events).hasSize(1);
        ApprovalAuditEvent audit = repository.events.get(0);
        assertThat(audit.getCommandHash())
                .contains("hash-ghp_***")
                .doesNotContain("ghp_audithash12345");
        assertThat(audit.getApprovalKey())
                .contains("hash-ghp_***")
                .doesNotContain("ghp_audithash12345");
    }

    @Test
    void shouldRedactSecretLikeSessionIdInApprovalAuditEvent() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        ApprovalAuditObserver observer = new ApprovalAuditObserver(repository);
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");

        Constructor<DangerousCommandApprovalService.ApprovalRequestEvent> constructor =
                DangerousCommandApprovalService.ApprovalRequestEvent.class.getDeclaredConstructor(
                        String.class, DangerousCommandApprovalService.PendingApproval.class);
        constructor.setAccessible(true);
        DangerousCommandApprovalService.ApprovalRequestEvent event =
                constructor.newInstance("session-ghp_auditsession12345\u202E", pending);

        observer.onApprovalRequest(event);

        assertThat(repository.events).hasSize(1);
        assertThat(repository.events.get(0).getSessionId())
                .contains("session-ghp_***")
                .doesNotContain("\u202E")
                .doesNotContain("ghp_auditsession12345");
    }

    @Test
    void shouldStripDisplayControlsFromApprovalAuditChoice() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        ApprovalAuditObserver observer = new ApprovalAuditObserver(repository);
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setApprovalId("approval-choice");
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");

        Constructor<DangerousCommandApprovalService.ApprovalResponseEvent> constructor =
                DangerousCommandApprovalService.ApprovalResponseEvent.class.getDeclaredConstructor(
                        String.class,
                        DangerousCommandApprovalService.PendingApproval.class,
                        String.class,
                        String.class);
        constructor.setAccessible(true);
        DangerousCommandApprovalService.ApprovalResponseEvent event =
                constructor.newInstance("session-choice", pending, "on\u202Ece", "ops");

        observer.onApprovalResponse(event);

        assertThat(repository.events).hasSize(1);
        assertThat(repository.events.get(0).getChoice()).isEqualTo("once");
    }

    @Test
    void shouldPersistDistinctDeniedAndTimedOutApprovalAuditOutcomes() throws Exception {
        CapturingApprovalAuditRepository repository = new CapturingApprovalAuditRepository();
        ApprovalAuditObserver observer = new ApprovalAuditObserver(repository);
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setApprovalId("approval-outcome");
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");

        Constructor<DangerousCommandApprovalService.ApprovalResponseEvent> constructor =
                DangerousCommandApprovalService.ApprovalResponseEvent.class.getDeclaredConstructor(
                        String.class,
                        DangerousCommandApprovalService.PendingApproval.class,
                        String.class,
                        String.class);
        constructor.setAccessible(true);
        observer.onApprovalResponse(
                constructor.newInstance("session-deny", pending, "deny", "ops"));
        observer.onApprovalResponse(
                constructor.newInstance("session-timeout", pending, "timeout", ""));

        assertThat(repository.events).hasSize(2);
        ApprovalAuditEvent denied = repository.events.get(0);
        ApprovalAuditEvent timedOut = repository.events.get(1);
        assertThat(denied.getChoice()).isEqualTo("deny");
        assertThat(denied.getOutcome())
                .isEqualTo(DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_DENIED);
        assertThat(denied.getStatus()).isEqualTo("denied");
        assertThat(denied.isApproved()).isFalse();
        assertThat(timedOut.getChoice()).isEqualTo("timeout");
        assertThat(timedOut.getOutcome())
                .isEqualTo(DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_TIMED_OUT);
        assertThat(timedOut.getStatus()).isEqualTo("timed_out");
        assertThat(timedOut.isApproved()).isFalse();
    }

    private static class CapturingApprovalAuditRepository implements ApprovalAuditRepository {
        private final List<ApprovalAuditEvent> events = new ArrayList<ApprovalAuditEvent>();

        @Override
        public void append(ApprovalAuditEvent event) {
            events.add(event);
        }

        @Override
        public List<ApprovalAuditEvent> listRecent(int limit) {
            return events;
        }
    }
}
