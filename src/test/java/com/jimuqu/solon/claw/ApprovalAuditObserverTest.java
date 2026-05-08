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
                "rm -rf runtime/cache --token ghp_commandsecret123");

        assertThat(repository.events).hasSize(1);
        ApprovalAuditEvent event = repository.events.get(0);
        assertThat(event.getDescription()).doesNotContain("ghp_auditsecret123");
        assertThat(event.getDescription()).doesNotContain("audit-password");
        assertThat(event.getDescription()).contains("token=***").contains("password=***");
        assertThat(event.getCommandPreview()).doesNotContain("ghp_commandsecret123");
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
                "rm -rf runtime/cache");
        service.approve(session, DangerousCommandApprovalService.ApprovalScope.ONCE, "ops token=ghp_approver123");

        assertThat(repository.events).hasSize(2);
        ApprovalAuditEvent response = repository.events.get(1);
        assertThat(response.getChoice()).isEqualTo("once");
        assertThat(response.getApprover()).doesNotContain("ghp_approver123");
        assertThat(response.getApprover()).contains("token=***");
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
