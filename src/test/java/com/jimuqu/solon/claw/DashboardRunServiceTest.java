package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.junit.jupiter.api.Test;

public class DashboardRunServiceTest {
    @Test
    void shouldReturnEventsWhenMetadataJsonIsBroken() throws Exception {
        FakeAgentRunRepository repository = new FakeAgentRunRepository();
        AgentRunEventRecord event = new AgentRunEventRecord();
        event.setEventId("event-1");
        event.setRunId("run-1");
        event.setSessionId("session-1");
        event.setEventType("attempt.error");
        event.setMetadataJson("{\"preview\":\"unterminated");
        repository.events.add(event);

        DashboardRunService service = new DashboardRunService(repository);
        Map<String, Object> response = service.events("run-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.get("events");
        assertThat(events).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) events.get(0).get("metadata");
        assertThat(metadata.get("parse_error")).isEqualTo(true);
        assertThat(metadata.get("field")).isEqualTo("metadata");
        assertThat(metadata.get("raw")).isEqualTo("{\"preview\":\"unterminated");
    }

    @Test
    void shouldRedactSecretsFromRunDetails() throws Exception {
        FakeAgentRunRepository repository = new FakeAgentRunRepository();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-secret");
        run.setSessionId("session-secret");
        run.setInputPreview("use api_key=sk-test-runinputsecret");
        run.setFinalReplyPreview("done token=ghp_runreplysecret123");
        run.setProvider("provider-ghp_runprovider12345");
        run.setModel("model-ghp_runmodel12345");
        run.setAgentSnapshotJson(
                ONode.serialize(Collections.singletonMap("env", "OPENAI_API_KEY=sk-test-snapshotsecret")));
        run.setRecoveryHint("retry with password=run-password");
        run.setError("failed with Authorization: Bearer ghp_runerrorsecret123");
        repository.runs.add(run);

        AgentRunEventRecord event = new AgentRunEventRecord();
        event.setEventId("event-secret");
        event.setRunId("run-secret");
        event.setSessionId("session-secret");
        event.setProvider("event-provider-ghp_eventprovider12345");
        event.setModel("event-model-ghp_eventmodel12345");
        event.setSummary("metadata token=ghp_eventsummary123");
        event.setMetadataJson(
                ONode.serialize(Collections.singletonMap("url", "https://u:p@example.com/cb?token=event-token")));
        repository.events.add(event);

        ToolCallRecord tool = new ToolCallRecord();
        tool.setToolCallId("tool-secret");
        tool.setRunId("run-secret");
        tool.setArgsPreview("OPENAI_API_KEY=sk-test-toolargsecret");
        tool.setResultPreview("Authorization: Bearer ghp_toolresult123");
        tool.setResultRef("/tmp/output-token=secret-ref-ghp_toolrefsecret123.txt");
        tool.setError("password=tool-password");
        repository.tools.add(tool);

        SubagentRunRecord subagent = new SubagentRunRecord();
        subagent.setSubagentId("subagent-secret");
        subagent.setParentRunId("run-secret");
        subagent.setGoalPreview("inspect token=ghp_subagentgoal123");
        subagent.setOutputTailJson(
                ONode.serialize(Collections.singletonList("api_key=sk-test-subagenttail")));
        subagent.setError("secret=subagent-secret-value");
        repository.subagents.add(subagent);

        RunRecoveryRecord recovery = new RunRecoveryRecord();
        recovery.setRecoveryId("recovery-secret");
        recovery.setRunId("run-secret");
        recovery.setSummary("recover token=ghp_recoverysummary123");
        recovery.setPayloadJson(
                ONode.serialize(Collections.singletonMap("authorization", "Bearer ghp_recoverypayload123")));
        repository.recoveries.add(recovery);

        RunControlCommand command = new RunControlCommand();
        command.setCommandId("command-secret");
        command.setRunId("run-secret");
        command.setCommand("steer api_key=sk-test-commandsecret");
        command.setPayloadJson(ONode.serialize(Collections.singletonMap("token", "ghp_commandpayload123")));
        repository.commands.add(command);

        DashboardRunService service = new DashboardRunService(repository);
        String response = ONode.serialize(service.detail("run-secret"));

        assertThat(response).doesNotContain("sk-test-runinputsecret");
        assertThat(response).doesNotContain("ghp_runreplysecret123");
        assertThat(response).contains("provider-ghp_***").contains("model-ghp_***");
        assertThat(response)
                .contains("event-provider-ghp_***")
                .contains("event-model-ghp_***");
        assertThat(response).doesNotContain("runprovider12345");
        assertThat(response).doesNotContain("runmodel12345");
        assertThat(response).doesNotContain("eventprovider12345");
        assertThat(response).doesNotContain("eventmodel12345");
        assertThat(response).doesNotContain("sk-test-snapshotsecret");
        assertThat(response).doesNotContain("run-password");
        assertThat(response).doesNotContain("ghp_runerrorsecret123");
        assertThat(response).doesNotContain("ghp_eventsummary123");
        assertThat(response).doesNotContain("event-token");
        assertThat(response).doesNotContain("sk-test-toolargsecret");
        assertThat(response).doesNotContain("ghp_toolresult123");
        assertThat(response).doesNotContain("secret-ref");
        assertThat(response).doesNotContain("ghp_toolrefsecret123");
        assertThat(response).doesNotContain("tool-password");
        assertThat(response).doesNotContain("ghp_subagentgoal123");
        assertThat(response).doesNotContain("sk-test-subagenttail");
        assertThat(response).doesNotContain("subagent-secret-value");
        assertThat(response).doesNotContain("ghp_recoverysummary123");
        assertThat(response).doesNotContain("ghp_recoverypayload123");
        assertThat(response).doesNotContain("sk-test-commandsecret");
        assertThat(response).doesNotContain("ghp_commandpayload123");
        assertThat(response).contains("***");
    }

