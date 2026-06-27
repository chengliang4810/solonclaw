package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGlobalSettingRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.tui.TerminalUiRpcService;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalUiRpcServiceTest {
    @Test
    void sessionResumeIncludesLiveRunFields() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SqliteAgentRunRepository runs = new SqliteAgentRunRepository(database);
        SessionRecord session = session("session-live", "MEMORY:terminal-ui:session-live");
        sessions.save(session);

        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-live");
        run.setSessionId(session.getSessionId());
        run.setSourceKey(session.getSourceKey());
        run.setStatus("waiting_approval");
        run.setStartedAt(1_800_000_000_000L);
        runs.saveRun(run);

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config,
                        sessions,
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
                        runs,
                        null,
                        null);

        Map<String, Object> response = service.sessionResume(session.getSessionId());

        assertThat(response.get("running")).isEqualTo(Boolean.TRUE);
        assertThat(response.get("status")).isEqualTo("waiting");
        assertThat(response.get("started_at")).isEqualTo(1_800_000_000L);
        assertThat(response).containsKey("inflight");
    }

    @Test
    void sessionUsageIncludesActiveSubagentCount() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SessionRecord session = session("session-usage", "MEMORY:terminal-ui:session-usage");
        sessions.save(session);

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config,
                        sessions,
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
                        new FixedDelegationService(2),
                        null,
                        null,
                        null,
                        null);

        assertThat(service.sessionUsage(session.getSessionId()).get("active_subagents")).isEqualTo(2);
    }

    @Test
    void reloadMcpRequiresExplicitConfirmationWhenPolicyRequiresIt() throws Exception {
        AppConfig config = testConfig();
        config.getApprovals().setMcpReloadConfirm(true);
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        Map<String, Object> response = service.reloadMcp();

        assertThat(response.get("status")).isEqualTo("confirm_required");
        assertThat(response.get("message")).asString().contains("/reload-mcp now");
    }

    @Test
    void reloadMcpRunsWhenConfirmedAndCanRememberAlways() throws Exception {
        AppConfig config = testConfig();
        config.getApprovals().setMcpReloadConfirm(true);
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        Map<String, Object> once = service.reloadMcp(true, false);

        assertThat(once.get("status")).isEqualTo("reloaded");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isTrue();

        Map<String, Object> always = service.reloadMcp(true, true);

        assertThat(always.get("status")).isEqualTo("reloaded");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isFalse();
    }

    @Test
    void compactConfigSetRespectsExplicitOnOffValues() throws Exception {
        AppConfig config = testConfig();
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        assertThat(service.configSet("compact", "on", "").get("value")).isEqualTo("1");
        assertThat(service.configSet("compact", "on", "").get("value")).isEqualTo("1");
        assertThat(service.configSet("compact", "off", "").get("value")).isEqualTo("0");
        assertThat(service.configSet("compact", "off", "").get("value")).isEqualTo("0");
    }

    @Test
    void mouseConfigSetPersistsTrackingPresets() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config,
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
                        null,
                        new SqliteGlobalSettingRepository(database));

        assertThat(service.configSet("mouse", "wheel", "").get("value")).isEqualTo("wheel");
        assertThat(service.fullConfig().get("config").toString()).contains("mouse_tracking=wheel");
        assertThat(service.configSet("mouse", "buttons", "").get("value")).isEqualTo("buttons");
        assertThat(service.fullConfig().get("config").toString()).contains("mouse_tracking=buttons");
        assertThat(service.configSet("mouse", "off", "").get("value")).isEqualTo("off");
        assertThat(service.fullConfig().get("config").toString()).contains("mouse_tracking=off");
    }

    private static SessionRecord session(String id, String sourceKey) {
        SessionRecord session = new SessionRecord();
        session.setSessionId(id);
        session.setSourceKey(sourceKey);
        session.setNdjson("");
        session.setCreatedAt(1_800_000_000_000L);
        session.setUpdatedAt(1_800_000_000_000L);
        return session;
    }

    private static AppConfig testConfig() throws Exception {
        File home = Files.createTempDirectory("solonclaw-tui-rpc-test").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.getAbsolutePath());
        config.getRuntime().setStateDb(new File(new File(home, "data"), "state.db").getAbsolutePath());
        return config;
    }

    private static class FixedDelegationService implements DelegationService {
        private final int activeCount;

        private FixedDelegationService(int activeCount) {
            this.activeCount = activeCount;
        }

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
            List<Map<String, Object>> active = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < activeCount; i++) {
                active.add(Collections.<String, Object>singletonMap("subagent_id", "sub-" + i));
            }
            return active;
        }
    }
}
