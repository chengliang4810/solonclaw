package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
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
                        + "    credentialFiles:\n"
                        + "      - credentials/oauth.json\n"
                        + "    envPassthrough:\n"
                        + "      - TENOR_API_KEY\n"
                        + "    sudoPassword: runtime-pass\n"
                        + "    writeSafeRoot: D:/workspace/runtime\n"
                        + "    foregroundMaxRetries: 4\n"
                        + "    foregroundRetryBaseDelaySeconds: 1\n"
                        + "  skills:\n"
                        + "    externalDirs:\n"
                        + "      - external/team-skills\n"
                        + "  mcp:\n"
                        + "    enabled: true\n"
                        + "  security:\n"
                        + "    allowPrivateUrls: true\n"
                        + "    websiteBlocklist:\n"
                        + "      enabled: true\n"
                        + "      domains:\n"
                        + "        - blocked.example\n"
                        + "      sharedFiles:\n"
                        + "        - shared-blocklist.txt\n"
                        + "  approvals:\n"
                        + "    mode: off\n"
                        + "    cronMode: approve\n"
                        + "    timeoutSeconds: 45\n"
                        + "    gatewayTimeoutSeconds: 120\n"
                        + "    mcpReloadConfirm: false\n"
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
                        + "      sendChunkRetries: 9\n",
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
        assertThat(config.getSkills().getExternalDirs()).containsExactly("external/team-skills");
        assertThat(config.getMcp().isEnabled()).isTrue();
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
    void shouldLoadHermesToolOutputAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "tool_output:\n"
                        + "  max_bytes: 12345\n"
                        + "  max_lines: 2345\n"
                        + "  max_line_length: 3456\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(12345);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2345);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(3456);
    }

    @Test
    void shouldUseHermesDefaultToolOutputMaxBytes() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-default").toFile();

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldCoerceHermesToolOutputStringIntegers() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-string").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "tool_output:\n"
                        + "  max_bytes: \"75000\"\n"
                        + "  max_lines: \"222\"\n"
                        + "  max_line_length: \"333\"\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(75000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(222);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(333);
    }

    @Test
    void shouldFallbackForInvalidHermesToolOutputValues() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-tool-output-invalid").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "tool_output:\n"
                        + "  max_bytes: -1\n"
                        + "  max_lines: 0\n"
                        + "  max_line_length: not-a-number\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldFallbackWhenHermesToolOutputSectionIsNotMap() throws Exception {
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
    void shouldLoadHermesTerminalCredentialFilesAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-config").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  credential_files:\n"
                        + "    - credentials/hermes-token.json\n"
                        + "  env_passthrough:\n"
                        + "    - TENOR_API_KEY\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/hermes-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
    }

    @Test
    void shouldLoadHermesTerminalShellInitAliases() throws Exception {
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
    void shouldTreatHermesBooleanFalseApprovalModeAsOff() throws Exception {
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
    void shouldLoadHermesExternalSkillsDirsAlias() throws Exception {
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
    void shouldLoadHermesTerminalSudoPasswordAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-sudo").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  sudo_password: hermes-pass\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("hermes-pass");
    }

    @Test
    void shouldLoadHermesTerminalWriteSafeRootAlias() throws Exception {
        File runtimeHome = Files.createTempDirectory("solon-claw-terminal-write-root").toFile();
        File configFile = new File(runtimeHome, "config.yml");
        FileUtil.writeUtf8String(
                "terminal:\n"
                        + "  write_safe_root: D:/workspace/hermes-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/hermes-safe");
    }

    @Test
    void shouldLoadHermesWebsiteBlocklistAlias() throws Exception {
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
    void shouldLoadHermesAllowPrivateUrlsAlias() throws Exception {
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
    void shouldLoadHermesBrowserAllowPrivateUrlsFallback() throws Exception {
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
}