    @Test
    void shouldRedactSecretLikeRunIdentifiersFromDashboardDetails() throws Exception {
        FakeAgentRunRepository repository = new FakeAgentRunRepository();
        String runSecret = "ghp_runidsecret12345";
        String sessionSecret = "ghp_sessionidsecret12345";
        String sourceSecret = "ghp_sourcekeysecret12345";
        String eventSecret = "ghp_eventidsecret12345";
        String toolSecret = "ghp_toolcallidsecret12345";
        String subagentSecret = "ghp_subagentidsecret12345";
        String childRunSecret = "ghp_childrunsecret12345";
        String recoverySecret = "ghp_recoveryidsecret12345";
        String commandSecret = "ghp_commandidsecret12345";

        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-" + runSecret);
        run.setSessionId("session-" + sessionSecret);
        run.setSourceKey("MEMORY:room-" + sourceSecret + ":user");
        run.setParentRunId("parent-" + childRunSecret);
        run.setAgentName("agent-" + subagentSecret);
        repository.runs.add(run);

        AgentRunEventRecord event = new AgentRunEventRecord();
        event.setEventId("event-" + eventSecret);
        event.setRunId(run.getRunId());
        event.setSessionId(run.getSessionId());
        event.setSourceKey(run.getSourceKey());
        event.setMetadataJson("{\"broken\":\"ghp_eventmetafallback12345\"");
        repository.events.add(event);

        ToolCallRecord tool = new ToolCallRecord();
        tool.setToolCallId("tool-" + toolSecret);
        tool.setRunId(run.getRunId());
        tool.setSessionId(run.getSessionId());
        tool.setSourceKey(run.getSourceKey());
        repository.tools.add(tool);

        SubagentRunRecord subagent = new SubagentRunRecord();
        subagent.setSubagentId("subagent-" + subagentSecret);
        subagent.setParentRunId(run.getRunId());
        subagent.setChildRunId("child-" + childRunSecret);
        subagent.setParentSourceKey(run.getSourceKey());
        subagent.setChildSourceKey("MEMORY:child-" + sourceSecret + ":user");
        subagent.setSessionId(run.getSessionId());
        subagent.setName("agent-" + subagentSecret);
        repository.subagents.add(subagent);

        RunRecoveryRecord recovery = new RunRecoveryRecord();
        recovery.setRecoveryId("recovery-" + recoverySecret);
        recovery.setRunId(run.getRunId());
        recovery.setSessionId(run.getSessionId());
        recovery.setSourceKey(run.getSourceKey());
        repository.recoveries.add(recovery);

        RunControlCommand command = new RunControlCommand();
        command.setCommandId("command-" + commandSecret);
        command.setRunId(run.getRunId());
        command.setSourceKey(run.getSourceKey());
        repository.commands.add(command);

        DashboardRunService service = new DashboardRunService(repository);
        String response = ONode.serialize(service.detail(run.getRunId()));

        assertThat(response)
                .contains("run-ghp_***")
                .contains("session-ghp_***")
                .contains("event-ghp_***")
                .contains("tool-ghp_***")
                .contains("subagent-ghp_***")
                .contains("recovery-ghp_***")
                .contains("command-ghp_***");
        assertThat(response)
                .doesNotContain(runSecret)
                .doesNotContain(sessionSecret)
                .doesNotContain(sourceSecret)
                .doesNotContain(eventSecret)
                .doesNotContain(toolSecret)
                .doesNotContain(subagentSecret)
                .doesNotContain(childRunSecret)
                .doesNotContain(recoverySecret)
                .doesNotContain(commandSecret)
                .doesNotContain("ghp_eventmetafallback12345");
    }

    @Test
    void shouldRedactControlPayloadWhenRunControlIsUnavailable() throws Exception {
        FakeAgentRunRepository repository = new FakeAgentRunRepository();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-control-secret");
        run.setSessionId("session-control-secret");
        repository.runs.add(run);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("instruction", "steer token=ghp_controlpayload12345");
        payload.put("nested", Collections.singletonMap("api_key", "sk-controlpayload-secret"));

        DashboardRunService service = new DashboardRunService(repository);
        String response =
                ONode.serialize(service.control("run-control-secret", "steer", payload));

        assertThat(response).contains("control_unavailable");
        assertThat(response)
                .contains("token=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_controlpayload12345")
                .doesNotContain("sk-controlpayload-secret");
    }

