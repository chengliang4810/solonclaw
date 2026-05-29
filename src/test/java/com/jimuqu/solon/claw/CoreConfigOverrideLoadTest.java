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
                        + "  terminal:\n"
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
                        + "      sendChunkRetries: 9\n"
                        + "jimuqu:\n"
                        + "  approvals:\n"
                        + "    mode: off\n"
                        + "    cronMode: approve\n"
                        + "    timeoutSeconds: 45\n"
                        + "    gatewayTimeoutSeconds: 120\n"
                        + "    mcpReloadConfirm: false\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/oauth.json\n"
                        + "    envPassthrough:\n"
                        + "      - TENOR_API_KEY\n"
                        + "    sudoPassword: runtime-pass\n"
                        + "    writeSafeRoot: D:/workspace/runtime\n"
                        + "  security:\n"
                        + "    allowPrivateUrls: true\n"
                        + "security:\n"
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
        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example");
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly("shared-blocklist.txt");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isFalse();
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
        assertThat(config.getChannels().getWeixin().getSendChunkRetries()).isEqualTo(9);
    }

    @Test
    void shouldLoadJimuquToolOutputAliases() throws Exception {
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

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(12345);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(67890);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2345);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(3456);
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
    void shouldLoadDdgsWebSearchBackendFromLegacyConfigKey() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-web-ddgs").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String("web:\n  search_backend: ddgs\n", configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getWeb().getSearchBackend()).isEqualTo("ddgs");
    }

    @Test
    void shouldUseJimuquDefaultToolOutputMaxBytes() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-default").toFile();

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldCoerceJimuquToolOutputStringIntegers() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-string").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "tool_output:\n"
                        + "  max_bytes: \"75000\"\n"
                        + "  turn_budget_bytes: \"125000\"\n"
                        + "  max_lines: \"222\"\n"
                        + "  max_line_length: \"333\"\n",
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
    void shouldFallbackForInvalidJimuquToolOutputValues() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-invalid").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "tool_output:\n"
                        + "  max_bytes: -1\n"
                        + "  turn_budget_bytes: -2\n"
                        + "  max_lines: 0\n"
                        + "  max_line_length: not-a-number\n",
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
    void shouldFallbackWhenJimuquToolOutputSectionIsNotMap() throws Exception {
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
    void shouldLoadJimuquTerminalCredentialFilesAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  credential_files:\n"
                        + "    - credentials/jimuqu-token.json\n"
                        + "  env_passthrough:\n"
                        + "    - TENOR_API_KEY\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/jimuqu-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
    }

    @Test
    void shouldLoadScopedJimuquTerminalAliases() throws Exception {
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

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/jimuqu-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("Jimuqu-pass");
        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/jimuqu-safe");
    }

    @Test
    void shouldIgnoreLegacyScopedTerminalAliases() throws Exception {
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

        assertThat(config.getTerminal().getCredentialFiles()).isEmpty();
        assertThat(config.getTerminal().getEnvPassthrough()).isEmpty();
        assertThat(config.getTerminal().getSudoPassword()).isBlank();
        assertThat(config.getTerminal().getWriteSafeRoot()).isBlank();
    }

    @Test
    void shouldLoadJimuquTerminalShellInitAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-init").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  shell_init_files:\n"
                        + "    - ~/.profile\n"
                        + "    - ~/.bashrc\n"
                        + "  auto_source_bashrc: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getShellInitFiles())
                .containsExactly("~/.profile", "~/.bashrc");
        assertThat(config.getTerminal().isAutoSourceBashrc()).isFalse();
    }

    @Test
    void shouldTreatJimuquBooleanFalseApprovalModeAsOff() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-approvals-mode").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "approvals:\n"
                        + "  mode: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getApprovals().getMode()).isEqualTo("off");
    }

    @Test
    void shouldLoadScopedJimuquApprovalsAliases() throws Exception {
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

        assertThat(config.getApprovals().getMode()).isEqualTo("off");
        assertThat(config.getApprovals().getCronMode()).isEqualTo("approve");
        assertThat(config.getApprovals().isSubagentAutoApprove()).isTrue();
        assertThat(config.getApprovals().getTimeoutSeconds()).isEqualTo(45);
        assertThat(config.getApprovals().getGatewayTimeoutSeconds()).isEqualTo(120);
        assertThat(config.getApprovals().isMcpReloadConfirm()).isFalse();
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
        assertThat(config.getApprovals().getCronMode()).isEqualTo("deny");
        assertThat(config.getApprovals().isSubagentAutoApprove()).isFalse();
        assertThat(config.getApprovals().getTimeoutSeconds()).isEqualTo(60);
        assertThat(config.getApprovals().getGatewayTimeoutSeconds()).isEqualTo(300);
        assertThat(config.getApprovals().isMcpReloadConfirm()).isTrue();
    }

    @Test
    void shouldLoadJimuquExternalSkillsDirsAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-external-skills").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "skills:\n"
                        + "  external_dirs:\n"
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
    void shouldLoadJimuquTerminalSudoPasswordAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-sudo").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  sudo_password: Jimuqu-pass\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("Jimuqu-pass");
    }

    @Test
    void shouldLoadJimuquTerminalTimeoutAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-timeout").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  timeout: 9\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getProcessWaitTimeoutSeconds()).isEqualTo(9);
    }

    @Test
    void shouldLoadJimuquTerminalWriteSafeRootAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-write-root").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  write_safe_root: D:/workspace/jimuqu-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/jimuqu-safe");
    }

    @Test
    void shouldLoadJimuquWebsiteBlocklistAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-website-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  website_blocklist:\n"
                        + "    enabled: true\n"
                        + "    domains:\n"
                        + "      - blocked.example\n"
                        + "      - '*.tracking.example'\n"
                        + "    shared_files:\n"
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
    void shouldApplyLoadedJimuquWebsitePolicyToUrlChecks() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-website-policy-check").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allow_private_urls: true\n"
                        + "  website_blocklist:\n"
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
    void shouldLoadScopedJimuquSecurityPolicyAliases() throws Exception {
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

        assertThat(config.getSecurity().isTirithEnabled()).isFalse();
        assertThat(config.getSecurity().getTirithPath()).isEqualTo("D:/tools/tirith.exe");
        assertThat(config.getSecurity().getTirithTimeoutSeconds()).isEqualTo(9);
        assertThat(config.getSecurity().isTirithFailOpen()).isFalse();
        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example");
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly("team-blocklist.txt");
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
    void shouldLoadJimuquAllowPrivateUrlsAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-private-url-policy").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allow_private_urls: true\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
    }

    @Test
    void shouldLoadScopedJimuquAllowPrivateUrlsAlias() throws Exception {
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
                        + "    allow_private_urls: true\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isFalse();
    }

    @Test
    void shouldLoadJimuquBrowserAllowPrivateUrlsFallback() throws Exception {
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
    void shouldPreferSecurityAllowPrivateUrlsOverBrowserFallback() throws Exception {
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

        assertThat(config.getSecurity().isAllowPrivateUrls()).isFalse();
    }

    @Test
    void shouldTreatQuotedFalseAllowPrivateUrlsAsFalse() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-private-url-false").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allow_private_urls: \"false\"\n",
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
