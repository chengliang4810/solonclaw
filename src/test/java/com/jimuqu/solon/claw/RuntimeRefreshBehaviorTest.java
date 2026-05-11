package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class RuntimeRefreshBehaviorTest {
    @Test
    void shouldUpdateLlmConfigWithoutReconnectingChannels() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setGlobalModel("default", "gpt-5.2");

        assertThat(env.appConfig.getLlm().getModel()).isEqualTo("gpt-5.2");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldUpdateConfigBackedLlmModelEffectively() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        FileUtil.writeUtf8String(
                "model:\n  providerKey: default\n  default: gpt-5.4\n",
                env.appConfig.getRuntime().getConfigFile());
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setGlobalModel("default", "gpt-5.2");

        assertThat(env.appConfig.getLlm().getModel()).isEqualTo("gpt-5.2");
        assertThat(env.appConfig.getModel().getDefault()).isEqualTo("gpt-5.2");
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("default: gpt-5.2");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldReconnectChannelsWhenUpdatingChannelConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("channels.weixin.enabled", "true");

        assertThat(env.appConfig.getChannels().getWeixin().isEnabled()).isTrue();
        assertThat(adapter.disconnectCount).isEqualTo(1);
        assertThat(adapter.connectCount).isEqualTo(1);
    }

    @Test
    void shouldUpdateJimuquWebsiteBlocklistRuntimeKeysWithoutReconnectingChannels()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("security.website_blocklist.enabled", "true");
        runtimeSettingsService.setConfigValue(
                "security.website_blocklist.domains",
                "blocked.example, *.tracking.example");
        runtimeSettingsService.setConfigValue(
                "security.website_blocklist.shared_files",
                "community-blocklist.txt");
        runtimeSettingsService.setConfigValue("security.tirithEnabled", "false");
        runtimeSettingsService.setConfigValue("security.tirithTimeoutSeconds", "9");

        assertThat(env.appConfig.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(env.appConfig.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example", "*.tracking.example");
        assertThat(env.appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly("community-blocklist.txt");
        assertThat(env.appConfig.getSecurity().isTirithEnabled()).isFalse();
        assertThat(env.appConfig.getSecurity().getTirithTimeoutSeconds()).isEqualTo(9);
        String config = FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile());
        assertThat(config)
                .contains("website_blocklist:")
                .contains("blocked.example")
                .contains("community-blocklist.txt")
                .contains("tirithEnabled: false")
                .contains("tirithTimeoutSeconds: 9")
                .doesNotContain("solonclaw:\n  security:");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldUpdateJimuquAllowPrivateUrlRuntimeKeysWithoutReconnectingChannels()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("security.allow_private_urls", "true");

        assertThat(env.appConfig.getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("allow_private_urls: true")
                .doesNotContain("solonclaw:\n  security:\n    allow_private_urls:");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldWriteApprovalsRuntimeKeysAtRootWithoutReconnectingChannels() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("approvals.mcpReloadConfirm", "false");

        String config = FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile());
        assertThat(env.appConfig.getApprovals().isMcpReloadConfirm()).isFalse();
        assertThat(config)
                .contains("approvals:")
                .contains("mcpReloadConfirm: false")
                .doesNotContain("solonclaw:\n  approvals:");
        assertThat(RuntimeConfigResolver.initialize(env.appConfig.getRuntime().getHome())
                        .get("approvals.mcpReloadConfirm"))
                .isEqualTo("false");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldRejectUnsafeCredentialFilePathsFromDashboardWrites() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardConfigService configService =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        Map<String, Object> updates = new LinkedHashMap<String, Object>();

        updates.put("terminal.credentialFiles", Collections.singletonList("credentials/oauth.json"));
        configService.savePartialFlat(updates, false);
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("credentialFiles:")
                .contains("credentials/oauth.json")
                .doesNotContain("solonclaw:\n  terminal:");

        assertCredentialPathRejected(configService, "../secret.json", "path traversal");
        assertCredentialPathRejected(configService, "/tmp/secret.json", "runtime-relative");
        assertCredentialPathRejected(configService, "C:\\Users\\secret.json", "runtime-relative");
        assertCredentialPathRejected(configService, "~/.ssh/id_rsa", "runtime-relative");
        assertCredentialPathRejected(configService, "credentials/\u0000secret.json", "control");
    }

    @Test
    void shouldWriteTerminalRuntimeKeysAtRootWithoutReconnectingChannels() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("terminal.writeSafeRoot", "D:/workspace/jimuqu-safe");

        String config = FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile());
        assertThat(env.appConfig.getTerminal().getWriteSafeRoot())
                .isEqualTo("D:/workspace/jimuqu-safe");
        assertThat(config)
                .contains("terminal:")
                .contains("writeSafeRoot: D:/workspace/jimuqu-safe")
                .doesNotContain("solonclaw:\n  terminal:");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldRejectUnsafeWebsiteBlocklistSharedFilePathsFromDashboardWrites()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardConfigService configService =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        Map<String, Object> updates = new LinkedHashMap<String, Object>();

        updates.put(
                "security.websiteBlocklist.sharedFiles",
                Collections.singletonList("blocklists/sites.txt"));
        configService.savePartialFlat(updates, false);
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("sharedFiles:")
                .contains("blocklists/sites.txt")
                .doesNotContain("solonclaw:\n  security:");

        assertWebsiteSharedFileRejected(configService, "../blocklists/sites.txt", "path traversal");
        assertWebsiteSharedFileRejected(configService, "blocklists/\u0000sites.txt", "control");
        assertWebsiteSharedFileRejected(configService, "~other/sites.txt", "home paths");
    }

    @Test
    void shouldRejectUnsafePathListsFromRawDashboardConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardConfigService configService =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);

        assertThatThrownBy(
                        () ->
                                configService.saveRaw(
                                        "solonclaw:\n"
                                                + "  terminal:\n"
                                                + "    credentialFiles:\n"
                                                + "      - ../secret.json\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal.credentialFiles")
                .hasMessageContaining("path traversal");

        assertThat(new java.io.File(env.appConfig.getRuntime().getConfigFile())).doesNotExist();
    }

    @Test
    void shouldRefreshDirectConfigFileChangesAfterValidation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileUtil.writeUtf8String(
                "solonclaw:\n  react:\n    maxSteps: 50\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRefreshed()).isTrue();
        assertThat(env.appConfig.getReact().getMaxSteps()).isEqualTo(50);
    }

    @Test
    void shouldRejectInvalidConfigBeforeRefreshing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        int previousMaxSteps = env.appConfig.getReact().getMaxSteps();
        FileUtil.writeUtf8String(
                "solonclaw:\n  react:\n    maxSteps: wrong\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("solonclaw.react.maxSteps");
        assertThat(env.appConfig.getReact().getMaxSteps()).isEqualTo(previousMaxSteps);
    }

    @Test
    void shouldRejectDecimalIntegerConfigBeforeRefreshing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        int previousMaxSteps = env.appConfig.getReact().getMaxSteps();
        FileUtil.writeUtf8String(
                "solonclaw:\n  react:\n    maxSteps: 50.0\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("solonclaw.react.maxSteps");
        assertThat(env.appConfig.getReact().getMaxSteps()).isEqualTo(previousMaxSteps);
    }

    @Test
    void shouldRejectInvalidProviderShapeBeforeRefreshing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String previousModel = env.appConfig.getLlm().getModel();
        FileUtil.writeUtf8String(
                "providers:\n  default: wrong\nmodel:\n  providerKey: default\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("providers.default");
        assertThat(env.appConfig.getLlm().getModel()).isEqualTo(previousModel);
    }

    @Test
    void shouldRedactRuntimeConfigYamlParseErrorsBeforeRefreshing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileUtil.writeUtf8String(
                "providers:\n  default:\n    apiKey: sk-test-refreshsecret12345\n    broken: [",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("runtime/config.yml 格式错误");
        assertThat(result.getMessage()).contains("***");
        assertThat(result.getMessage()).doesNotContain("sk-test-refreshsecret12345");
    }

    @Test
    void shouldBlockUnsafeProviderModelListUrlBeforeNetworkAccess() {
        AppConfig config = new AppConfig();
        DashboardProviderService providerService =
                new DashboardProviderService(
                        config,
                        null,
                        new LlmProviderService(config),
                        new SecurityPolicyService(config) {
                            @Override
                            public UrlVerdict checkUrl(String url) {
                                return UrlVerdict.block(url, "blocked-by-test");
                            }
                        });
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("baseUrl", "https://api.example.test");
        body.put("dialect", "openai");
        body.put("apiKey", "test-key");

        assertThatThrownBy(() -> providerService.listRemoteModels(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provider model list URL blocked")
                .hasMessageContaining("blocked-by-test");
    }

    @Test
    void shouldBlockUnsafeProviderModelListRedirectTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/v1/models",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .add(
                                        "Location",
                                        "http://169.254.169.254/latest/meta-data/?token=secret");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            server.start();
            AppConfig config = new AppConfig();
            DashboardProviderService providerService =
                    new DashboardProviderService(
                            config,
                            null,
                            new LlmProviderService(config),
                            new AllowLocalButBlockMetadataSecurityPolicyService(config));
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            body.put("dialect", "openai");

            assertThatThrownBy(() -> providerService.listRemoteModels(body))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Provider model list URL blocked")
                    .hasMessageContaining("169.254.169.254")
                    .hasMessageContaining("token=***");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactProviderModelListErrorBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"error\":\"api_key=sk-provider-model-secret token=ghp_providermodel12345\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            AppConfig config = new AppConfig();
            DashboardProviderService providerService =
                    new DashboardProviderService(
                            config,
                            null,
                            new LlmProviderService(config),
                            new AllowLocalButBlockMetadataSecurityPolicyService(config));
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            body.put("dialect", "openai");

            assertThatThrownBy(() -> providerService.listRemoteModels(body))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("获取模型列表失败：HTTP 500")
                    .hasMessageContaining("api_key=***")
                    .hasMessageContaining("token=***")
                    .hasMessageNotContaining("sk-provider-model-secret")
                    .hasMessageNotContaining("ghp_providermodel12345");
        } finally {
            server.stop(0);
        }
    }

    private RuntimeSettingsService runtimeSettingsService(
            TestEnvironment env, RecordingChannelAdapter adapter) {
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        GatewayRuntimeRefreshService refreshService =
                new GatewayRuntimeRefreshService(
                        env.appConfig,
                        new com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager(
                                adapters));
        DashboardConfigService configService =
                new DashboardConfigService(env.appConfig, refreshService);
        DashboardRuntimeConfigService runtimeConfigService =
                new DashboardRuntimeConfigService(env.appConfig, refreshService);
        LlmProviderService llmProviderService = new LlmProviderService(env.appConfig);
        DashboardProviderService providerService =
                new DashboardProviderService(env.appConfig, refreshService, llmProviderService);
        return new RuntimeSettingsService(
                env.appConfig,
                env.globalSettingRepository,
                env.deliveryService,
                configService,
                runtimeConfigService,
                new AppVersionService(env.appConfig),
                llmProviderService,
                providerService);
    }

    private void assertCredentialPathRejected(
            DashboardConfigService configService, String value, String messagePart) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("terminal.credentialFiles", Collections.singletonList(value));
        assertThatThrownBy(() -> configService.savePartialFlat(updates, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal.credentialFiles")
                .hasMessageContaining(messagePart);
    }

    private void assertWebsiteSharedFileRejected(
            DashboardConfigService configService, String value, String messagePart) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("security.websiteBlocklist.sharedFiles", Collections.singletonList(value));
        assertThatThrownBy(() -> configService.savePartialFlat(updates, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.websiteBlocklist.sharedFiles")
                .hasMessageContaining(messagePart);
    }

    private static class AllowLocalButBlockMetadataSecurityPolicyService
            extends SecurityPolicyService {
        private AllowLocalButBlockMetadataSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            }
            return new InetAddress[] {InetAddress.getByName(host)};
        }
    }

    private static class RecordingChannelAdapter implements ChannelAdapter {
        private final PlatformType platformType;
        private int connectCount;
        private int disconnectCount;

        private RecordingChannelAdapter(PlatformType platformType) {
            this.platformType = platformType;
        }

        @Override
        public PlatformType platform() {
            return platformType;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean connect() {
            connectCount++;
            return true;
        }

        @Override
        public void disconnect() {
            disconnectCount++;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String detail() {
            return "recording";
        }

        @Override
        public void send(DeliveryRequest request) {}

        @Override
        public ChannelStatus statusSnapshot() {
            ChannelStatus status = new ChannelStatus(platformType, true, true, "recording");
            status.setMissingConfig(Collections.<String>emptyList());
            return status;
        }
    }
}
