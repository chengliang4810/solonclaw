package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class DashboardStatusServiceTest {
    @Test
    void shouldRedactSensitiveDashboardStatusFields() throws Exception {
        AppConfig config = new AppConfig();
        File runtimeHome = new File("target/status-secret-runtime").getAbsoluteFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setConfigFile(new File(runtimeHome, "config.yml").getAbsolutePath());
        config.getLlm().setContextWindowTokens(128000);
        config.getLlm().setMaxTokens(4096);
        config.getModel().setProviderKey("default-ghp_statusprovider12345");
        config.getModel().setDefault("gpt-ghp_statusmodel12345");
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default token=ghp_statuslabel12345");
        provider.setBaseUrl("https://user:secret-pass@example.com/v1?token=base-url-token");
        provider.setDefaultModel("gpt-ghp_statusmodel12345");
        provider.setDialect("openai");
        config.getProviders().put("default-ghp_statusprovider12345", provider);
        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("fallback-ghp_statusfallback12345");
        fallback.setModel("fallback-model-ghp_statusfallbackmodel12345");
        config.getFallbackProviders().add(fallback);

        ChannelStatus channelStatus =
                new ChannelStatus(
                        PlatformType.FEISHU,
                        true,
                        false,
                        "failed at "
                                + new File(runtimeHome, "secrets/token.txt").getAbsolutePath()
                                + " token=ghp_channelstatus123");
        channelStatus.setLastErrorCode("auth_failed");
        channelStatus.setLastErrorMessage(
                "Authorization: Bearer ghp_channelerror123 password=channel-password");

        DashboardStatusService service =
                new DashboardStatusService(
                        config,
                        new EmptySessionRepository(),
                        new FixedDeliveryService(channelStatus),
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())),
                        new AppVersionService(config),
                        new FixedUpdateService(config),
                        new LlmProviderService(config));

        String statusJson = ONode.serialize(service.getStatus(true));
        assertThat(statusJson).contains("runtime://config.yml");
        assertThat(statusJson).contains("runtime://");
        assertThat(statusJson).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(statusJson).doesNotContain("ghp_channelstatus123");
        assertThat(statusJson).doesNotContain("ghp_channelerror123");
        assertThat(statusJson).doesNotContain("channel-password");
        assertThat(statusJson).doesNotContain("ghp_updateerror123");

        String modelJson = ONode.serialize(service.getModelInfo(true));
        assertThat(modelJson).contains("https://user:***@example.com/v1?token=***");
        assertThat(modelJson)
                .contains("default-ghp_***")
                .contains("Default token=***")
                .contains("gpt-ghp_***")
                .contains("fallback-ghp_***")
                .contains("fallback-model-ghp_***");
        assertThat(modelJson).doesNotContain("secret-pass");
        assertThat(modelJson).doesNotContain("base-url-token");
        assertThat(modelJson).doesNotContain("statusprovider12345");
        assertThat(modelJson).doesNotContain("statuslabel12345");
        assertThat(modelJson).doesNotContain("statusmodel12345");
        assertThat(modelJson).doesNotContain("statusfallback12345");
        assertThat(modelJson).doesNotContain("statusfallbackmodel12345");
    }

    private static class FixedDeliveryService implements DeliveryService {
        private final ChannelStatus status;

        private FixedDeliveryService(ChannelStatus status) {
            this.status = status;
        }

        @Override
        public void deliver(DeliveryRequest request) {}

        @Override
        public List<ChannelStatus> statuses() {
            return Collections.singletonList(status);
        }
    }

    private static class FixedUpdateService extends AppUpdateService {
        private FixedUpdateService(AppConfig appConfig) {
            super(appConfig, new AppVersionService(appConfig));
        }

        @Override
        public VersionStatus getVersionStatus(boolean forceRefresh) {
            VersionStatus status = new VersionStatus();
            status.setCurrentVersion("0.0.0-test");
            status.setCurrentTag("v0.0.0-test");
            status.setDeploymentMode("dev");
            status.setReleaseUrl("https://user:release-pass@example.com/releases?token=release-token");
            status.setReleaseApiUrl("https://api.example.com/releases?access_token=release-api-token");
            status.setUpdateErrorMessage("update token=ghp_updateerror123");
            status.setUpdateErrorAt(123L);
            return status;
        }
    }

    private static class EmptySessionRepository implements SessionRepository {
        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            return null;
        }

        @Override
        public SessionRecord bindNewSession(String sourceKey) {
            return null;
        }

        @Override
        public void bindSource(String sourceKey, String sessionId) {}

        @Override
        public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
            return null;
        }

        @Override
        public SessionRecord findById(String sessionId) {
            return null;
        }

        @Override
        public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
            return null;
        }

        @Override
        public List<SessionRecord> findResumeCandidates(String reference, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void save(SessionRecord sessionRecord) {}

        @Override
        public List<SessionRecord> search(String keyword, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listRecent(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int countAll() {
            return 0;
        }

        @Override
        public void delete(String sessionId) {}

        @Override
        public void setModelOverride(String sessionId, String modelOverride) {}

        @Override
        public void setServiceTierOverride(String sessionId, String serviceTierOverride) {}

        @Override
        public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {}

        @Override
        public void setActiveAgentName(String sessionId, String agentName) {}

        @Override
        public void clearActiveAgentName(String agentName) {}

        @Override
        public void setGoalState(String sessionId, String goalStateJson) {}

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {}
    }
}