    @Test
    void shouldRedactSecretLikeActiveSubagentFields() {
        FakeAgentRunRepository repository = new FakeAgentRunRepository();
        FakeDelegationService delegationService = new FakeDelegationService();
        Map<String, Object> active = new LinkedHashMap<String, Object>();
        active.put("subagent_id", "subagent-ghp_activesubagent12345");
        active.put("parent_run_id", "parent-ghp_activeparent12345");
        active.put("child_run_id", "child-ghp_activechild12345");
        active.put("source_key", "MEMORY:room-token=ghp_activesource12345:user");
        active.put("status", "running");
        active.put("depth", Integer.valueOf(1));
        active.put(
                "output_tail",
                "stdout api_key=sk-activesubagent-secret token=ghp_activetail12345");
        active.put(
                "nested",
                Collections.singletonMap("authorization", "Bearer ghp_activenested12345"));
        delegationService.active.add(active);

        DashboardRunService service =
                new DashboardRunService(repository, null, delegationService);
        String response = ONode.serialize(service.activeSubagents());

        assertThat(response)
                .contains("subagent-ghp_***")
                .contains("parent-ghp_***")
                .contains("child-ghp_***")
                .contains("token=***")
                .contains("api_key=***")
                .contains("Bearer ***")
                .doesNotContain("activesubagent12345")
                .doesNotContain("activeparent12345")
                .doesNotContain("activechild12345")
                .doesNotContain("activesource12345")
                .doesNotContain("sk-activesubagent-secret")
                .doesNotContain("activetail12345")
                .doesNotContain("activenested12345");
    }

    private static class FakeAgentRunRepository implements AgentRunRepository {
        private final List<AgentRunEventRecord> events = new ArrayList<AgentRunEventRecord>();
        private final List<AgentRunRecord> runs = new ArrayList<AgentRunRecord>();
        private final List<ToolCallRecord> tools = new ArrayList<ToolCallRecord>();
        private final List<SubagentRunRecord> subagents = new ArrayList<SubagentRunRecord>();
        private final List<RunRecoveryRecord> recoveries = new ArrayList<RunRecoveryRecord>();
        private final List<RunControlCommand> commands = new ArrayList<RunControlCommand>();

        @Override
        public void saveRun(AgentRunRecord record) {}

        @Override
        public AgentRunRecord findRun(String runId) {
            for (AgentRunRecord run : runs) {
                if (runId.equals(run.getRunId())) {
                    return run;
                }
            }
            return null;
        }

        @Override
        public List<AgentRunRecord> listBySession(String sessionId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listFinishedWithUsage(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listRecoverable(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listActiveBefore(long beforeEpochMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void markStaleRuns(long beforeEpochMillis, long now) {}

        @Override
        public List<AgentRunRecord> listActiveBySource(String sourceKey, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> searchRuns(
                String sourceKey,
                String sessionId,
                String runId,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            return Collections.emptyList();
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
            events.add(event);
        }

        @Override
        public List<AgentRunEventRecord> listEvents(String runId) {
            return events;
        }

        @Override
        public void saveRunControlCommand(RunControlCommand command) {}

        @Override
        public List<RunControlCommand> listRunControlCommands(String runId) {
            return commands;
        }

        @Override
        public RunControlCommand findLatestPendingCommand(String runId, String command) {
            return null;
        }

        @Override
        public void markRunControlCommandHandled(String commandId, String status, long handledAt) {}

        @Override
        public void saveQueuedMessage(QueuedRunMessage message) {}

        @Override
        public QueuedRunMessage findNextQueuedMessage(String sourceKey, String sessionId) {
            return null;
        }

        @Override
        public int countQueuedMessages(String sourceKey, String sessionId) {
            return 0;
        }

        @Override
        public void markQueuedMessage(String queueId, String status, long timestamp, String error) {}

        @Override
        public void saveToolCall(ToolCallRecord record) {}

        @Override
        public List<ToolCallRecord> listToolCalls(String runId) {
            return tools;
        }

        @Override
        public List<ToolCallRecord> searchToolCalls(
                String sourceKey,
                String sessionId,
                String runId,
                String toolName,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            return Collections.emptyList();
        }

        @Override
        public void saveSubagentRun(SubagentRunRecord record) {}

        @Override
        public List<SubagentRunRecord> listSubagents(String parentRunId) {
            return subagents;
        }

        @Override
        public void saveRecovery(RunRecoveryRecord record) {}

        @Override
        public List<RunRecoveryRecord> listRecoveries(String runId) {
            return recoveries;
        }

        @Override
        public void pruneBefore(long beforeEpochMillis) {}
    }

    private static class FakeDelegationService implements DelegationService {
        private final List<Map<String, Object>> active =
                new ArrayList<Map<String, Object>>();

        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context) {
            return null;
        }

        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks) {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> activeSubagents() {
            return active;
        }
    }
}
