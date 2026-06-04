package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class CoreConfigOverrideLoadTest {
    @Test
    void shouldLoadCoreAndChannelConfigFromRuntimeConfig() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-core-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  scheduler:\n"
                        + "    enabled: false\n"
                        + "    tickSeconds: 15\n"
                        + "    scriptTimeoutSeconds: 7\n"
                        + "    inactivityTimeoutSeconds: 9\n"
                        + "  react:\n"
                        + "    maxSteps: 14\n"
                        + "    retryMax: 5\n"
                        + "    retryDelayMs: 3000\n"
                        + "    delegateMaxSteps: 22\n"
                        + "    delegateRetryMax: 6\n"
                        + "    delegateRetryDelayMs: 3500\n"
                        + "    summarizationEnabled: false\n"
                        + "    summarizationMaxMessages: 55\n"
                        + "    summarizationMaxTokens: 45000\n"
                        + "  trace:\n"
                        + "    retentionDays: 21\n"
                        + "    maxAttempts: 4\n"
                        + "    toolPreviewLength: 1600\n"
                        + "  task:\n"
                        + "    busyPolicy: steer\n"
                        + "    restartDrainTimeoutSeconds: 240\n"
                        + "    staleAfterMinutes: 45\n"
                        + "    subagentMaxConcurrency: 5\n"
                        + "    subagentMaxDepth: 2\n"
                        + "    toolOutputInlineLimit: 8000\n"
                        + "    toolOutputTurnBudget: 160000\n"
                        + "    toolOutputMaxLines: 5000\n"
                        + "    toolOutputMaxLineLength: 3000\n"
                        + "    mediaCacheTtlHours: 72\n"
                        + "  browser:\n"
                        + "    rewriteLoopbackUrls: true\n"
                        + "    loopbackHostAlias: host.containers.internal\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/oauth.json\n"
                        + "    envPassthrough:\n"
                        + "      - TENOR_API_KEY\n"
                        + "    sudoPassword: runtime-pass\n"
                        + "    writeSafeRoot: D:/workspace/runtime\n"
                        + "    foregroundMaxRetries: 4\n"
                        + "    foregroundRetryBaseDelaySeconds: 1\n"
                        + "    processWaitTimeoutSeconds: 11\n"
                        + "  skills:\n"
                        + "    externalDirs:\n"
                        + "      - external/team-skills\n"
                        + "  mcp:\n"
                        + "    enabled: true\n"
                        + "  web:\n"
                        + "    searchBackend: brave-free\n"
                        + "    braveSearchApiKey: brv-test-key\n"
                        + "  compression:\n"
                        + "    enabled: false\n"
                        + "    thresholdPercent: 0.75\n"
                        + "    summaryModel: gpt-5.4-mini\n"
                        + "  channels:\n"
                        + "    feishu:\n"
                        + "      enabled: true\n"
                        + "      websocketUrl: wss://feishu.example/ws\n"
                        + "    dingtalk:\n"
                        + "      streamUrl: wss://dingtalk.example/stream\n"
                        + "    wecom:\n"
                        + "      websocketUrl: wss://wecom.example/ws\n"
                        + "      groups:\n"
                        + "        room-a:\n"
                        + "          allowFrom:\n"
                        + "            - alice\n"
                        + "            - bob\n"
                        + "        '*':\n"
                        + "          allowFrom:\n"
                        + "            - admin\n"
                        + "    weixin:\n"
                        + "      enabled: true\n"
                        + "      baseUrl: https://weixin.example\n"
                        + "      cdnBaseUrl: https://cdn.example\n"
                        + "      longPollUrl: https://poll.example/ilink/bot/getupdates\n"
                        + "      splitMultilineMessages: true\n"
                        + "      textBatchDelaySeconds: 0.8\n"
                        + "      textBatchSplitDelaySeconds: 1.6\n"
                        + "      sendChunkRetries: 9\n"
                        + "approvals:\n"
                        + "  timeoutSeconds: 45\n"
                        + "  gatewayTimeoutSeconds: 120\n"
                        + "  mcpReloadConfirm: false\n"
                        + "security:\n"
                        + "  guardrailMode: bypass\n"
                        + "  guardrailCronMode: approve\n"
                        + "  guardrailCronScope: global\n"
                        + "  allowPrivateUrls: true\n"
                        + "  websiteBlocklist:\n"
                        + "    enabled: true\n"
                        + "    domains:\n"
                        + "      - blocked.example\n"
                        + "    sharedFiles:\n"
                        + "      - shared-blocklist.txt\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
        props.put("solonclaw.scheduler.enabled", "true");
        props.put("solonclaw.channels.feishu.enabled", "false");
        props.put("solonclaw.channels.weixin.enabled", "false");

        AppConfig config = AppConfig.load(props);

        assertThat(config.getScheduler().isEnabled()).isFalse();
        assertThat(config.getScheduler().getTickSeconds()).isEqualTo(15);
        assertThat(config.getScheduler().getScriptTimeoutSeconds()).isEqualTo(7);
        assertThat(config.getScheduler().getInactivityTimeoutSeconds()).isEqualTo(9);
        assertThat(config.getReact().getMaxSteps()).isEqualTo(14);
        assertThat(config.getReact().getRetryMax()).isEqualTo(5);
        assertThat(config.getReact().getRetryDelayMs()).isEqualTo(3000);
        assertThat(config.getReact().getDelegateMaxSteps()).isEqualTo(22);
        assertThat(config.getReact().getDelegateRetryMax()).isEqualTo(6);
        assertThat(config.getReact().getDelegateRetryDelayMs()).isEqualTo(3500);
        assertThat(config.getReact().isSummarizationEnabled()).isFalse();
        assertThat(config.getReact().getSummarizationMaxMessages()).isEqualTo(55);
        assertThat(config.getReact().getSummarizationMaxTokens()).isEqualTo(45000);
        assertThat(config.getTrace().getRetentionDays()).isEqualTo(21);
        assertThat(config.getTrace().getMaxAttempts()).isEqualTo(4);
        assertThat(config.getTrace().getToolPreviewLength()).isEqualTo(1600);
        assertThat(config.getTask().getBusyPolicy()).isEqualTo("steer");
        assertThat(config.getTask().getRestartDrainTimeoutSeconds()).isEqualTo(240);
        assertThat(config.getTask().getStaleAfterMinutes()).isEqualTo(45);
        assertThat(config.getTask().getSubagentMaxConcurrency()).isEqualTo(5);
        assertThat(config.getTask().getSubagentMaxDepth()).isEqualTo(2);
        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(8000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(160000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(5000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(3000);
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
        assertThat(config.getTask().getMediaCacheTtlHours()).isEqualTo(72);
        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/oauth.json");
        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("runtime-pass");
        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/runtime");
        assertThat(config.getTerminal().getForegroundMaxRetries()).isEqualTo(4);
        assertThat(config.getTerminal().getForegroundRetryBaseDelaySeconds()).isEqualTo(1);
        assertThat(config.getTerminal().getProcessWaitTimeoutSeconds()).isEqualTo(11);
        assertThat(config.getSkills().getExternalDirs()).containsExactly("external/team-skills");
        assertThat(config.getMcp().isEnabled()).isTrue();
        assertThat(config.getWeb().getSearchBackend()).isEqualTo("brave-free");
        assertThat(config.getWeb().getBraveSearchApiKey()).isEqualTo("brv-test-key");
        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(config.getSecurity().isRewriteBrowserLoopbackUrls()).isTrue();
        assertThat(config.getSecurity().getBrowserLoopbackHostAlias())
                .isEqualTo("host.containers.internal");
        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example");
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly("shared-blocklist.txt");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isFalse();
        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("bypass");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("approve");
        assertThat(config.getSecurity().getGuardrailCronScope()).isEqualTo("global");
        assertThat(config.getApprovals().getMode()).isEqualTo("off");
        assertThat(config.getApprovals().getCronMode()).isEqualTo("approve");
        assertThat(config.getApprovals().getTimeoutSeconds()).isEqualTo(45);
        assertThat(config.getApprovals().getGatewayTimeoutSeconds()).isEqualTo(120);
        assertThat(config.getCompression().isEnabled()).isFalse();
        assertThat(config.getCompression().getThresholdPercent()).isEqualTo(0.75D);
        assertThat(config.getCompression().getSummaryModel()).isEqualTo("gpt-5.4-mini");

        assertThat(config.getChannels().getFeishu().isEnabled()).isTrue();
        assertThat(config.getChannels().getFeishu().getWebsocketUrl())
                .isEqualTo("wss://feishu.example/ws");
        assertThat(config.getChannels().getDingtalk().getStreamUrl())
                .isEqualTo("wss://dingtalk.example/stream");
        assertThat(config.getChannels().getWecom().getWebsocketUrl())
                .isEqualTo("wss://wecom.example/ws");
        assertThat(config.getChannels().getWecom().getGroupMemberAllowedUsers().get("room-a"))
                .containsExactly("alice", "bob");
        assertThat(config.getChannels().getWecom().getGroupMemberAllowedUsers().get("*"))
                .containsExactly("admin");
        assertThat(config.getChannels().getWeixin().isEnabled()).isTrue();
        assertThat(config.getChannels().getWeixin().getBaseUrl())
                .isEqualTo("https://weixin.example");
        assertThat(config.getChannels().getWeixin().getCdnBaseUrl())
                .isEqualTo("https://cdn.example");
        assertThat(config.getChannels().getWeixin().getLongPollUrl())
                .isEqualTo("https://poll.example/ilink/bot/getupdates");
        assertThat(config.getChannels().getWeixin().isSplitMultilineMessages()).isTrue();
        assertThat(config.getChannels().getWeixin().getTextBatchDelaySeconds()).isEqualTo(0.8D);
        assertThat(config.getChannels().getWeixin().getTextBatchSplitDelaySeconds())
                .isEqualTo(1.6D);
        assertThat(config.getChannels().getWeixin().getSendChunkRetries()).isEqualTo(9);
    }

    @Test
    void shouldIgnoreLegacyRuntimeConfigKeys() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-legacy-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "jimuqu:\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/legacy.json\n"
                        + "    envPassthrough:\n"
                        + "      - LEGACY_TOKEN\n"
                        + "    sudoPassword: legacy-pass\n"
                        + "    writeSafeRoot: /legacy/root\n"
                        + "  security:\n"
                        + "    allowPrivateUrls: false\n"
                        + "  approvals:\n"
                        + "    mode: off\n"
                        + "tool_output:\n"
                        + "  max_bytes: 9\n"
                        + "  turn_budget_bytes: 10\n"
                        + "  max_lines: 11\n"
                        + "  max_line_length: 12\n"
                        + "browser:\n"
                        + "  rewrite_loopback_urls: true\n"
                        + "  loopback_host_alias: legacy.host\n"
                        + "web:\n"
                        + "  search_backend: ddgs\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles()).isEmpty();
        assertThat(config.getTerminal().getEnvPassthrough()).isEmpty();
        assertThat(config.getTerminal().getSudoPassword()).isBlank();
        assertThat(config.getTerminal().getWriteSafeRoot()).isBlank();
        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(200000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(config.getSecurity().isRewriteBrowserLoopbackUrls()).isFalse();
        assertThat(config.getSecurity().getBrowserLoopbackHostAlias())
                .isEqualTo("host.docker.internal");
        assertThat(config.getWeb().getSearchBackend()).isEqualTo("solon-ai");
        assertThat(config.getApprovals().getMode()).isEqualTo("on");
    }

    @Test
    void shouldFallbackInvalidWeixinTextBatchDelaysToDefaults() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-weixin-text-batch").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  channels:\n"
                        + "    weixin:\n"
                        + "      textBatchDelaySeconds: NaN\n"
                        + "      textBatchSplitDelaySeconds: -1\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getChannels().getWeixin().getTextBatchDelaySeconds()).isEqualTo(3.0D);
        assertThat(config.getChannels().getWeixin().getTextBatchSplitDelaySeconds())
                .isEqualTo(5.0D);
    }

    @Test
    void shouldIgnoreLegacyToolOutputAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "tool_output:\n"
                        + "  max_bytes: 12345\n"
                        + "  turn_budget_bytes: 67890\n"
                        + "  max_lines: 2345\n"
                        + "  max_line_length: 3456\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(200000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldExcludeEnvrcFromDefaultRollbackCheckpoints() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-rollback-defaults").toFile();

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getRollback().getExcludePatterns())
                .contains(".env", ".envrc", ".env.*");
    }

    @Test
    void shouldIgnoreLegacyWebSearchBackendConfigKey() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-web-ddgs").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String("web:\n  search_backend: ddgs\n", configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getWeb().getSearchBackend()).isEqualTo("solon-ai");
    }

    @Test
    void shouldUseDefaultToolOutputMaxBytes() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-default").toFile();

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldCoerceCanonicalToolOutputStringIntegers() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-string").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  task:\n"
                        + "    toolOutputInlineLimit: \"75000\"\n"
                        + "    toolOutputTurnBudget: \"125000\"\n"
                        + "    toolOutputMaxLines: \"222\"\n"
                        + "    toolOutputMaxLineLength: \"333\"\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(75000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(125000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(222);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(333);
    }

    @Test
    void shouldFallbackForInvalidToolOutputValues() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-invalid").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  task:\n"
                        + "    toolOutputInlineLimit: -1\n"
                        + "    toolOutputTurnBudget: -2\n"
                        + "    toolOutputMaxLines: 0\n"
                        + "    toolOutputMaxLineLength: not-a-number\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(200000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldIgnoreLegacyToolOutputSectionWhenItIsNotMap() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-section").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String("tool_output: nonsense\n", configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldLoadCanonicalTerminalCredentialFiles() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/solonclaw-token.json\n"
                        + "    envPassthrough:\n"
                        + "    - TENOR_API_KEY\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/solonclaw-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
    }

    @Test
    void shouldIgnoreScopedJimuquTerminalAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-jimuqu-terminal-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "jimuqu:\n"
                        + "  terminal:\n"
                        + "    credential_files:\n"
                        + "      - credentials/jimuqu-token.json\n"
                        + "    env_passthrough:\n"
                        + "      - TENOR_API_KEY\n"
                        + "    sudo_password: Jimuqu-pass\n"
                        + "    write_safe_root: D:/workspace/jimuqu-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles()).isEmpty();
        assertThat(config.getTerminal().getEnvPassthrough()).isEmpty();
        assertThat(config.getTerminal().getSudoPassword()).isBlank();
        assertThat(config.getTerminal().getWriteSafeRoot()).isBlank();
    }

    @Test
    void shouldLoadCanonicalScopedTerminalKeys() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-legacy-terminal-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/legacy-token.json\n"
                        + "    envPassthrough:\n"
                        + "      - LEGACY_API_KEY\n"
                        + "    sudoPassword: legacy-pass\n"
                        + "    writeSafeRoot: D:/workspace/legacy-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/legacy-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("LEGACY_API_KEY");
        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("legacy-pass");
        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/legacy-safe");
    }

    @Test
    void shouldLoadCanonicalTerminalShellInitKeys() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-init").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    shellInitFiles:\n"
                        + "    - ~/.profile\n"
                        + "    - ~/.bashrc\n"
                        + "    autoSourceBashrc: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getShellInitFiles())
                .containsExactly("~/.profile", "~/.bashrc");
        assertThat(config.getTerminal().isAutoSourceBashrc()).isFalse();
    }

    @Test
    void shouldIgnoreApprovalsModeCompatibilityValue() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-approvals-mode").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "approvals:\n"
                        + "  mode: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getApprovals().getMode()).isEqualTo("on");
    }

    @Test
    void shouldLoadSecurityGuardrailModeAndCronModeWithJobScopeDefault() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-guardrail-mode").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  guardrailMode: bypass\n"
                        + "  guardrailCronMode: approval\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("bypass");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("approval");
        assertThat(config.getSecurity().getGuardrailCronScope()).isEqualTo("job");
        assertThat(config.getApprovals().getMode()).isEqualTo("off");
        assertThat(config.getApprovals().getCronMode()).isEqualTo("approval");
    }

    @Test
    void shouldLoadSecurityHardlineAllowlistFromYamlList() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-hardline-allowlist").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  hardlineAllowlist:\n"
                        + "    - hardline_shutdown\n"
                        + "    - hardline_windows_shutdown\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().getHardlineAllowlist())
                .containsExactly("hardline_shutdown", "hardline_windows_shutdown");
    }

    @Test
    void shouldIgnoreScopedJimuquApprovalsAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-jimuqu-approvals").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "jimuqu:\n"
                        + "  approvals:\n"
                        + "    mode: off\n"
                        + "    cron_mode: approve\n"
                        + "    subagent_auto_approve: true\n"
                        + "    timeout: 45\n"
                        + "    gateway_timeout: 120\n"
                        + "    mcp_reload_confirm: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getApprovals().getMode()).isEqualTo("on");
        assertThat(config.getApprovals().getCronMode()).isEqualTo("approval");
        assertThat(config.getApprovals().isSubagentAutoApprove()).isFalse();
        assertThat(config.getApprovals().getTimeoutSeconds()).isEqualTo(60);
        assertThat(config.getApprovals().getGatewayTimeoutSeconds()).isEqualTo(300);
        assertThat(config.getApprovals().isMcpReloadConfirm()).isTrue();
    }

    @Test
    void shouldIgnoreLegacyScopedApprovalsAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-legacy-approvals").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  approvals:\n"
                        + "    mode: off\n"
                        + "    cron_mode: approve\n"
                        + "    subagent_auto_approve: true\n"
                        + "    timeout: 45\n"
                        + "    gateway_timeout: 120\n"
                        + "    mcp_reload_confirm: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getApprovals().getMode()).isEqualTo("on");
        assertThat(config.getApprovals().getCronMode()).isEqualTo("approval");
        assertThat(config.getApprovals().isSubagentAutoApprove()).isFalse();
        assertThat(config.getApprovals().getTimeoutSeconds()).isEqualTo(60);
        assertThat(config.getApprovals().getGatewayTimeoutSeconds()).isEqualTo(300);
        assertThat(config.getApprovals().isMcpReloadConfirm()).isTrue();
    }

    @Test
    void shouldLoadCanonicalExternalSkillsDirs() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-external-skills").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  skills:\n"
                        + "    externalDirs:\n"
                        + "    - external/team-skills\n"
                        + "    - D:/shared/skills\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSkills().getExternalDirs())
                .containsExactly("external/team-skills", "D:/shared/skills");
    }

    @Test
    void shouldLoadCanonicalSkillPreprocessKeys() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-skill-preprocess").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  skills:\n"
                        + "    templateVars: false\n"
                        + "    inlineShell: true\n"
                        + "    inlineShellTimeoutSeconds: 7\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSkills().isTemplateVars()).isFalse();
        assertThat(config.getSkills().isInlineShell()).isTrue();
        assertThat(config.getSkills().getInlineShellTimeoutSeconds()).isEqualTo(7);
    }

    @Test
    void shouldLoadCanonicalTerminalSudoPassword() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-sudo").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    sudoPassword: SolonClaw-pass\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("SolonClaw-pass");
    }

    @Test
    void shouldLoadCanonicalTerminalTimeout() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-timeout").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    processWaitTimeoutSeconds: 9\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getProcessWaitTimeoutSeconds()).isEqualTo(9);
    }

    @Test
    void shouldLoadCanonicalTerminalWriteSafeRoot() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-write-root").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    writeSafeRoot: D:/workspace/solonclaw-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/solonclaw-safe");
    }

    @Test
    void shouldLoadCanonicalWebsiteBlocklist() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-website-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  websiteBlocklist:\n"
                        + "    enabled: true\n"
                        + "    domains:\n"
                        + "      - blocked.example\n"
                        + "      - '*.tracking.example'\n"
                        + "    sharedFiles:\n"
                        + "      - community-blocklist.txt\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example", "*.tracking.example");
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly("community-blocklist.txt");
    }

    @Test
    void shouldApplyLoadedWebsitePolicyToUrlChecks() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-website-policy-check").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allowPrivateUrls: true\n"
                        + "  websiteBlocklist:\n"
                        + "    enabled: true\n"
                        + "    domains:\n"
                        + "      - blocked.example\n"
                        + "      - '*.tracking.example'\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "93.184.216.34");

        assertThat(policy.checkUrl("http://127.0.0.1:8080/status").isAllowed()).isTrue();
        assertThat(policy.checkUrl("https://docs.blocked.example/page").isAllowed()).isFalse();
        assertThat(policy.checkUrl("https://a.tracking.example/pixel").isAllowed()).isFalse();
        assertThat(policy.checkUrl("https://tracking.example/pixel").isAllowed()).isTrue();
    }

    @Test
    void shouldIgnoreScopedJimuquSecurityPolicyAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-jimuqu-security-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "jimuqu:\n"
                        + "  security:\n"
                        + "    tirith_enabled: false\n"
                        + "    tirith_path: D:/tools/tirith.exe\n"
                        + "    tirith_timeout: 9\n"
                        + "    tirith_fail_open: false\n"
                        + "    website_blocklist:\n"
                        + "      enabled: true\n"
                        + "      domains:\n"
                        + "        - blocked.example\n"
                        + "      shared_files:\n"
                        + "        - team-blocklist.txt\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isTirithEnabled()).isTrue();
        assertThat(config.getSecurity().getTirithPath()).isEqualTo("tirith");
        assertThat(config.getSecurity().getTirithTimeoutSeconds()).isEqualTo(5);
        assertThat(config.getSecurity().isTirithFailOpen()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isFalse();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains()).isEmpty();
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles()).isEmpty();
    }

    @Test
    void shouldIgnoreLegacyScopedSecurityPolicyAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-legacy-security-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  security:\n"
                        + "    tirithEnabled: false\n"
                        + "    tirithPath: D:/tools/legacy-tirith.exe\n"
                        + "    tirithTimeoutSeconds: 9\n"
                        + "    tirithFailOpen: false\n"
                        + "    websiteBlocklist:\n"
                        + "      enabled: true\n"
                        + "      domains:\n"
                        + "        - legacy.example\n"
                        + "      sharedFiles:\n"
                        + "        - legacy-blocklist.txt\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isTirithEnabled()).isTrue();
        assertThat(config.getSecurity().getTirithPath()).isEqualTo("tirith");
        assertThat(config.getSecurity().getTirithTimeoutSeconds()).isEqualTo(5);
        assertThat(config.getSecurity().isTirithFailOpen()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isFalse();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains()).isEmpty();
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles()).isEmpty();
    }

    @Test
    void shouldLoadCanonicalAllowPrivateUrlsKey() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-private-url-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allowPrivateUrls: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isFalse();
    }

    @Test
    void shouldIgnoreScopedJimuquAllowPrivateUrlsAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-jimuqu-private-url-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "jimuqu:\n"
                        + "  security:\n"
                        + "    allow_private_urls: true\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
    }

    @Test
    void shouldIgnoreLegacyScopedAllowPrivateUrlsAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-legacy-private-url-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  security:\n"
                        + "    allow_private_urls: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
    }

    @Test
    void shouldIgnoreBrowserAllowPrivateUrlsFallback() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-browser-private-url").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "browser:\n"
                        + "  allow_private_urls: true\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
    }

    @Test
    void shouldIgnoreLegacySecurityAllowPrivateUrlsSnakeCaseAndBrowserFallback() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-private-url-precedence").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allow_private_urls: false\n"
                        + "browser:\n"
                        + "  allow_private_urls: true\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
    }

    @Test
    void shouldTreatQuotedFalseCanonicalAllowPrivateUrlsAsFalse() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-private-url-false").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allowPrivateUrls: \"false\"\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isFalse();
    }

    private static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        private final String ip;

        private FixedDnsSecurityPolicyService(AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }
    }
}
