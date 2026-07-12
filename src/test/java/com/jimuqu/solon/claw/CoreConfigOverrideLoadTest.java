package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class CoreConfigOverrideLoadTest {
    @Test
    void shouldLoadCoreAndChannelConfigFromRuntimeConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-core-config").toFile();
        File configFile = new File(workspaceHome, "config.yml");
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
                        + "    bootstrapPromptFileCharLimit: 9000\n"
                        + "    bootstrapPromptTotalCharBudget: 36000\n"
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
                        + "  guardrailCronScope: global\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
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
        assertThat(config.getTask().getBootstrapPromptFileCharLimit()).isEqualTo(9000);
        assertThat(config.getTask().getBootstrapPromptTotalCharBudget()).isEqualTo(36000);
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
        assertThat(config.getSecurity().isRewriteBrowserLoopbackUrls()).isTrue();
        assertThat(config.getSecurity().getBrowserLoopbackHostAlias())
                .isEqualTo("host.containers.internal");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isFalse();
        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("bypass");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("approve");
        assertThat(config.getSecurity().getGuardrailCronScope()).isEqualTo("global");
        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("bypass");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("approve");
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

    /** 安全 URL 配置必须从 Profile 的 config.yml 进入真实策略，而不是只出现在配置模型中。 */
    @Test
    void shouldLoadUrlSafetyConfigurationIntoRuntimePolicy() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-url-policy-config").toFile();
        File sharedRules = new File(workspaceHome, "blocked-websites.txt");
        Files.write(
                sharedRules.toPath(),
                java.util.Collections.singletonList("shared.example"),
                StandardCharsets.UTF_8);
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  allowPrivateUrls: true\n"
                        + "  websiteBlocklist:\n"
                        + "    enabled: true\n"
                        + "    domains:\n"
                        + "      - blocked.example\n"
                        + "    sharedFiles:\n"
                        + "      - "
                        + sharedRules.getAbsolutePath().replace("\\", "\\\\")
                        + "\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        SecurityPolicyService policy = new SecurityPolicyService(config);

        assertThat(config.getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().isEnabled()).isTrue();
        assertThat(config.getSecurity().getWebsiteBlocklist().getDomains())
                .containsExactly("blocked.example");
        assertThat(config.getSecurity().getWebsiteBlocklist().getSharedFiles())
                .containsExactly(sharedRules.getAbsolutePath());
        assertThat(policy.checkUrlSafety("https://blocked.example/path", null).isAllowed())
                .isFalse();
        assertThat(policy.checkUrlSafety("https://shared.example/path", null).isAllowed())
                .isFalse();
    }

    @Test
    void shouldFallbackInvalidWeixinTextBatchDelaysToDefaults() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-weixin-text-batch").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  channels:\n"
                        + "    weixin:\n"
                        + "      textBatchDelaySeconds: NaN\n"
                        + "      textBatchSplitDelaySeconds: -1\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getChannels().getWeixin().getTextBatchDelaySeconds()).isEqualTo(3.0D);
        assertThat(config.getChannels().getWeixin().getTextBatchSplitDelaySeconds())
                .isEqualTo(5.0D);
    }

    @Test
    void shouldExcludeEnvrcFromDefaultRollbackCheckpoints() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-rollback-defaults").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getRollback().getExcludePatterns()).contains(".env", ".envrc", ".env.*");
    }

    @Test
    void shouldUseDefaultToolOutputMaxBytes() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-tool-output-default").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldCoerceCanonicalToolOutputStringIntegers() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-tool-output-string").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  task:\n"
                        + "    toolOutputInlineLimit: \"75000\"\n"
                        + "    toolOutputTurnBudget: \"125000\"\n"
                        + "    toolOutputMaxLines: \"222\"\n"
                        + "    toolOutputMaxLineLength: \"333\"\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(75000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(125000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(222);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(333);
    }

    @Test
    void shouldFallbackForInvalidToolOutputValues() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-tool-output-invalid").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  task:\n"
                        + "    toolOutputInlineLimit: -1\n"
                        + "    toolOutputTurnBudget: -2\n"
                        + "    toolOutputMaxLines: 0\n"
                        + "    toolOutputMaxLineLength: not-a-number\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTask().getToolOutputInlineLimit()).isEqualTo(50000);
        assertThat(config.getTask().getToolOutputTurnBudget()).isEqualTo(200000);
        assertThat(config.getTask().getToolOutputMaxLines()).isEqualTo(2000);
        assertThat(config.getTask().getToolOutputMaxLineLength()).isEqualTo(2000);
    }

    @Test
    void shouldLoadCanonicalTerminalCredentialFiles() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-terminal-config").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/solonclaw-token.json\n"
                        + "    envPassthrough:\n"
                        + "    - TENOR_API_KEY\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/solonclaw-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
    }

    @Test
    void shouldLoadCanonicalScopedTerminalKeys() throws Exception {
        File workspaceHome =
                Files.createTempDirectory("solonclaw-terminal-current-config").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    credentialFiles:\n"
                        + "      - credentials/team-token.json\n"
                        + "    envPassthrough:\n"
                        + "      - TENOR_API_KEY\n"
                        + "    sudoPassword: current-pass\n"
                        + "    writeSafeRoot: D:/workspace/current-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getCredentialFiles())
                .containsExactly("credentials/team-token.json");
        assertThat(config.getTerminal().getEnvPassthrough()).containsExactly("TENOR_API_KEY");
        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("current-pass");
        assertThat(config.getTerminal().getWriteSafeRoot()).isEqualTo("D:/workspace/current-safe");
    }

    @Test
    void shouldLoadCanonicalTerminalShellInitKeys() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-terminal-init").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    shellInitFiles:\n"
                        + "    - ~/.profile\n"
                        + "    - ~/.bashrc\n"
                        + "    autoSourceBashrc: false\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getShellInitFiles())
                .containsExactly("~/.profile", "~/.bashrc");
        assertThat(config.getTerminal().isAutoSourceBashrc()).isFalse();
    }

    @Test
    void shouldDefaultAgentApprovalAndCronStrictModes() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-guardrail-defaults").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().isTirithFailOpen()).isTrue();
        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("approval");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("strict");
    }

    @Test
    void shouldLoadSecurityGuardrailModeAndCronModeWithJobScopeDefault() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-guardrail-mode").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n" + "  guardrailMode: bypass\n" + "  guardrailCronMode: approval\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("bypass");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("approval");
        assertThat(config.getSecurity().getGuardrailCronScope()).isEqualTo("job");
        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("bypass");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("approval");
    }

    @Test
    void shouldRejectUnsupportedGuardrailModeValues() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-guardrail-invalid").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "security:\n"
                        + "  guardrailMode: supervise\n"
                        + "  guardrailCronMode: queue\n"
                        + "  guardrailCronScope: forever\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.guardrailMode");
    }

    @Test
    void shouldLoadCanonicalExternalSkillsDirs() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-external-skills").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  skills:\n"
                        + "    externalDirs:\n"
                        + "    - external/team-skills\n"
                        + "    - D:/shared/skills\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSkills().getExternalDirs())
                .containsExactly("external/team-skills", "D:/shared/skills");
    }

    @Test
    void shouldLoadCanonicalSkillPreprocessKeys() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-skill-preprocess").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  skills:\n"
                        + "    templateVars: false\n"
                        + "    inlineShell: true\n"
                        + "    inlineShellTimeoutSeconds: 7\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSkills().isTemplateVars()).isFalse();
        assertThat(config.getSkills().isInlineShell()).isTrue();
        assertThat(config.getSkills().getInlineShellTimeoutSeconds()).isEqualTo(7);
    }

    @Test
    void shouldLoadCanonicalTerminalSudoPassword() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-terminal-sudo").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n" + "  terminal:\n" + "    sudoPassword: solonclaw-pass\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getSudoPassword()).isEqualTo("solonclaw-pass");
    }

    @Test
    void shouldLoadCanonicalTerminalTimeout() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-terminal-timeout").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n" + "  terminal:\n" + "    processWaitTimeoutSeconds: 9\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getProcessWaitTimeoutSeconds()).isEqualTo(9);
    }

    @Test
    void shouldLoadCanonicalTerminalWriteSafeRoot() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-terminal-write-root").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  terminal:\n"
                        + "    writeSafeRoot: D:/workspace/solonclaw-safe\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getTerminal().getWriteSafeRoot())
                .isEqualTo("D:/workspace/solonclaw-safe");
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
