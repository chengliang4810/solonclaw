package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.FixedDnsSecurityPolicyService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    void shouldUpdateCanonicalWebsiteBlocklistRuntimeKeysWithoutReconnectingChannels()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("security.websiteBlocklist.enabled", "true");
        runtimeSettingsService.setConfigValue(
                "security.websiteBlocklist.domains", "blocked.example, *.tracking.example");
        runtimeSettingsService.setConfigValue(
                "security.websiteBlocklist.sharedFiles", "community-blocklist.txt");
        runtimeSettingsService.setConfigValue("security.tirithEnabled", "false");
        runtimeSettingsService.setConfigValue("security.tirithTimeoutSeconds", "9");
        runtimeSettingsService.setConfigValue("security.hardlineAllowlist", "hardline_delete_root");

        assertThat(env.appConfig.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(env.appConfig.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example", "*.tracking.example");
        assertThat(env.appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly("community-blocklist.txt");
        assertThat(env.appConfig.getSecurity().isTirithEnabled()).isFalse();
        assertThat(env.appConfig.getSecurity().getTirithTimeoutSeconds()).isEqualTo(9);
        assertThat(env.appConfig.getSecurity().getHardlineAllowlist())
                .containsExactly("hardline_delete_root");
        assertThat(env.dangerousCommandApprovalService.detectHardline("execute_shell", "rm -rf /"))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.detectHardline(
                                "execute_shell", "curl http://169.254.169.254/latest/meta-data/"))
                .isNotNull();
        String config = FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile());
        assertThat(config)
                .contains("websiteBlocklist:")
                .contains("hardlineAllowlist:")
                .contains("- hardline_delete_root")
                .contains("blocked.example")
                .contains("community-blocklist.txt")
                .contains("tirithEnabled: false")
                .contains("tirithTimeoutSeconds: 9")
                .doesNotContain("solonclaw:\n  security:");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldPersistPromptCacheDashboardConfigWithoutReconnectingChannels() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
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
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("llm.promptCache.enabled", Boolean.TRUE);
        updates.put("llm.promptCache.ttl", "1h");
        updates.put("llm.promptCache.layout", "system_and_3");

        configService.savePartialFlat(updates, false);

        String config = FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile());
        assertThat(config).contains("promptCache:").contains("enabled: true").contains("ttl: 1h");
        assertThat(env.appConfig.getLlm().getPromptCache().isEnabled()).isTrue();
        assertThat(env.appConfig.getLlm().getPromptCache().getTtl()).isEqualTo("1h");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldApplyDashboardWebsitePolicyUpdatesToUrlChecksWithoutReconnectingChannels()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
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
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(env.appConfig, "93.184.216.34");
        Map<String, Object> updates = new LinkedHashMap<String, Object>();

        updates.put("security.allowPrivateUrls", Boolean.TRUE);
        updates.put("security.websiteBlocklist.enabled", Boolean.TRUE);
        updates.put(
                "security.websiteBlocklist.domains",
                Arrays.asList("blocked.example", "*.tracking.example"));
        configService.savePartialFlat(updates, false);

        assertThat(policy.checkUrl("http://127.0.0.1:8080/status").isAllowed()).isTrue();
        SecurityPolicyService.UrlVerdict blocked =
                policy.checkUrl("https://docs.blocked.example/page?token=secret");
        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getMessage()).contains("blocked.example").doesNotContain("secret");
        assertThat(policy.checkUrl("https://pixel.tracking.example/p.gif").isAllowed()).isFalse();
        SecurityPolicyService.UrlVerdict bareDomain =
                policy.checkUrl("https://tracking.example/p.gif");
        assertThat(bareDomain.isAllowed()).isFalse();
        assertThat(bareDomain.isApprovalRequired()).isTrue();
        assertThat(bareDomain.getPolicyKey()).isEqualTo("network_external_operation");
        assertThat(policy.websitePolicySummary().get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(policy.websitePolicySummary().get("configuredDomainCount")).isEqualTo(2);
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldUpdateCanonicalAllowPrivateUrlRuntimeKeysWithoutReconnectingChannels()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("security.allowPrivateUrls", "true");

        assertThat(env.appConfig.getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("allowPrivateUrls: true")
                .doesNotContain("allow_private_urls:");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldUpdateBrowserLoopbackRewriteRuntimeKeysWithoutReconnectingChannels()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue("solonclaw.browser.rewriteLoopbackUrls", "true");
        runtimeSettingsService.setConfigValue(
                "solonclaw.browser.loopbackHostAlias", "host.containers.internal");

        assertThat(env.appConfig.getSecurity().isRewriteBrowserLoopbackUrls()).isTrue();
        assertThat(env.appConfig.getSecurity().getBrowserLoopbackHostAlias())
                .isEqualTo("host.containers.internal");
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("solonclaw:")
                .contains("browser:")
                .contains("rewriteLoopbackUrls: true")
                .contains("loopbackHostAlias: host.containers.internal");
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
        assertThat(
                        RuntimeConfigResolver.initialize(env.appConfig.getRuntime().getHome())
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

        updates.put(
                "solonclaw.terminal.credentialFiles",
                Collections.singletonList("credentials/oauth.json"));
        configService.savePartialFlat(updates, false);
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("solonclaw:")
                .contains("terminal:")
                .contains("credentialFiles:")
                .contains("credentials/oauth.json");

        assertCredentialPathRejected(configService, "../secret.json", "path traversal");
        assertCredentialPathRejected(configService, "/tmp/secret.json", "workspace-relative");
        assertCredentialPathRejected(configService, "C:\\Users\\secret.json", "workspace-relative");
        assertCredentialPathRejected(configService, "~/.ssh/id_rsa", "workspace-relative");
        assertCredentialPathRejected(configService, "credentials/\u0000secret.json", "control");
    }

    @Test
    void shouldRejectHighRiskTerminalEnvPassthroughFromDashboardWrites() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardConfigService configService =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        Map<String, Object> updates = new LinkedHashMap<String, Object>();

        updates.put(
                "solonclaw.terminal.envPassthrough", Collections.singletonList("TENOR_API_KEY"));
        configService.savePartialFlat(updates, false);
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("solonclaw:")
                .contains("terminal:")
                .contains("envPassthrough:")
                .contains("TENOR_API_KEY");

        assertEnvPassthroughRejected(configService, "OPENAI_API_KEY", "high-risk");
        assertEnvPassthroughRejected(configService, "PATH", "high-risk");
        assertEnvPassthroughRejected(configService, "LD_PRELOAD", "high-risk");
        assertEnvPassthroughRejected(configService, "PYTHONPATH", "high-risk");
        assertEnvPassthroughRejected(
                configService, "_SOLONCLAW_FORCE_OPENAI_API_KEY", "force prefix");
        assertEnvPassthroughRejected(configService, "BAD-NAME", "invalid env var name");
    }

    @Test
    void shouldWriteTerminalEnvPassthroughCanonicalKey() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        runtimeSettingsService.setConfigValue(
                "solonclaw.terminal.envPassthrough", "TENOR_API_KEY,NOTION_TOKEN");

        String config = FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile());
        assertThat(env.appConfig.getTerminal().getEnvPassthrough())
                .containsExactly("TENOR_API_KEY", "NOTION_TOKEN");
        assertThat(config)
                .contains("solonclaw:")
                .contains("terminal:")
                .contains("envPassthrough:")
                .contains("TENOR_API_KEY")
                .doesNotContain("env_passthrough:");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldRejectHighRiskTerminalEnvPassthroughFromRuntimeSettings() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        assertThatThrownBy(
                        () ->
                                runtimeSettingsService.setConfigValue(
                                        "terminal.env_passthrough", "OPENAI_API_KEY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported config key")
                .hasMessageContaining("terminal.env_passthrough");
        assertThatThrownBy(
                        () ->
                                runtimeSettingsService.setConfigValue(
                                        "solonclaw.terminal.envPassthrough", "PATH"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solonclaw.terminal.envPassthrough")
                .hasMessageContaining("PATH")
                .hasMessageContaining("high-risk");
        assertThat(new java.io.File(env.appConfig.getRuntime().getConfigFile())).doesNotExist();
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldRejectWorkspaceRuntimeKeyWithoutReconnectingChannels() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter(PlatformType.WEIXIN);
        RuntimeSettingsService runtimeSettingsService = runtimeSettingsService(env, adapter);

        assertThatThrownBy(
                        () -> runtimeSettingsService.setConfigValue("solonclaw.workspace", "./workspace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported config key")
                .hasMessageContaining("solonclaw.workspace");
        assertThat(adapter.disconnectCount).isZero();
        assertThat(adapter.connectCount).isZero();
    }

    @Test
    void shouldRejectUnsafeWebsiteBlocklistSharedFilePathsFromDashboardWrites() throws Exception {
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
        assertThat(result.getConfigFile()).isEqualTo("workspace://config.yml");
        assertThat(result.getConfigFile())
                .doesNotContain(env.appConfig.getRuntime().getHome())
                .doesNotContain(env.appConfig.getRuntime().getConfigFile());
        assertThat(env.appConfig.getReact().getMaxSteps()).isEqualTo(50);
    }

    @Test
    void shouldRefreshPluginEnableDisableLists() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  plugins:\n"
                        + "    enabled:\n"
                        + "      - browser-use\n"
                        + "      - speech-local\n"
                        + "    disabled:\n"
                        + "      - legacy-demo\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isTrue();
        assertThat(env.appConfig.getPlugins().getEnabled())
                .containsExactly("browser-use", "speech-local");
        assertThat(env.appConfig.getPlugins().getDisabled()).containsExactly("legacy-demo");
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
        assertThat(result.getConfigFile()).isEqualTo("workspace://config.yml");
        assertThat(result.getConfigFile())
                .doesNotContain(env.appConfig.getRuntime().getHome())
                .doesNotContain(env.appConfig.getRuntime().getConfigFile());
        assertThat(env.appConfig.getReact().getMaxSteps()).isEqualTo(previousMaxSteps);
    }

    @Test
    void shouldRejectInvalidSchedulerIntegerBeforeRefreshing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        int previousTimeout = env.appConfig.getScheduler().getScriptTimeoutSeconds();
        FileUtil.writeUtf8String(
                "solonclaw:\n  scheduler:\n    scriptTimeoutSeconds: wrong\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("solonclaw.scheduler.scriptTimeoutSeconds");
        assertThat(env.appConfig.getScheduler().getScriptTimeoutSeconds())
                .isEqualTo(previousTimeout);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRetainLastRuntimeRefreshFailureWithRedactedMessage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String secretPath = env.appConfig.getRuntime().getHome() + "/secrets/token-file.txt";
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    note: "
                        + secretPath
                        + " token=ghp_runtimefailure12345\n"
                        + "    broken: [\n",
                env.appConfig.getRuntime().getConfigFile());

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshConfigOnly();
        Map<String, Object> failure = env.gatewayRuntimeRefreshService.lastFailureSnapshot();

        assertThat(result.isSuccess()).isFalse();
        assertThat(failure)
                .containsEntry("type", "validation")
                .containsEntry("config_file", "workspace://config.yml")
                .containsEntry("validation_failure", Boolean.TRUE);
        assertThat(failure.get("failed_at")).isInstanceOf(Long.class);
        String failureJson = org.noear.snack4.ONode.serialize(failure);
        assertThat(failureJson)
                .contains("workspace/config.yml 格式错误")
                .contains("[REDACTED_PATH]")
                .contains("***")
                .doesNotContain(secretPath)
                .doesNotContain(env.appConfig.getRuntime().getHome())
                .doesNotContain(env.appConfig.getRuntime().getConfigFile())
                .doesNotContain("ghp_runtimefailure12345");
    }

    @Test
    void shouldRedactSkippedRuntimeConfigRefreshPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        GatewayRuntimeRefreshService.RefreshResult result =
                env.gatewayRuntimeRefreshService.refreshIfNeeded();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRefreshed()).isFalse();
        assertThat(result.getConfigFile()).isEqualTo("workspace://config.yml");
        assertThat(result.getConfigFile())
                .doesNotContain(env.appConfig.getRuntime().getHome())
                .doesNotContain(env.appConfig.getRuntime().getConfigFile());
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
        assertThat(result.getMessage()).contains("workspace/config.yml 格式错误");
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
                            public UrlVerdict checkAlwaysBlockedUrl(String url) {
                                return UrlVerdict.block(url, "blocked-by-test");
                            }
                        });
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("baseUrl", "https://api.example.test");
        body.put("dialect", "openai");
        body.put("apiKey", "test-key");

        assertThatThrownBy(() -> providerService.listRemoteModels(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.baseUrl 被 URL 安全底线阻止")
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
    void shouldAllowProviderModelListPublicUrlWithoutInteractiveApproval() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"data\":[{\"id\":\"runtime-model\"}]}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
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
                            new ProviderPublicUrlApprovalSecurityPolicyService(config));
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            body.put("dialect", "openai");

            Map<String, Object> result = providerService.listRemoteModels(body);

            assertThat(result.get("models")).asList().containsExactly("runtime-model");
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

    @Test
    void shouldReuseFreshProviderModelListCacheAndFallbackWhenRefreshFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            final int[] hits = new int[] {0};
            final boolean[] fail = new boolean[] {false};
            server.createContext(
                    "/v1/models",
                    exchange -> {
                        hits[0]++;
                        String body =
                                fail[0]
                                        ? "{\"error\":\"temporary failure\"}"
                                        : "{\"data\":[{\"id\":\"cached-model\"}]}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(fail[0] ? 500 : 200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            AppConfig config = new AppConfig();
            ExpiringDashboardProviderService providerService =
                    new ExpiringDashboardProviderService(
                            config,
                            null,
                            new LlmProviderService(config),
                            new AllowLocalButBlockMetadataSecurityPolicyService(config));
            providerService.now = 10_000L;
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            body.put("dialect", "openai");

            Map<String, Object> first = providerService.listRemoteModels(body);
            providerService.now = 10_500L;
            Map<String, Object> second = providerService.listRemoteModels(body);
            fail[0] = true;
            providerService.now = 12_000L;
            Map<String, Object> third = providerService.listRemoteModels(body);

            assertThat(first.get("models")).asList().containsExactly("cached-model");
            assertThat(second.get("models")).asList().containsExactly("cached-model");
            assertThat(third.get("models")).asList().containsExactly("cached-model");
            assertThat(second.get("cache")).isEqualTo("hit");
            assertThat(third.get("cache")).isEqualTo("stale");
            assertThat(hits[0]).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldValidateProviderRuntimeSuccessAndRejectionStatuses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/valid/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"data\":[{\"id\":\"runtime-model\"}]}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.createContext(
                    "/rejected/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"error\":\"invalid api key\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(401, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.createContext(
                    "/limited/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(429, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            DashboardProviderService providerService = localProviderService(new AppConfig());
            int port = server.getAddress().getPort();

            Map<String, Object> valid =
                    providerService.validateProvider(
                            providerProbe("http://127.0.0.1:" + port + "/valid", "openai"));
            Map<String, Object> rejected =
                    providerService.validateProvider(
                            providerProbe("http://127.0.0.1:" + port + "/rejected", "openai"));
            Map<String, Object> limited =
                    providerService.validateProvider(
                            providerProbe("http://127.0.0.1:" + port + "/limited", "openai"));

            assertThat(valid.get("ok")).isEqualTo(Boolean.TRUE);
            assertThat(valid.get("reachable")).isEqualTo(Boolean.TRUE);
            assertThat(valid.get("status")).isEqualTo("valid");
            assertThat(valid.get("models")).asList().containsExactly("runtime-model");
            assertThat(rejected.get("ok")).isEqualTo(Boolean.FALSE);
            assertThat(rejected.get("reachable")).isEqualTo(Boolean.TRUE);
            assertThat(rejected.get("status")).isEqualTo("rejected");
            assertThat(limited.get("ok")).isEqualTo(Boolean.TRUE);
            assertThat(limited.get("reachable")).isEqualTo(Boolean.TRUE);
            assertThat(limited.get("status")).isEqualTo("rate_limited");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldValidateAnthropicProviderWhenModelListEndpointIsUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/anthropic/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "<html><body>Not Found</body></html>"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(404, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.createContext(
                    "/anthropic/v1/messages",
                    exchange -> {
                        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                        assertThat(exchange.getRequestHeaders().getFirst("x-api-key"))
                                .isEqualTo("sk-provider-anthropic-secret");
                        assertThat(exchange.getRequestHeaders().getFirst("anthropic-version"))
                                .isEqualTo("2023-06-01");
                        String body =
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8);
                        assertThat(body).contains("\"model\":\"mimo-v2.5\"");
                        byte[] bytes =
                                "{\"id\":\"msg_test\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            DashboardProviderService providerService = localProviderService(new AppConfig());
            Map<String, Object> body =
                    providerProbe(
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/anthropic",
                            "anthropic");
            body.put("apiKey", "sk-provider-anthropic-secret");
            body.put("model", "mimo-v2.5");

            Map<String, Object> result = providerService.validateProvider(body);

            assertThat(result.get("ok")).isEqualTo(Boolean.TRUE);
            assertThat(result.get("reachable")).isEqualTo(Boolean.TRUE);
            assertThat(result.get("status")).isEqualTo("valid");
            assertThat(String.valueOf(result.get("url")))
                    .endsWith("/anthropic/v1/messages");
            assertThat(result.get("models")).asList().containsExactly("mimo-v2.5");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactProviderRuntimeValidationErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/v1/models",
                    exchange -> {
                        byte[] bytes =
                                "{\"error\":\"api_key=sk-provider-validate-secret token=ghp_providervalidate12345\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            DashboardProviderService providerService = localProviderService(new AppConfig());

            Map<String, Object> result =
                    providerService.validateProvider(
                            providerProbe(
                                    "http://127.0.0.1:" + server.getAddress().getPort(), "openai"));

            assertThat(result.get("ok")).isEqualTo(Boolean.FALSE);
            assertThat(result.get("reachable")).isEqualTo(Boolean.TRUE);
            assertThat(result.get("status")).isEqualTo("error");
            assertThat(String.valueOf(result.get("message")))
                    .contains("HTTP 500")
                    .contains("api_key=***")
                    .contains("token=***")
                    .doesNotContain("sk-provider-validate-secret")
                    .doesNotContain("ghp_providervalidate12345");
        } finally {
            server.stop(0);
        }

        DashboardProviderService providerService =
                new ThrowingDashboardProviderService(
                        new AppConfig(), "connect failed token=ghp_unreachablevalidate12345");
        Map<String, Object> unreachable =
                providerService.validateProvider(providerProbe("http://127.0.0.1:1", "openai"));

        assertThat(unreachable.get("ok")).isEqualTo(Boolean.FALSE);
        assertThat(unreachable.get("reachable")).isEqualTo(Boolean.FALSE);
        assertThat(unreachable.get("status")).isEqualTo("unreachable");
        assertThat(String.valueOf(unreachable.get("message")))
                .doesNotContain("ghp_unreachablevalidate12345")
                .contains("***");
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
        updates.put("solonclaw.terminal.credentialFiles", Collections.singletonList(value));
        assertThatThrownBy(() -> configService.savePartialFlat(updates, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solonclaw.terminal.credentialFiles")
                .hasMessageContaining(messagePart);
    }

    private void assertEnvPassthroughRejected(
            DashboardConfigService configService, String value, String messagePart) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("solonclaw.terminal.envPassthrough", Collections.singletonList(value));
        assertThatThrownBy(() -> configService.savePartialFlat(updates, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solonclaw.terminal.envPassthrough")
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

    private DashboardProviderService localProviderService(AppConfig config) {
        return new DashboardProviderService(
                config,
                null,
                new LlmProviderService(config),
                new AllowLocalButBlockMetadataSecurityPolicyService(config));
    }

    private Map<String, Object> providerProbe(String baseUrl, String dialect) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("baseUrl", baseUrl);
        body.put("dialect", dialect);
        return body;
    }

    private static class ProviderPublicUrlApprovalSecurityPolicyService
            extends SecurityPolicyService {
        private ProviderPublicUrlApprovalSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        public UrlVerdict checkAlwaysBlockedUrl(String url) {
            if (url != null && url.contains("127.0.0.1")) {
                return UrlVerdict.allow();
            }
            return super.checkAlwaysBlockedUrl(url);
        }

        @Override
        public UrlVerdict checkUrlSafety(String url, Boolean allowPrivateOverride) {
            if (url != null && url.contains("127.0.0.1")) {
                return UrlVerdict.allow();
            }
            return super.checkUrlSafety(url, allowPrivateOverride);
        }

        @Override
        public UrlVerdict checkUrl(String url) {
            if (url != null && url.contains("127.0.0.1")) {
                return UrlVerdict.approvalRequired(
                        url, "network_external_operation", "网络外部操作需要审批");
            }
            return super.checkUrl(url);
        }
    }

    private static class ExpiringDashboardProviderService extends DashboardProviderService {
        private long now;

        private ExpiringDashboardProviderService(
                AppConfig appConfig,
                GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
                LlmProviderService llmProviderService,
                SecurityPolicyService securityPolicyService) {
            super(
                    appConfig,
                    gatewayRuntimeRefreshService,
                    llmProviderService,
                    securityPolicyService);
        }

        @Override
        protected long currentTimeMillis() {
            return now;
        }

        @Override
        protected long modelListCacheTtlMillis() {
            return 1000L;
        }
    }

    private static class ThrowingDashboardProviderService extends DashboardProviderService {
        private final String message;

        private ThrowingDashboardProviderService(AppConfig appConfig, String message) {
            super(
                    appConfig,
                    null,
                    new LlmProviderService(appConfig),
                    new AllowLocalButBlockMetadataSecurityPolicyService(appConfig));
            this.message = message;
        }

        @Override
        protected HttpResponse executeModelListRequest(
                String url, String apiKey, String dialect, int redirectCount) {
            throw new IllegalStateException(message);
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
