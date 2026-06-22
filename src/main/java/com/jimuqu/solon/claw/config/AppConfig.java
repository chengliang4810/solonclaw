package com.jimuqu.solon.claw.config;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.support.constants.CheckpointConstants;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** 应用级配置对象，负责承接 Solon 配置并做外部 config.yml 覆盖与路径标准化。 */
@Getter
@Setter
@NoArgsConstructor
public class AppConfig {
    /** 配置加载日志，异常摘要只记录类型，避免把配置值或密钥写入日志。 */
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** 运行时目录配置。 */
    private RuntimeConfig runtime = new RuntimeConfig();

    /** Agent 工作区配置。 */
    private WorkspaceConfig workspace = new WorkspaceConfig();

    /** 大模型接入配置。 */
    private LlmConfig llm = new LlmConfig();

    /** 多 provider 原始配置。 */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<String, ProviderConfig>();

    /** 当前主模型选择。 */
    private ModelConfig model = new ModelConfig();

    /** 主模型故障切换链。 */
    private List<FallbackProviderConfig> fallbackProviders =
            new ArrayList<FallbackProviderConfig>();

    /** 定时任务调度配置。 */
    private SchedulerConfig scheduler = new SchedulerConfig();

    /** 上下文压缩配置。 */
    private CompressionConfig compression = new CompressionConfig();

    /** 任务后自动学习配置。 */
    private LearningConfig learning = new LearningConfig();

    /** 技能后台维护配置。 */
    private CuratorConfig curator = new CuratorConfig();

    /** 技能目录配置。 */
    private SkillsConfig skills = new SkillsConfig();

    /** 文件快照与回滚配置。 */
    private RollbackConfig rollback = new RollbackConfig();

    /** 聊天窗口显示配置。 */
    private DisplayConfig display = new DisplayConfig();

    /** 各渠道接入配置。 */
    private ChannelsConfig channels = new ChannelsConfig();

    /** 网关通用授权配置。 */
    private GatewayConfig gateway = new GatewayConfig();

    /** 记录应用中的控制台。 */
    private DashboardConfig dashboard = new DashboardConfig();

    /** Agent 运行配置。 */
    private AgentConfig agent = new AgentConfig();

    /** 主动协作运行配置。 */
    private ProactiveConfig proactive = new ProactiveConfig();

    /** ReAct 运行配置。 */
    private ReActConfig react = new ReActConfig();

    /** Agent run 追踪配置。 */
    private TraceConfig trace = new TraceConfig();

    /** 长任务控制配置。 */
    private TaskConfig task = new TaskConfig();

    /** 终端/沙箱执行配置。 */
    private TerminalConfig terminal = new TerminalConfig();

    /** 安全策略配置。 */
    private SecurityConfig security = new SecurityConfig();

    /** Web 工具配置。 */
    private WebConfig web = new WebConfig();

    /** 记录应用中的价格。 */
    private PricingConfig pricing = new PricingConfig();

    /** 记录应用中的plugins。 */
    private PluginConfig plugins = new PluginConfig();

    /** 审批/确认策略配置。 */
    private ApprovalsConfig approvals = new ApprovalsConfig();

    /** MCP 工具适配配置。 */
    private McpConfig mcp = new McpConfig();

    /**
     * 从 Solon Props 构建应用配置。
     *
     * @param props Solon 启动时加载的配置源
     * @return 标准化后的配置对象
     */
    public static AppConfig load(Props props) {
        AppConfig config = new AppConfig();
        File userDir = new File(System.getProperty("user.dir"));
        File runtimeHome = asAbsoluteStatic(new File(resolveInitialRuntimeHome(props)), userDir);
        initializeRuntimeConfigIfMissing(runtimeHome);
        Map<String, Object> overrides = loadFlatOverrides(runtimeHome);
        Map<String, Object> structuredOverrides = loadStructuredOverrides(runtimeHome);
        RuntimeConfigResolver configResolver =
                RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());

        config.getRuntime().setHome(resolveConfigString(runtimeHome.getPath()));
        config.getRuntime()
                .setContextDir(
                        runtimeChildPath(
                                config.getRuntime().getHome(),
                                RuntimePathConstants.CONTEXT_DIR_NAME));
        config.getRuntime()
                .setSkillsDir(
                        runtimeChildPath(
                                config.getRuntime().getHome(),
                                RuntimePathConstants.SKILLS_DIR_NAME));
        config.getRuntime()
                .setCacheDir(
                        runtimeChildPath(
                                config.getRuntime().getHome(),
                                RuntimePathConstants.CACHE_DIR_NAME));
        config.getRuntime()
                .setStateDb(
                        runtimeChildPath(
                                config.getRuntime().getHome(),
                                RuntimePathConstants.DATA_DIR_NAME,
                                RuntimePathConstants.STATE_DB_FILE_NAME));
        config.getRuntime().setConfigFile(configResolver.configFile().getPath());
        config.getRuntime()
                .setLogsDir(
                        runtimeChildPath(
                                config.getRuntime().getHome(), RuntimePathConstants.LOGS_DIR_NAME));
        config.getWorkspace()
                .setDir(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.workspace",
                                        RuntimePathConstants.DEFAULT_WORKSPACE)));

        config.getLlm()
                .setStream(
                        resolveBoolean(
                                readBoolean(props, overrides, "solonclaw.llm.stream", true)));
        config.getLlm()
                .setReasoningEffort(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.llm.reasoningEffort",
                                        RuntimePathConstants.DEFAULT_REASONING_EFFORT)));
        config.getLlm()
                .setTemperature(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.llm.temperature",
                                        RuntimePathConstants.DEFAULT_TEMPERATURE)));
        config.getLlm()
                .setMaxTokens(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.llm.maxTokens",
                                                RuntimePathConstants.DEFAULT_MAX_TOKENS)),
                                RuntimePathConstants.DEFAULT_MAX_TOKENS));
        config.getLlm()
                .setContextWindowTokens(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.llm.contextWindowTokens",
                                        RuntimePathConstants.DEFAULT_CONTEXT_WINDOW_TOKENS)));
        config.getLlm()
                .getPromptCache()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.llm.promptCache.enabled",
                                        false)));
        config.getLlm()
                .getPromptCache()
                .setTtl(
                        resolveConfigString(
                                readString(
                                        props, overrides, "solonclaw.llm.promptCache.ttl", "5m")));
        config.getLlm()
                .getPromptCache()
                .setLayout(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.llm.promptCache.layout",
                                        "system_and_3")));
        applyProviderConfiguration(config, props, overrides, structuredOverrides);
        applyPricingConfiguration(config, props, overrides, structuredOverrides);
        applyPluginConfiguration(config, props, overrides, structuredOverrides);

        config.getScheduler()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "solonclaw.scheduler.enabled", true)));
        config.getScheduler()
                .setTickSeconds(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.scheduler.tickSeconds",
                                        RuntimePathConstants.DEFAULT_SCHEDULER_TICK_SECONDS)));
        config.getScheduler()
                .setWrapResponse(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.scheduler.wrapResponse",
                                        true)));
        config.getScheduler()
                .setScriptTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.scheduler.scriptTimeoutSeconds",
                                                120)),
                                120));
        config.getScheduler()
                .setInactivityTimeoutSeconds(
                        nonNegativeInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.scheduler.inactivityTimeoutSeconds",
                                                600)),
                                600));
        config.getScheduler()
                .setEnabledToolsets(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.scheduler.enabledToolsets",
                                        "")));

        config.getCompression()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "solonclaw.compression.enabled", true)));
        config.getCompression()
                .setThresholdPercent(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.compression.thresholdPercent",
                                        CompressionConstants.DEFAULT_THRESHOLD_PERCENT)));
        config.getCompression()
                .setSummaryModel(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.compression.summaryModel",
                                        "")));
        config.getCompression()
                .setProtectHeadMessages(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.compression.protectHeadMessages",
                                        CompressionConstants.DEFAULT_PROTECT_HEAD_MESSAGES)));
        config.getCompression()
                .setTailRatio(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.compression.tailRatio",
                                        CompressionConstants.DEFAULT_TAIL_RATIO)));

        config.getLearning()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(props, overrides, "solonclaw.learning.enabled", true)));
        config.getLearning()
                .setToolCallThreshold(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.learning.toolCallThreshold",
                                        5)));
        config.getLearning()
                .setAuxiliaryTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.learning.auxiliaryTimeoutSeconds",
                                                60)),
                                60));
        config.getCurator()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.skills.curator.enabled",
                                        true)));
        config.getCurator()
                .setIntervalHours(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.skills.curator.intervalHours",
                                        168)));
        config.getCurator()
                .setMinIdleHours(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.skills.curator.minIdleHours",
                                        2.0D)));
        config.getCurator()
                .setStaleAfterDays(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.skills.curator.staleAfterDays",
                                        30)));
        config.getCurator()
                .setArchiveAfterDays(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.skills.curator.archiveAfterDays",
                                        90)));
        config.getSkills()
                .setExternalDirs(
                        resolveList(
                                readRaw(props, overrides, "solonclaw.skills.externalDirs", "")));
        config.getSkills()
                .setTemplateVars(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "solonclaw.skills.templateVars", true)));
        config.getSkills()
                .setInlineShell(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "solonclaw.skills.inlineShell", false)));
        config.getSkills()
                .setInlineShellTimeoutSeconds(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.skills.inlineShellTimeoutSeconds",
                                        10)));

        config.getRollback()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(props, overrides, "solonclaw.rollback.enabled", true)));
        config.getRollback()
                .setMaxCheckpointsPerSource(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.rollback.maxCheckpointsPerSource",
                                        CheckpointConstants.DEFAULT_MAX_CHECKPOINTS_PER_SOURCE)));
        config.getRollback()
                .setMaxFileSizeMb(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.rollback.maxFileSizeMb",
                                        CheckpointConstants.DEFAULT_MAX_FILE_SIZE_MB)));
        config.getRollback()
                .setExcludePatterns(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.rollback.excludePatterns",
                                        config.getRollback().getExcludePatterns())));

        config.getDisplay()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.display.toolProgress",
                                        "all")));
        config.getDisplay()
                .setShowReasoning(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.display.showReasoning",
                                        false)));
        config.getDisplay()
                .setResumeDisplay(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.display.resumeDisplay",
                                        "full")));
        config.getDisplay()
                .setToolPreviewLength(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.display.toolPreviewLength",
                                        80)));
        config.getDisplay()
                .setProgressThrottleMs(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.display.progressThrottleMs",
                                        1500)));
        config.getDisplay()
                .getRuntimeFooter()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.display.runtimeFooter.enabled",
                                        false)));
        config.getDisplay()
                .getRuntimeFooter()
                .setFields(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.runtimeFooter.fields",
                                        "model,context_pct,cwd")));

        applyChannelConfig(
                config.getChannels().getFeishu(),
                props,
                overrides,
                "feishu",
                GatewayBehaviorConstants.DM_POLICY_OPEN,
                GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST);
        config.getChannels()
                .getFeishu()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.enabled",
                                        false)));
        config.getChannels()
                .getFeishu()
                .setAppId(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.feishu.appId", "")));
        config.getChannels()
                .getFeishu()
                .setAppSecret(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.appSecret",
                                        "")));
        config.getChannels()
                .getFeishu()
                .setDomain(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.domain",
                                        "feishu")));
        config.getChannels()
                .getFeishu()
                .setWebsocketUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.websocketUrl",
                                        "")));
        config.getChannels()
                .getFeishu()
                .setBotOpenId(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.botOpenId",
                                        "")));
        config.getChannels()
                .getFeishu()
                .setBotUserId(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.botUserId",
                                        "")));
        config.getChannels()
                .getFeishu()
                .setBotName(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.botName",
                                        "")));
        config.getChannels()
                .getFeishu()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.toolProgress",
                                        "all")));
        config.getChannels()
                .getFeishu()
                .setCommentEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.comment.enabled",
                                        false)));
        config.getChannels()
                .getFeishu()
                .setCommentPairingFile(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.feishu.comment.pairingFile",
                                        "")));
        config.getChannels()
                .getFeishu()
                .setRuntimeFooterEnabled(
                        resolveOptionalBoolean(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.platforms.feishu.runtimeFooter.enabled",
                                        null)));

        applyChannelConfig(
                config.getChannels().getDingtalk(),
                props,
                overrides,
                "dingtalk",
                GatewayBehaviorConstants.DM_POLICY_OPEN,
                GatewayBehaviorConstants.GROUP_POLICY_OPEN);
        config.getChannels()
                .getDingtalk()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.enabled",
                                        false)));
        config.getChannels()
                .getDingtalk()
                .setClientId(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.clientId",
                                        "")));
        config.getChannels()
                .getDingtalk()
                .setClientSecret(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.clientSecret",
                                        "")));
        config.getChannels()
                .getDingtalk()
                .setRobotCode(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.robotCode",
                                        "")));
        config.getChannels()
                .getDingtalk()
                .setCoolAppCode(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.coolAppCode",
                                        "")));
        config.getChannels()
                .getDingtalk()
                .setStreamUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.streamUrl",
                                        "")));
        config.getChannels()
                .getDingtalk()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.toolProgress",
                                        "all")));
        config.getChannels()
                .getDingtalk()
                .setProgressCardTemplateId(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.progressCardTemplateId",
                                        "")));
        config.getChannels()
                .getDingtalk()
                .setAiCardStreamingEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.dingtalk.aiCardStreaming.enabled",
                                        true)));
        config.getChannels()
                .getDingtalk()
                .setRuntimeFooterEnabled(
                        resolveOptionalBoolean(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.platforms.dingtalk.runtimeFooter.enabled",
                                        null)));

        applyChannelConfig(
                config.getChannels().getWecom(),
                props,
                overrides,
                "wecom",
                GatewayBehaviorConstants.DM_POLICY_OPEN,
                GatewayBehaviorConstants.GROUP_POLICY_OPEN);
        config.getChannels()
                .getWecom()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.wecom.enabled",
                                        false)));
        config.getChannels()
                .getWecom()
                .setBotId(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.wecom.botId", "")));
        config.getChannels()
                .getWecom()
                .setSecret(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.wecom.secret", "")));
        config.getChannels()
                .getWecom()
                .setWebsocketUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.wecom.websocketUrl",
                                        "")));
        config.getChannels()
                .getWecom()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.wecom.toolProgress",
                                        "all")));
        config.getChannels()
                .getWecom()
                .setGroupMemberAllowedUsers(
                        collectGroupAllowMap(
                                props,
                                overrides,
                                "solonclaw.channels.wecom.groups.",
                                "solonclaw.channels.wecom.groupMemberAllowedUsers"));
        config.getChannels()
                .getWecom()
                .setRuntimeFooterEnabled(
                        resolveOptionalBoolean(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.platforms.wecom.runtimeFooter.enabled",
                                        null)));

        applyChannelConfig(
                config.getChannels().getWeixin(),
                props,
                overrides,
                "weixin",
                GatewayBehaviorConstants.DM_POLICY_OPEN,
                GatewayBehaviorConstants.GROUP_POLICY_DISABLED);
        config.getChannels()
                .getWeixin()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.enabled",
                                        false)));
        config.getChannels()
                .getWeixin()
                .setToken(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.weixin.token", "")));
        config.getChannels()
                .getWeixin()
                .setAccountId(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.accountId",
                                        "")));
        config.getChannels()
                .getWeixin()
                .setBaseUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.baseUrl",
                                        "")));
        config.getChannels()
                .getWeixin()
                .setCdnBaseUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.cdnBaseUrl",
                                        "")));
        config.getChannels()
                .getWeixin()
                .setLongPollUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.longPollUrl",
                                        "")));
        config.getChannels()
                .getWeixin()
                .setSplitMultilineMessages(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.splitMultilineMessages",
                                        false)));
        config.getChannels()
                .getWeixin()
                .setTextBatchDelaySeconds(
                        nonNegativeFiniteDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.textBatchDelaySeconds",
                                        3.0D),
                                3.0D));
        config.getChannels()
                .getWeixin()
                .setTextBatchSplitDelaySeconds(
                        nonNegativeFiniteDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.textBatchSplitDelaySeconds",
                                        5.0D),
                                5.0D));
        config.getChannels()
                .getWeixin()
                .setSendChunkDelaySeconds(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.sendChunkDelaySeconds",
                                        0.35D)));
        config.getChannels()
                .getWeixin()
                .setSendChunkRetries(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.sendChunkRetries",
                                        2)));
        config.getChannels()
                .getWeixin()
                .setSendChunkRetryDelaySeconds(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.sendChunkRetryDelaySeconds",
                                        1.0D)));
        config.getChannels()
                .getWeixin()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.weixin.toolProgress",
                                        "off")));
        config.getChannels()
                .getWeixin()
                .setRuntimeFooterEnabled(
                        resolveOptionalBoolean(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.platforms.weixin.runtimeFooter.enabled",
                                        null)));

        applyChannelConfig(
                config.getChannels().getQqbot(),
                props,
                overrides,
                "qqbot",
                GatewayBehaviorConstants.DM_POLICY_OPEN,
                GatewayBehaviorConstants.GROUP_POLICY_OPEN);
        config.getChannels()
                .getQqbot()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.qqbot.enabled",
                                        false)));
        config.getChannels()
                .getQqbot()
                .setAppId(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.qqbot.appId", "")));
        config.getChannels()
                .getQqbot()
                .setClientSecret(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.qqbot.clientSecret",
                                        "")));
        config.getChannels()
                .getQqbot()
                .setApiDomain(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.qqbot.apiDomain",
                                        "")));
        config.getChannels()
                .getQqbot()
                .setWebsocketUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.qqbot.websocketUrl",
                                        "")));
        config.getChannels()
                .getQqbot()
                .setMarkdownSupport(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.qqbot.markdownSupport",
                                        true)));
        config.getChannels()
                .getQqbot()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.qqbot.toolProgress",
                                        "all")));
        config.getChannels()
                .getQqbot()
                .setRuntimeFooterEnabled(
                        resolveOptionalBoolean(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.platforms.qqbot.runtimeFooter.enabled",
                                        null)));

        applyChannelConfig(
                config.getChannels().getYuanbao(),
                props,
                overrides,
                "yuanbao",
                GatewayBehaviorConstants.DM_POLICY_OPEN,
                GatewayBehaviorConstants.GROUP_POLICY_OPEN);
        config.getChannels()
                .getYuanbao()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.channels.yuanbao.enabled",
                                        false)));
        config.getChannels()
                .getYuanbao()
                .setAppId(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.yuanbao.appId", "")));
        config.getChannels()
                .getYuanbao()
                .setAppSecret(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.yuanbao.appSecret",
                                        "")));
        config.getChannels()
                .getYuanbao()
                .setBotId(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.channels.yuanbao.botId", "")));
        config.getChannels()
                .getYuanbao()
                .setApiDomain(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.yuanbao.apiDomain",
                                        "")));
        config.getChannels()
                .getYuanbao()
                .setWebsocketUrl(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.yuanbao.websocketUrl",
                                        "")));
        config.getChannels()
                .getYuanbao()
                .setToolProgress(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.channels.yuanbao.toolProgress",
                                        "all")));
        config.getChannels()
                .getYuanbao()
                .setRuntimeFooterEnabled(
                        resolveOptionalBoolean(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.platforms.yuanbao.runtimeFooter.enabled",
                                        null)));

        config.getGateway()
                .setAllowedUsers(
                        resolveList(
                                readRaw(props, overrides, "solonclaw.gateway.allowedUsers", "")));
        config.getGateway()
                .setAllowAllUsers(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.gateway.allowAllUsers",
                                        false)));
        config.getGateway()
                .setInjectionSecret(
                        resolveSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.gateway.injectionSecret",
                                        "")));
        config.getGateway()
                .setInjectionMaxBodyBytes(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.gateway.injectionMaxBodyBytes",
                                        65536)));
        config.getGateway()
                .setInjectionReplayWindowSeconds(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.gateway.injectionReplayWindowSeconds",
                                        300)));
        config.getGateway()
                .setFilterSilenceNarration(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.gateway.filterSilenceNarration",
                                        true)));
        config.getGateway()
                .setPlatforms(loadGatewayPlatforms(props, overrides, structuredOverrides));
        config.getDashboard()
                .setAccessToken(
                        resolveSecret(
                                readString(
                                        props, overrides, "solonclaw.dashboard.accessToken", "")));
        config.getDashboard()
                .setBindHost(resolveConfigString(readString(props, overrides, "server.host", "")));
        config.getDashboard()
                .setBindPort(resolveInt(readInt(props, overrides, "server.port", 8080)));
        config.getAgent().setPersonalities(loadPersonalities(props, overrides));
        config.getAgent()
                .getHeartbeat()
                .setIntervalMinutes(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.agent.heartbeat.intervalMinutes",
                                        RuntimePathConstants.DEFAULT_HEARTBEAT_INTERVAL_MINUTES)));
        ProactiveConfig proactiveDefaults = new ProactiveConfig();
        config.getProactive()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.enabled",
                                        proactiveDefaults.isEnabled())));
        config.getProactive()
                .setIntervalMinutes(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.intervalMinutes",
                                        proactiveDefaults.getIntervalMinutes())));
        config.getProactive()
                .setInitialDelaySeconds(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.initialDelaySeconds",
                                        proactiveDefaults.getInitialDelaySeconds())));
        config.getProactive()
                .setDailyMaxContacts(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.dailyMaxContacts",
                                        proactiveDefaults.getDailyMaxContacts())));
        config.getProactive()
                .setCooldownMinutes(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.cooldownMinutes",
                                        proactiveDefaults.getCooldownMinutes())));
        config.getProactive()
                .setQuietStartHour(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.quietStartHour",
                                        proactiveDefaults.getQuietStartHour())));
        config.getProactive()
                .setQuietEndHour(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.quietEndHour",
                                        proactiveDefaults.getQuietEndHour())));
        config.getProactive()
                .setMinConfidenceToContact(
                        resolveDouble(
                                readDouble(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.minConfidenceToContact",
                                        proactiveDefaults.getMinConfidenceToContact())));
        config.getProactive()
                .setLlmDecisionEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.llmDecisionEnabled",
                                        proactiveDefaults.isLlmDecisionEnabled())));
        config.getProactive()
                .setLlmPolishEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.llmPolishEnabled",
                                        proactiveDefaults.isLlmPolishEnabled())));
        config.getProactive()
                .setMaxCandidatesPerTick(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.maxCandidatesPerTick",
                                        proactiveDefaults.getMaxCandidatesPerTick())));
        config.getProactive()
                .setMaxContactsPerTick(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.maxContactsPerTick",
                                        proactiveDefaults.getMaxContactsPerTick())));
        config.getProactive()
                .setCandidateTtlHours(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.candidateTtlHours",
                                        proactiveDefaults.getCandidateTtlHours())));
        config.getProactive()
                .setRepositoryCheckEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.repositoryCheckEnabled",
                                        proactiveDefaults.isRepositoryCheckEnabled())));
        config.getProactive()
                .setRepositoryCheckIntervalMinutes(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.repositoryCheckIntervalMinutes",
                                        proactiveDefaults.getRepositoryCheckIntervalMinutes())));
        config.getProactive()
                .setSessionLookbackDays(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.sessionLookbackDays",
                                        proactiveDefaults.getSessionLookbackDays())));
        config.getProactive()
                .setRunLookbackDays(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.runLookbackDays",
                                        proactiveDefaults.getRunLookbackDays())));
        config.getProactive()
                .setCronLookbackDays(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.cronLookbackDays",
                                        proactiveDefaults.getCronLookbackDays())));
        config.getProactive()
                .setCareCheckinEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.careCheckinEnabled",
                                        proactiveDefaults.isCareCheckinEnabled())));
        config.getProactive()
                .setCareCheckinAfterIdleHours(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.careCheckinAfterIdleHours",
                                        proactiveDefaults.getCareCheckinAfterIdleHours())));
        config.getProactive()
                .setDeliveryPreviewPrefix(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.proactive.deliveryPreviewPrefix",
                                        proactiveDefaults.getDeliveryPreviewPrefix())));
        config.getReact()
                .setMaxSteps(resolveInt(readInt(props, overrides, "solonclaw.react.maxSteps", 12)));
        config.getReact()
                .setRetryMax(resolveInt(readInt(props, overrides, "solonclaw.react.retryMax", 3)));
        config.getReact()
                .setRetryDelayMs(
                        resolveInt(
                                readInt(props, overrides, "solonclaw.react.retryDelayMs", 2000)));
        config.getReact()
                .setDelegateMaxSteps(
                        resolveInt(
                                readInt(props, overrides, "solonclaw.react.delegateMaxSteps", 18)));
        config.getReact()
                .setDelegateRetryMax(
                        resolveInt(
                                readInt(props, overrides, "solonclaw.react.delegateRetryMax", 4)));
        config.getReact()
                .setDelegateRetryDelayMs(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.delegateRetryDelayMs",
                                        2500)));
        config.getReact()
                .setSummarizationEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.react.summarizationEnabled",
                                        true)));
        config.getReact()
                .setSummarizationMaxMessages(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.summarizationMaxMessages",
                                        40)));
        config.getReact()
                .setSummarizationMaxTokens(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.summarizationMaxTokens",
                                        32000)));
        config.getReact()
                .setToolLoopWarningsEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopWarningsEnabled",
                                        true)));
        config.getReact()
                .setToolLoopHardStopEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopHardStopEnabled",
                                        false)));
        config.getReact()
                .setToolLoopExactFailureWarnAfter(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopExactFailureWarnAfter",
                                        2)));
        config.getReact()
                .setToolLoopExactFailureBlockAfter(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopExactFailureBlockAfter",
                                        5)));
        config.getReact()
                .setToolLoopSameToolFailureWarnAfter(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopSameToolFailureWarnAfter",
                                        3)));
        config.getReact()
                .setToolLoopSameToolFailureHaltAfter(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopSameToolFailureHaltAfter",
                                        8)));
        config.getReact()
                .setToolLoopNoProgressWarnAfter(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopNoProgressWarnAfter",
                                        2)));
        config.getReact()
                .setToolLoopNoProgressBlockAfter(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.react.toolLoopNoProgressBlockAfter",
                                        5)));
        config.getTrace()
                .setRetentionDays(
                        resolveInt(readInt(props, overrides, "solonclaw.trace.retentionDays", 14)));
        config.getTrace()
                .setMaxAttempts(
                        resolveInt(readInt(props, overrides, "solonclaw.trace.maxAttempts", 2)));
        config.getTrace()
                .setToolPreviewLength(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.trace.toolPreviewLength",
                                        1200)));
        config.getTask()
                .setBusyPolicy(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.task.busyPolicy",
                                        "interrupt")));
        config.getTask()
                .setRestartDrainTimeoutSeconds(
                        Math.max(
                                0,
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.task.restartDrainTimeoutSeconds",
                                                180))));
        config.getTask()
                .setStaleAfterMinutes(
                        resolveInt(
                                readInt(props, overrides, "solonclaw.task.staleAfterMinutes", 60)));
        config.getTask()
                .setSubagentMaxConcurrency(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.task.subagentMaxConcurrency",
                                        3)));
        config.getTask()
                .setSubagentMaxDepth(
                        resolveInt(
                                readInt(props, overrides, "solonclaw.task.subagentMaxDepth", 1)));
        config.getTask()
                .setToolOutputInlineLimit(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.task.toolOutputInlineLimit",
                                                50000)),
                                50000));
        config.getTask()
                .setToolOutputTurnBudget(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.task.toolOutputTurnBudget",
                                                200000)),
                                200000));
        config.getTask()
                .setToolOutputMaxLines(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.task.toolOutputMaxLines",
                                                2000)),
                                2000));
        config.getTask()
                .setToolOutputMaxLineLength(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.task.toolOutputMaxLineLength",
                                                2000)),
                                2000));
        config.getTask()
                .setMediaCacheTtlHours(
                        resolveInt(
                                readInt(
                                        props,
                                        overrides,
                                        "solonclaw.task.mediaCacheTtlHours",
                                        168)));
        config.getSecurity()
                .setAllowPrivateUrls(resolveBoolean(readAllowPrivateUrls(props, overrides)));
        config.getSecurity()
                .setRewriteBrowserLoopbackUrls(
                        resolveBoolean(readBrowserLoopbackRewriteEnabled(props, overrides)));
        config.getSecurity()
                .setBrowserLoopbackHostAlias(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.browser.loopbackHostAlias",
                                        "host.docker.internal")));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "security.websiteBlocklist.enabled",
                                        false)));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setDomains(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "security.websiteBlocklist.domains",
                                        "")));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "security.websiteBlocklist.sharedFiles",
                                        "")));
        config.getSecurity()
                .setTirithEnabled(
                        resolveBoolean(
                                readBoolean(props, overrides, "security.tirithEnabled", true)));
        config.getSecurity()
                .setTirithPath(
                        resolveConfigString(
                                readString(props, overrides, "security.tirithPath", "tirith")));
        config.getSecurity()
                .setTirithTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "security.tirithTimeoutSeconds",
                                                5)),
                                5));
        config.getSecurity()
                .setTirithFailOpen(
                        resolveBoolean(
                                readBoolean(props, overrides, "security.tirithFailOpen", false)));
        config.getSecurity()
                .setFileGuardrailMode(
                        normalizeBinaryGuardrailMode(
                                resolveConfigString(
                                        readString(
                                                props,
                                                overrides,
                                                "security.fileGuardrailMode",
                                                "strict"))));
        config.getSecurity()
                .setUrlGuardrailMode(
                        normalizeBinaryGuardrailMode(
                                resolveConfigString(
                                        readString(
                                                props,
                                                overrides,
                                                "security.urlGuardrailMode",
                                                "strict"))));
        config.getSecurity()
                .setGuardrailMode(
                        normalizeGuardrailMode(
                                resolveConfigString(
                                        readString(
                                                props,
                                                overrides,
                                                "security.guardrailMode",
                                                "approval"))));
        config.getSecurity()
                .setGuardrailCronMode(
                        normalizeGuardrailCronMode(
                                resolveConfigString(
                                        readString(
                                                props,
                                                overrides,
                                                "security.guardrailCronMode",
                                                "strict"))));
        config.getSecurity()
                .setGuardrailCronScope(
                        normalizeGuardrailCronScope(
                                resolveConfigString(
                                        readString(
                                                props,
                                                overrides,
                                                "security.guardrailCronScope",
                                                "job"))));
        config.getSecurity()
                .setHardlineAllowlist(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "security.hardlineAllowlist",
                                        defaultHardlineAllowlist())));
        config.getApprovals()
                .setSubagentAutoApprove(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "approvals.subagentAutoApprove", false)));
        config.getApprovals()
                .setTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(props, overrides, "approvals.timeoutSeconds", 60)),
                                60));
        config.getApprovals()
                .setGatewayTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "approvals.gatewayTimeoutSeconds",
                                                300)),
                                300));
        config.getApprovals()
                .setMcpReloadConfirm(
                        resolveBoolean(
                                readBoolean(props, overrides, "approvals.mcpReloadConfirm", true)));
        config.getMcp()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(props, overrides, "solonclaw.mcp.enabled", false)));
        config.getWeb()
                .setSearchBackend(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.web.searchBackend",
                                        "solon-ai")));
        config.getWeb()
                .setBraveSearchApiKey(
                        resolveConfigString(
                                readString(
                                        props, overrides, "solonclaw.web.braveSearchApiKey", "")));
        config.getTerminal()
                .setCredentialFiles(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.terminal.credentialFiles",
                                        "")));
        config.getTerminal()
                .setEnvPassthrough(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.terminal.envPassthrough",
                                        "")));
        config.getTerminal()
                .setShellInitFiles(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.terminal.shellInitFiles",
                                        "")));
        config.getTerminal()
                .setAutoSourceBashrc(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.terminal.autoSourceBashrc",
                                        true)));
        config.getTerminal()
                .setSudoPassword(
                        blankToNullSecret(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.terminal.sudoPassword",
                                        null)));
        config.getTerminal()
                .setWriteSafeRoot(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.terminal.writeSafeRoot",
                                        null)));
        config.getTerminal()
                .setMaxForegroundTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.terminal.maxForegroundTimeoutSeconds",
                                                600)),
                                600));
        config.getTerminal()
                .setForegroundMaxRetries(
                        Math.max(
                                0,
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.terminal.foregroundMaxRetries",
                                                3))));
        config.getTerminal()
                .setForegroundRetryBaseDelaySeconds(
                        Math.max(
                                0,
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.terminal.foregroundRetryBaseDelaySeconds",
                                                2))));
        config.getTerminal()
                .setProcessWaitTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.terminal.processWaitTimeoutSeconds",
                                                180)),
                                180));

        config.normalizePaths();
        syncRuntimeConfigExample(config.getRuntime().getHome());
        return config;
    }

    /** 标准化运行时路径与工作区路径，运行时基于进程目录，工作区相对运行 Jar 所在目录。 */
    public void normalizePaths() {
        File userDir = new File(System.getProperty("user.dir"));
        File runtimeHome =
                asAbsolute(
                        new File(
                                StrUtil.blankToDefault(
                                        runtime.getHome(), RuntimePathConstants.RUNTIME_HOME)),
                        userDir);
        runtime.setHome(runtimeHome.getAbsolutePath());
        runtime.setContextDir(
                new File(runtimeHome, RuntimePathConstants.CONTEXT_DIR_NAME).getAbsolutePath());
        runtime.setSkillsDir(
                new File(runtimeHome, RuntimePathConstants.SKILLS_DIR_NAME).getAbsolutePath());
        runtime.setCacheDir(
                new File(runtimeHome, RuntimePathConstants.CACHE_DIR_NAME).getAbsolutePath());
        runtime.setStateDb(
                new File(
                                new File(runtimeHome, RuntimePathConstants.DATA_DIR_NAME),
                                RuntimePathConstants.STATE_DB_FILE_NAME)
                        .getAbsolutePath());
        runtime.setConfigFile(
                new File(runtimeHome, RuntimePathConstants.CONFIG_FILE_NAME).getAbsolutePath());
        runtime.setLogsDir(
                new File(runtimeHome, RuntimePathConstants.LOGS_DIR_NAME).getAbsolutePath());
        File workspaceBase = jarBaseDir(userDir);
        File workspaceDir =
                asAbsolute(
                        new File(
                                StrUtil.blankToDefault(
                                        workspace.getDir(), RuntimePathConstants.DEFAULT_WORKSPACE)),
                        workspaceBase);
        workspace.setDir(workspaceDir.getAbsolutePath());
    }

    /** 用新的配置快照覆盖当前实例，保留对象引用稳定。 */
    public synchronized void applyFrom(AppConfig other) {
        if (other == null) {
            return;
        }
        copyRuntime(other.getRuntime());
        copyWorkspace(other.getWorkspace());
        copyLlm(other.getLlm());
        copyProviders(other.getProviders());
        copyModel(other.getModel());
        copyFallbackProviders(other.getFallbackProviders());
        copyScheduler(other.getScheduler());
        copyCompression(other.getCompression());
        copyLearning(other.getLearning());
        copyCurator(other.getCurator());
        copySkills(other.getSkills());
        copyRollback(other.getRollback());
        copyDisplay(other.getDisplay());
        copyReact(other.getReact());
        copyTrace(other.getTrace());
        copyTask(other.getTask());
        copyTerminal(other.getTerminal());
        copySecurity(other.getSecurity());
        copyWeb(other.getWeb());
        copyPricing(other.getPricing());
        copyApprovals(other.getApprovals());
        copyMcp(other.getMcp());
        copyProactive(other.getProactive());
        copyChannel(this.channels.getFeishu(), other.getChannels().getFeishu());
        copyChannel(this.channels.getDingtalk(), other.getChannels().getDingtalk());
        copyChannel(this.channels.getWecom(), other.getChannels().getWecom());
        copyChannel(this.channels.getWeixin(), other.getChannels().getWeixin());
        copyChannel(this.channels.getQqbot(), other.getChannels().getQqbot());
        copyChannel(this.channels.getYuanbao(), other.getChannels().getYuanbao());
        this.gateway.setAllowedUsers(new ArrayList<String>(other.getGateway().getAllowedUsers()));
        this.gateway.setAllowAllUsers(other.getGateway().isAllowAllUsers());
        this.gateway.setInjectionSecret(other.getGateway().getInjectionSecret());
        this.gateway.setInjectionMaxBodyBytes(other.getGateway().getInjectionMaxBodyBytes());
        this.gateway.setInjectionReplayWindowSeconds(
                other.getGateway().getInjectionReplayWindowSeconds());
        this.gateway.setFilterSilenceNarration(other.getGateway().isFilterSilenceNarration());
        this.gateway.setPlatforms(cloneGatewayPlatforms(other.getGateway().getPlatforms()));
        this.dashboard.setAccessToken(other.getDashboard().getAccessToken());
        this.agent.setPersonalities(clonePersonalities(other.getAgent().getPersonalities()));
        this.agent
                .getHeartbeat()
                .setIntervalMinutes(other.getAgent().getHeartbeat().getIntervalMinutes());
    }

    /**
     * 复制运行时。
     *
     * @param other 待比较对象。
     */
    private void copyRuntime(RuntimeConfig other) {
        this.runtime.setHome(other.getHome());
        this.runtime.setContextDir(other.getContextDir());
        this.runtime.setSkillsDir(other.getSkillsDir());
        this.runtime.setCacheDir(other.getCacheDir());
        this.runtime.setStateDb(other.getStateDb());
        this.runtime.setConfigFile(other.getConfigFile());
        this.runtime.setLogsDir(other.getLogsDir());
    }

    /**
     * 复制工作区配置。
     *
     * @param other 待复制的工作区配置。
     */
    private void copyWorkspace(WorkspaceConfig other) {
        if (other == null) {
            this.workspace.setDir(null);
            return;
        }
        this.workspace.setDir(other.getDir());
    }

    /**
     * 复制大模型。
     *
     * @param other 待比较对象。
     */
    private void copyLlm(LlmConfig other) {
        this.llm.setProvider(other.getProvider());
        this.llm.setDialect(other.getDialect());
        this.llm.setApiUrl(other.getApiUrl());
        this.llm.setApiKey(other.getApiKey());
        this.llm.setModel(other.getModel());
        this.llm.setStream(other.isStream());
        this.llm.setReasoningEffort(other.getReasoningEffort());
        this.llm.setTemperature(other.getTemperature());
        this.llm.setMaxTokens(other.getMaxTokens());
        this.llm.setContextWindowTokens(other.getContextWindowTokens());
        this.llm.getPromptCache().setEnabled(other.getPromptCache().isEnabled());
        this.llm.getPromptCache().setTtl(other.getPromptCache().getTtl());
        this.llm.getPromptCache().setLayout(other.getPromptCache().getLayout());
    }

    /**
     * 复制Providers。
     *
     * @param other 待比较对象。
     */
    private void copyProviders(Map<String, ProviderConfig> other) {
        this.providers = new LinkedHashMap<String, ProviderConfig>();
        if (other == null) {
            return;
        }
        for (Map.Entry<String, ProviderConfig> entry : other.entrySet()) {
            ProviderConfig source = entry.getValue();
            if (source == null) {
                continue;
            }
            ProviderConfig copy = new ProviderConfig();
            copy.setName(source.getName());
            copy.setBaseUrl(source.getBaseUrl());
            copy.setApiKey(source.getApiKey());
            copy.setDefaultModel(source.getDefaultModel());
            copy.setDialect(source.getDialect());
            copy.setSupportsVision(source.getSupportsVision());
            copyProviderDisplayFields(source, copy);
            this.providers.put(entry.getKey(), copy);
        }
    }

    /**
     * 复制提供方展示Fields。
     *
     * @param source 来源参数。
     * @param target target 参数。
     */
    private void copyProviderDisplayFields(ProviderConfig source, ProviderConfig target) {
        target.setGroupId(source.getGroupId());
        target.setGroupLabel(source.getGroupLabel());
        target.setGroupDescription(source.getGroupDescription());
        target.setDisplayDescription(source.getDisplayDescription());
    }

    /**
     * 复制模型。
     *
     * @param other 待比较对象。
     */
    private void copyModel(ModelConfig other) {
        this.model = new ModelConfig();
        if (other == null) {
            return;
        }
        this.model.setProviderKey(other.getProviderKey());
        this.model.setDefault(other.getDefault());
    }

    /**
     * 复制兜底Providers。
     *
     * @param other 待比较对象。
     */
    private void copyFallbackProviders(List<FallbackProviderConfig> other) {
        this.fallbackProviders = new ArrayList<FallbackProviderConfig>();
        if (other == null) {
            return;
        }
        for (FallbackProviderConfig source : other) {
            if (source == null) {
                continue;
            }
            FallbackProviderConfig copy = new FallbackProviderConfig();
            copy.setProvider(source.getProvider());
            copy.setModel(source.getModel());
            this.fallbackProviders.add(copy);
        }
    }

    /**
     * 复制调度器。
     *
     * @param other 待比较对象。
     */
    private void copyScheduler(SchedulerConfig other) {
        this.scheduler.setEnabled(other.isEnabled());
        this.scheduler.setTickSeconds(other.getTickSeconds());
        this.scheduler.setWrapResponse(other.isWrapResponse());
        this.scheduler.setScriptTimeoutSeconds(other.getScriptTimeoutSeconds());
        this.scheduler.setInactivityTimeoutSeconds(other.getInactivityTimeoutSeconds());
        this.scheduler.setEnabledToolsets(new ArrayList<String>(other.getEnabledToolsets()));
    }

    /**
     * 复制压缩。
     *
     * @param other 待比较对象。
     */
    private void copyCompression(CompressionConfig other) {
        this.compression.setEnabled(other.isEnabled());
        this.compression.setThresholdPercent(other.getThresholdPercent());
        this.compression.setSummaryModel(other.getSummaryModel());
        this.compression.setProtectHeadMessages(other.getProtectHeadMessages());
        this.compression.setTailRatio(other.getTailRatio());
    }

    /**
     * 复制Learning。
     *
     * @param other 待比较对象。
     */
    private void copyLearning(LearningConfig other) {
        this.learning.setEnabled(other.isEnabled());
        this.learning.setToolCallThreshold(other.getToolCallThreshold());
        this.learning.setAuxiliaryTimeoutSeconds(other.getAuxiliaryTimeoutSeconds());
    }

    /**
     * 复制技能维护。
     *
     * @param other 待比较对象。
     */
    private void copyCurator(CuratorConfig other) {
        this.curator.setEnabled(other.isEnabled());
        this.curator.setIntervalHours(other.getIntervalHours());
        this.curator.setMinIdleHours(other.getMinIdleHours());
        this.curator.setStaleAfterDays(other.getStaleAfterDays());
        this.curator.setArchiveAfterDays(other.getArchiveAfterDays());
    }

    /**
     * 复制技能。
     *
     * @param other 待比较对象。
     */
    private void copySkills(SkillsConfig other) {
        this.skills.setExternalDirs(new ArrayList<String>(other.getExternalDirs()));
        this.skills.setTemplateVars(other.isTemplateVars());
        this.skills.setInlineShell(other.isInlineShell());
        this.skills.setInlineShellTimeoutSeconds(other.getInlineShellTimeoutSeconds());
    }

    /**
     * 复制回滚。
     *
     * @param other 待比较对象。
     */
    private void copyRollback(RollbackConfig other) {
        this.rollback.setEnabled(other.isEnabled());
        this.rollback.setMaxCheckpointsPerSource(other.getMaxCheckpointsPerSource());
        this.rollback.setMaxFileSizeMb(other.getMaxFileSizeMb());
        this.rollback.setExcludePatterns(new ArrayList<String>(other.getExcludePatterns()));
    }

    /**
     * 复制展示。
     *
     * @param other 待比较对象。
     */
    private void copyDisplay(DisplayConfig other) {
        this.display.setToolProgress(other.getToolProgress());
        this.display.setShowReasoning(other.isShowReasoning());
        this.display.setResumeDisplay(other.getResumeDisplay());
        this.display.setToolPreviewLength(other.getToolPreviewLength());
        this.display.setProgressThrottleMs(other.getProgressThrottleMs());
        this.display.getRuntimeFooter().setEnabled(other.getRuntimeFooter().isEnabled());
        this.display
                .getRuntimeFooter()
                .setFields(new ArrayList<String>(other.getRuntimeFooter().getFields()));
    }

    /**
     * 复制ReAct。
     *
     * @param other 待比较对象。
     */
    private void copyReact(ReActConfig other) {
        this.react.setMaxSteps(other.getMaxSteps());
        this.react.setRetryMax(other.getRetryMax());
        this.react.setRetryDelayMs(other.getRetryDelayMs());
        this.react.setDelegateMaxSteps(other.getDelegateMaxSteps());
        this.react.setDelegateRetryMax(other.getDelegateRetryMax());
        this.react.setDelegateRetryDelayMs(other.getDelegateRetryDelayMs());
        this.react.setSummarizationEnabled(other.isSummarizationEnabled());
        this.react.setSummarizationMaxMessages(other.getSummarizationMaxMessages());
        this.react.setSummarizationMaxTokens(other.getSummarizationMaxTokens());
        this.react.setToolLoopWarningsEnabled(other.isToolLoopWarningsEnabled());
        this.react.setToolLoopHardStopEnabled(other.isToolLoopHardStopEnabled());
        this.react.setToolLoopExactFailureWarnAfter(other.getToolLoopExactFailureWarnAfter());
        this.react.setToolLoopExactFailureBlockAfter(other.getToolLoopExactFailureBlockAfter());
        this.react.setToolLoopSameToolFailureWarnAfter(other.getToolLoopSameToolFailureWarnAfter());
        this.react.setToolLoopSameToolFailureHaltAfter(other.getToolLoopSameToolFailureHaltAfter());
        this.react.setToolLoopNoProgressWarnAfter(other.getToolLoopNoProgressWarnAfter());
        this.react.setToolLoopNoProgressBlockAfter(other.getToolLoopNoProgressBlockAfter());
    }

    /**
     * 复制Trace。
     *
     * @param other 待比较对象。
     */
    private void copyTrace(TraceConfig other) {
        this.trace.setRetentionDays(other.getRetentionDays());
        this.trace.setMaxAttempts(other.getMaxAttempts());
        this.trace.setToolPreviewLength(other.getToolPreviewLength());
    }

    /**
     * 复制任务。
     *
     * @param other 待比较对象。
     */
    private void copyTask(TaskConfig other) {
        this.task.setBusyPolicy(other.getBusyPolicy());
        this.task.setRestartDrainTimeoutSeconds(other.getRestartDrainTimeoutSeconds());
        this.task.setStaleAfterMinutes(other.getStaleAfterMinutes());
        this.task.setSubagentMaxConcurrency(other.getSubagentMaxConcurrency());
        this.task.setSubagentMaxDepth(other.getSubagentMaxDepth());
        this.task.setToolOutputInlineLimit(other.getToolOutputInlineLimit());
        this.task.setToolOutputTurnBudget(other.getToolOutputTurnBudget());
        this.task.setToolOutputMaxLines(other.getToolOutputMaxLines());
        this.task.setToolOutputMaxLineLength(other.getToolOutputMaxLineLength());
        this.task.setMediaCacheTtlHours(other.getMediaCacheTtlHours());
    }

    /**
     * 复制终端。
     *
     * @param other 待比较对象。
     */
    private void copyTerminal(TerminalConfig other) {
        this.terminal.setCredentialFiles(new ArrayList<String>(other.getCredentialFiles()));
        this.terminal.setEnvPassthrough(new ArrayList<String>(other.getEnvPassthrough()));
        this.terminal.setShellInitFiles(new ArrayList<String>(other.getShellInitFiles()));
        this.terminal.setAutoSourceBashrc(other.isAutoSourceBashrc());
        this.terminal.setSudoPassword(other.getSudoPassword());
        this.terminal.setWriteSafeRoot(other.getWriteSafeRoot());
        this.terminal.setMaxForegroundTimeoutSeconds(other.getMaxForegroundTimeoutSeconds());
        this.terminal.setForegroundMaxRetries(other.getForegroundMaxRetries());
        this.terminal.setForegroundRetryBaseDelaySeconds(
                other.getForegroundRetryBaseDelaySeconds());
        this.terminal.setProcessWaitTimeoutSeconds(other.getProcessWaitTimeoutSeconds());
    }

    /**
     * 复制安全。
     *
     * @param other 待比较对象。
     */
    private void copySecurity(SecurityConfig other) {
        this.security.setAllowPrivateUrls(other.isAllowPrivateUrls());
        this.security.setRewriteBrowserLoopbackUrls(other.isRewriteBrowserLoopbackUrls());
        this.security.setBrowserLoopbackHostAlias(other.getBrowserLoopbackHostAlias());
        this.security.setTirithEnabled(other.isTirithEnabled());
        this.security.setTirithPath(other.getTirithPath());
        this.security.setTirithTimeoutSeconds(other.getTirithTimeoutSeconds());
        this.security.setTirithFailOpen(other.isTirithFailOpen());
        this.security.setFileGuardrailMode(other.getFileGuardrailMode());
        this.security.setUrlGuardrailMode(other.getUrlGuardrailMode());
        this.security.setGuardrailMode(other.getGuardrailMode());
        this.security.setGuardrailCronMode(other.getGuardrailCronMode());
        this.security.setGuardrailCronScope(other.getGuardrailCronScope());
        this.security.setHardlineAllowlist(new ArrayList<String>(other.getHardlineAllowlist()));
        this.security.getWebsiteBlocklist().setEnabled(other.getWebsiteBlocklist().isEnabled());
        this.security
                .getWebsiteBlocklist()
                .setDomains(new ArrayList<String>(other.getWebsiteBlocklist().getDomains()));
        this.security
                .getWebsiteBlocklist()
                .setSharedFiles(
                        new ArrayList<String>(other.getWebsiteBlocklist().getSharedFiles()));
    }

    /**
     * 复制Web。
     *
     * @param other 待比较对象。
     */
    private void copyWeb(WebConfig other) {
        this.web.setSearchBackend(other.getSearchBackend());
        this.web.setBraveSearchApiKey(other.getBraveSearchApiKey());
    }

    /**
     * 复制价格。
     *
     * @param other 待比较对象。
     */
    private void copyPricing(PricingConfig other) {
        this.pricing.setPrices(new ArrayList<ModelPrice>(other.getPrices()));
    }

    /**
     * 复制Approvals。
     *
     * @param other 待比较对象。
     */
    private void copyApprovals(ApprovalsConfig other) {
        this.approvals.setSubagentAutoApprove(other.isSubagentAutoApprove());
        this.approvals.setTimeoutSeconds(other.getTimeoutSeconds());
        this.approvals.setGatewayTimeoutSeconds(other.getGatewayTimeoutSeconds());
        this.approvals.setMcpReloadConfirm(other.isMcpReloadConfirm());
    }

    /**
     * 复制MCP。
     *
     * @param other 待比较对象。
     */
    private void copyMcp(McpConfig other) {
        this.mcp.setEnabled(other.isEnabled());
        if (other.getOauth() != null) {
            this.mcp.getOauth().setClientId(other.getOauth().getClientId());
            this.mcp.getOauth().setClientSecret(other.getOauth().getClientSecret());
            this.mcp.getOauth().setTokenUrl(other.getOauth().getTokenUrl());
            this.mcp.getOauth().setScope(other.getOauth().getScope());
        }
    }

    /**
     * 复制主动协作配置。
     *
     * @param other 待比较对象。
     */
    private void copyProactive(ProactiveConfig other) {
        this.proactive.setEnabled(other.isEnabled());
        this.proactive.setIntervalMinutes(other.getIntervalMinutes());
        this.proactive.setInitialDelaySeconds(other.getInitialDelaySeconds());
        this.proactive.setDailyMaxContacts(other.getDailyMaxContacts());
        this.proactive.setCooldownMinutes(other.getCooldownMinutes());
        this.proactive.setQuietStartHour(other.getQuietStartHour());
        this.proactive.setQuietEndHour(other.getQuietEndHour());
        this.proactive.setMinConfidenceToContact(other.getMinConfidenceToContact());
        this.proactive.setLlmDecisionEnabled(other.isLlmDecisionEnabled());
        this.proactive.setLlmPolishEnabled(other.isLlmPolishEnabled());
        this.proactive.setMaxCandidatesPerTick(other.getMaxCandidatesPerTick());
        this.proactive.setMaxContactsPerTick(other.getMaxContactsPerTick());
        this.proactive.setCandidateTtlHours(other.getCandidateTtlHours());
        this.proactive.setRepositoryCheckEnabled(other.isRepositoryCheckEnabled());
        this.proactive.setRepositoryCheckIntervalMinutes(
                other.getRepositoryCheckIntervalMinutes());
        this.proactive.setSessionLookbackDays(other.getSessionLookbackDays());
        this.proactive.setRunLookbackDays(other.getRunLookbackDays());
        this.proactive.setCronLookbackDays(other.getCronLookbackDays());
        this.proactive.setCareCheckinEnabled(other.isCareCheckinEnabled());
        this.proactive.setCareCheckinAfterIdleHours(other.getCareCheckinAfterIdleHours());
        this.proactive.setDeliveryPreviewPrefix(other.getDeliveryPreviewPrefix());
    }

    /**
     * 复制渠道。
     *
     * @param target target 参数。
     * @param source 来源参数。
     */
    private void copyChannel(ChannelConfig target, ChannelConfig source) {
        target.setEnabled(source.isEnabled());
        target.setAppId(source.getAppId());
        target.setAppSecret(source.getAppSecret());
        target.setClientId(source.getClientId());
        target.setClientSecret(source.getClientSecret());
        target.setBotId(source.getBotId());
        target.setSecret(source.getSecret());
        target.setToken(source.getToken());
        target.setAccountId(source.getAccountId());
        target.setRobotCode(source.getRobotCode());
        target.setCoolAppCode(source.getCoolAppCode());
        target.setWebsocketUrl(source.getWebsocketUrl());
        target.setStreamUrl(source.getStreamUrl());
        target.setLongPollUrl(source.getLongPollUrl());
        target.setBaseUrl(source.getBaseUrl());
        target.setCdnBaseUrl(source.getCdnBaseUrl());
        target.setAllowedUsers(new ArrayList<String>(source.getAllowedUsers()));
        target.setDmPolicy(source.getDmPolicy());
        target.setGroupPolicy(source.getGroupPolicy());
        target.setGroupAllowedUsers(new ArrayList<String>(source.getGroupAllowedUsers()));
        target.setAllowedChats(new ArrayList<String>(source.getAllowedChats()));
        target.setGroupMemberAllowedUsers(cloneGroupAllowMap(source.getGroupMemberAllowedUsers()));
        target.setBotOpenId(source.getBotOpenId());
        target.setBotUserId(source.getBotUserId());
        target.setBotName(source.getBotName());
        target.setAllowAllUsers(source.isAllowAllUsers());
        target.setUnauthorizedDmBehavior(source.getUnauthorizedDmBehavior());
        target.setSplitMultilineMessages(source.isSplitMultilineMessages());
        target.setTextBatchDelaySeconds(source.getTextBatchDelaySeconds());
        target.setTextBatchSplitDelaySeconds(source.getTextBatchSplitDelaySeconds());
        target.setSendChunkDelaySeconds(source.getSendChunkDelaySeconds());
        target.setSendChunkRetries(source.getSendChunkRetries());
        target.setSendChunkRetryDelaySeconds(source.getSendChunkRetryDelaySeconds());
        target.setToolProgress(source.getToolProgress());
        target.setProgressCardTemplateId(source.getProgressCardTemplateId());
        target.setRuntimeFooterEnabled(source.getRuntimeFooterEnabled());
        target.setCommentEnabled(source.isCommentEnabled());
        target.setCommentPairingFile(source.getCommentPairingFile());
        target.setAiCardStreamingEnabled(source.isAiCardStreamingEnabled());
        target.setApiDomain(source.getApiDomain());
        target.setMarkdownSupport(source.isMarkdownSupport());
    }

    /**
     * 克隆Personalities。
     *
     * @param source 来源参数。
     * @return 返回clone Personalities结果。
     */
    private Map<String, PersonalityConfig> clonePersonalities(
            Map<String, PersonalityConfig> source) {
        Map<String, PersonalityConfig> result = new LinkedHashMap<String, PersonalityConfig>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, PersonalityConfig> entry : source.entrySet()) {
            PersonalityConfig config = new PersonalityConfig();
            if (entry.getValue() != null) {
                config.setDescription(entry.getValue().getDescription());
                config.setSystemPrompt(entry.getValue().getSystemPrompt());
                config.setTone(entry.getValue().getTone());
                config.setStyle(entry.getValue().getStyle());
            }
            result.put(entry.getKey(), config);
        }
        return result;
    }

    /**
     * 克隆群组Allow映射。
     *
     * @param source 来源参数。
     * @return 返回clone群组Allow Map结果。
     */
    private Map<String, List<String>> cloneGroupAllowMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            result.put(
                    entry.getKey(),
                    entry.getValue() == null
                            ? new ArrayList<String>()
                            : new ArrayList<String>(entry.getValue()));
        }
        return result;
    }

    /**
     * 批量装配渠道共性配置。
     *
     * @param channelConfig 目标渠道配置
     * @param props 原始配置
     * @param channelName 渠道名称
     * @param defaultDmPolicy 默认私聊策略
     * @param defaultGroupPolicy 默认群聊策略
     */
    private static void applyChannelConfig(
            ChannelConfig channelConfig,
            Props props,
            Map<String, Object> overrides,
            String channelName,
            String defaultDmPolicy,
            String defaultGroupPolicy) {
        channelConfig.setAllowedUsers(
                resolveList(
                        readRaw(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".allowedUsers",
                                "")));
        channelConfig.setAllowAllUsers(
                resolveBoolean(
                        readBoolean(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".allowAllUsers",
                                false)));
        channelConfig.setUnauthorizedDmBehavior(
                resolveBehavior(
                        readString(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".unauthorizedDmBehavior",
                                GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_PAIR)));
        channelConfig.setDmPolicy(
                resolvePolicy(
                        readString(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".dmPolicy",
                                defaultDmPolicy),
                        defaultDmPolicy));
        channelConfig.setGroupPolicy(
                resolvePolicy(
                        readString(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".groupPolicy",
                                defaultGroupPolicy),
                        defaultGroupPolicy));
        channelConfig.setGroupAllowedUsers(
                resolveList(
                        readRaw(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".groupAllowedUsers",
                                "")));
        channelConfig.setAllowedChats(
                resolveList(
                        readRaw(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".allowedChats",
                                "")));
    }

    /** 优先从配置文件解析密钥。 */
    private static String resolveSecret(String fallback) {
        return StrUtil.nullToEmpty(fallback).trim();
    }

    /** 将空白密钥归一为空值，避免安全判断把空字符串当作已配置凭据。 */
    private static String blankToNullSecret(String fallback) {
        String value = resolveSecret(fallback);
        return StrUtil.isBlank(value) ? null : value;
    }

    /** 优先从配置文件解析普通字符串配置。 */
    private static String resolveConfigString(String fallback) {
        return StrUtil.nullToEmpty(fallback).trim();
    }

    /** 支持通过配置文件覆盖布尔配置。 */
    private static boolean resolveBoolean(boolean fallback) {
        return fallback;
    }

    /** 支持三态布尔配置，未配置时保留 null 表示走全局默认。 */
    private static Boolean resolveOptionalBoolean(Object fallback) {
        if (fallback == null) {
            return null;
        }
        String raw = String.valueOf(fallback).trim();
        if (raw.length() == 0) {
            return null;
        }
        return Boolean.valueOf(parseBooleanText(raw, false));
    }

    /**
     * 解析Boolean Text。
     *
     * @param raw 原始输入值。
     * @param fallback 兜底参数。
     * @return 返回解析后的Boolean Text。
     */
    private static boolean parseBooleanText(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim();
        if ("true".equalsIgnoreCase(normalized)
                || "1".equals(normalized)
                || "yes".equalsIgnoreCase(normalized)
                || "on".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)
                || "0".equals(normalized)
                || "no".equalsIgnoreCase(normalized)
                || "off".equalsIgnoreCase(normalized)) {
            return false;
        }
        return fallback;
    }

    /** 支持通过配置文件覆盖整型配置。 */
    private static int resolveInt(int fallback) {
        return fallback;
    }

    /**
     * 执行正数Int相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回positive Int结果。
     */
    private static int positiveInt(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    /**
     * 执行nonNegativeInt相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回non Negative Int结果。
     */
    private static int nonNegativeInt(int value, int defaultValue) {
        return value >= 0 ? value : defaultValue;
    }

    /** 支持通过配置文件覆盖浮点配置。 */
    private static double resolveDouble(double fallback) {
        return fallback;
    }

    /**
     * 执行nonNegativeFiniteDouble相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param defaultValue 默认值参数。
     * @return 返回non Negative Finite Double结果。
     */
    private static double nonNegativeFiniteDouble(double value, double defaultValue) {
        return Double.isFinite(value) && value >= 0.0D ? value : defaultValue;
    }

    /** 支持逗号分隔的用户列表解析。 */
    private static List<String> resolveList(Object fallback) {
        return splitObjectList(fallback);
    }

    /**
     * 执行默认HardlineAllowlist相关逻辑。
     *
     * @return 返回默认Hardline Allowlist结果。
     */
    private static List<String> defaultHardlineAllowlist() {
        return Collections.emptyList();
    }

    /** 统一收敛未授权私聊用户的处理行为。 */
    private static String resolveBehavior(String fallback) {
        String value =
                StrUtil.nullToDefault(
                                fallback, GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_PAIR)
                        .trim();
        if (GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_IGNORE.equalsIgnoreCase(value)) {
            return GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_IGNORE;
        }
        return GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_PAIR;
    }

    /**
     * 规范化防护模式，只接受当前配置枚举，避免历史值或拼写错误静默生效。
     *
     * @param raw 原始输入值。
     * @return 返回防护模式结果。
     */
    private static String normalizeGuardrailMode(String raw) {
        String value = StrUtil.blankToDefault(raw, "approval").trim().toLowerCase();
        if ("approval".equals(value)
                || "strict".equals(value)
                || "bypass".equals(value)
                || "smart".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.guardrailMode 只支持 approval、strict、bypass、smart，当前值：" + raw);
    }

    /**
     * 规范化定时任务防护模式，只接受当前配置枚举。
     *
     * @param raw 原始输入值。
     * @return 返回防护定时任务模式结果。
     */
    private static String normalizeGuardrailCronMode(String raw) {
        String value = StrUtil.blankToDefault(raw, "strict").trim().toLowerCase();
        if ("approval".equals(value)
                || "strict".equals(value)
                || "bypass".equals(value)
                || "approve".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.guardrailCronMode 只支持 approval、strict、bypass、approve，当前值：" + raw);
    }

    /**
     * 规范化定时任务审批记忆范围，只接受当前配置枚举。
     *
     * @param raw 原始输入值。
     * @return 返回防护定时任务范围结果。
     */
    private static String normalizeGuardrailCronScope(String raw) {
        String value = StrUtil.blankToDefault(raw, "job").trim().toLowerCase();
        if ("job".equals(value) || "session".equals(value) || "global".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.guardrailCronScope 只支持 job、session、global，当前值：" + raw);
    }

    /** 规范化只有严格和跳过两种结果的安全预检模式，只接受当前配置枚举。 */
    private static String normalizeBinaryGuardrailMode(String raw) {
        String value = StrUtil.blankToDefault(raw, "strict").trim().toLowerCase();
        if ("bypass".equals(value) || "strict".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.fileGuardrailMode/security.urlGuardrailMode 只支持 strict、bypass，当前值：" + raw);
    }

    /** 统一解析访问策略值。 */
    private static String resolvePolicy(String fallback, String defaultValue) {
        String value = StrUtil.nullToDefault(fallback, defaultValue).trim();
        return value.length() == 0 ? defaultValue : value.toLowerCase();
    }

    /** 将逗号分隔列表转为字符串集合。 */
    private static List<String> splitList(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        for (String item : Arrays.asList(raw.split(","))) {
            String trimmed = item == null ? "" : item.trim();
            if (trimmed.length() > 0) {
                values.add(trimmed);
            }
        }
        return values;
    }

    /** 支持从 YAML 列表或字符串中解析动态 allowlist。 */
    private static List<String> splitObjectList(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof List) {
            List<String> values = new ArrayList<String>();
            for (Object item : (List<?>) raw) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        return splitList(String.valueOf(raw));
    }

    /** 收集形如 channels.wecom.groups.<groupId>.allowFrom 的动态配置。 */
    private static Map<String, List<String>> collectGroupAllowMap(
            Props props, Map<String, Object> overrides, String prefix, String configKey) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        if (props != null) {
            for (Map.Entry<Object, Object> entry : props.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.startsWith(prefix) || !key.endsWith(".allowFrom")) {
                    continue;
                }
                String groupId =
                        key.substring(prefix.length(), key.length() - ".allowFrom".length()).trim();
                if (groupId.length() == 0) {
                    continue;
                }
                result.put(groupId, splitObjectList(entry.getValue()));
            }
        }
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix) || !key.endsWith(".allowFrom")) {
                continue;
            }
            String groupId =
                    key.substring(prefix.length(), key.length() - ".allowFrom".length()).trim();
            if (groupId.length() == 0) {
                continue;
            }
            result.put(groupId, splitObjectList(entry.getValue()));
        }
        Object configValue = readRaw(props, overrides, configKey, null);
        if (configValue instanceof Map) {
            result.putAll(parseGroupAllowMapJson(ONode.serialize(configValue)));
        } else if (configValue != null && StrUtil.isNotBlank(String.valueOf(configValue))) {
            result.putAll(parseGroupAllowMapJson(String.valueOf(configValue)));
        }
        return result;
    }

    /** 支持通过 JSON 配置文件注入 group -> allowlist 映射。 */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> parseGroupAllowMapJson(String json) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        Object parsed = ONode.deserialize(json, Object.class);
        if (!(parsed instanceof Map)) {
            return result;
        }
        Map<String, Object> raw = (Map<String, Object>) parsed;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.length() == 0) {
                continue;
            }
            result.put(key, splitObjectList(entry.getValue()));
        }
        return result;
    }

    /**
     * 应用价格配置装配。
     *
     * @param config 当前模块使用的配置对象。
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param structuredOverrides structuredOverrides标识或键值。
     */
    @SuppressWarnings("unchecked")
    private static void applyPricingConfiguration(
            AppConfig config,
            Props props,
            Map<String, Object> overrides,
            Map<String, Object> structuredOverrides) {
        Object rawPrices = null;
        Object pricingNode = structuredOverrides.get("pricing");
        if (pricingNode instanceof Map) {
            rawPrices = ((Map<String, Object>) pricingNode).get("prices");
        }
        Object nestedNode = structuredOverrides.get("solonclaw");
        if (nestedNode instanceof Map) {
            Object nestedPricing = ((Map<String, Object>) nestedNode).get("pricing");
            if (nestedPricing instanceof Map && ((Map<?, ?>) nestedPricing).containsKey("prices")) {
                rawPrices = ((Map<String, Object>) nestedPricing).get("prices");
            }
        }
        Object flatPrices = readRaw(props, overrides, "solonclaw.pricing.prices", null);
        if (flatPrices != null) {
            rawPrices = flatPrices;
        }
        List<ModelPrice> prices = parseModelPrices(rawPrices);
        config.getPricing().setPrices(prices);
    }

    /**
     * 应用插件配置装配。
     *
     * @param config 当前模块使用的配置对象。
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param structuredOverrides structuredOverrides标识或键值。
     */
    @SuppressWarnings("unchecked")
    private static void applyPluginConfiguration(
            AppConfig config,
            Props props,
            Map<String, Object> overrides,
            Map<String, Object> structuredOverrides) {
        Object enabled = readRaw(props, overrides, "solonclaw.plugins.enabled", null);
        Object disabled = readRaw(props, overrides, "solonclaw.plugins.disabled", null);
        Object pluginsNode = structuredOverrides.get("plugins");
        if (pluginsNode instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) pluginsNode;
            if (map.containsKey("enabled")) {
                enabled = map.get("enabled");
            }
            if (map.containsKey("disabled")) {
                disabled = map.get("disabled");
            }
        }
        Object solonclawNode = structuredOverrides.get("solonclaw");
        if (solonclawNode instanceof Map) {
            Object nestedPlugins = ((Map<String, Object>) solonclawNode).get("plugins");
            if (nestedPlugins instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) nestedPlugins;
                if (map.containsKey("enabled")) {
                    enabled = map.get("enabled");
                }
                if (map.containsKey("disabled")) {
                    disabled = map.get("disabled");
                }
            }
        }
        config.getPlugins().setEnabled(resolveList(enabled));
        config.getPlugins().setDisabled(resolveList(disabled));
    }

    /**
     * 解析模型Prices。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的模型Prices。
     */
    @SuppressWarnings("unchecked")
    private static List<ModelPrice> parseModelPrices(Object raw) {
        List<ModelPrice> prices = new ArrayList<ModelPrice>();
        if (raw == null) {
            return prices;
        }
        Object parsed = raw;
        if (raw instanceof String) {
            String text = String.valueOf(raw).trim();
            if (text.length() == 0) {
                return prices;
            }
            parsed = ONode.deserialize(text, Object.class);
            if (parsed instanceof Map && ((Map<?, ?>) parsed).containsKey("prices")) {
                parsed = ((Map<?, ?>) parsed).get("prices");
            }
        }
        if (!(parsed instanceof List)) {
            return prices;
        }
        for (Object item : (List<Object>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) item;
            ModelPrice price = new ModelPrice();
            price.setProvider(stringValue(map, "provider"));
            price.setModel(stringValue(map, "model"));
            price.setCurrency(StrUtil.blankToDefault(stringValue(map, "currency"), "USD"));
            applyModelTokenPrice(
                    price,
                    "input",
                    firstStringValue(map, "input_cost_per_million", "prompt_cost_per_million"),
                    firstPresentLongValue(
                            map, "input_micros_per_token", "prompt_micros_per_token"));
            applyModelTokenPrice(
                    price,
                    "output",
                    firstStringValue(map, "output_cost_per_million", "completion_cost_per_million"),
                    firstPresentLongValue(
                            map, "output_micros_per_token", "completion_micros_per_token"));
            applyModelTokenPrice(
                    price,
                    "cache_read",
                    stringValue(map, "cache_read_cost_per_million"),
                    presentLongValue(map, "cache_read_micros_per_token"));
            applyModelTokenPrice(
                    price,
                    "cache_write",
                    stringValue(map, "cache_write_cost_per_million"),
                    presentLongValue(map, "cache_write_micros_per_token"));
            applyModelTokenPrice(
                    price,
                    "reasoning",
                    stringValue(map, "reasoning_cost_per_million"),
                    presentLongValue(map, "reasoning_micros_per_token"));
            Long requestMicros = presentLongValue(map, "request_micros_per_request");
            if (requestMicros != null) {
                price.setRequestMicrosPerRequest(requestMicros.longValue());
            }
            price.setSource(stringValue(map, "source"));
            price.setSourceUrl(firstStringValue(map, "source_url", "sourceUrl"));
            price.setPricingVersion(
                    firstStringValue(map, "pricing_version", "pricingVersion", "version"));
            price.setFetchedAt(firstLongValue(map, "fetched_at", "fetchedAt"));
            if (StrUtil.isNotBlank(price.getProvider()) && StrUtil.isNotBlank(price.getModel())) {
                prices.add(price);
            }
        }
        return prices;
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回string Value结果。
     */
    private static String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 执行first字符串值相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first String Value结果。
     */
    private static String firstStringValue(Map<String, Object> map, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = stringValue(map, key);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 应用模型token价格。
     *
     * @param price 价格参数。
     * @param field field 参数。
     * @param costPerMillion 成本PerMillion参数。
     * @param microsPerToken microsPertoken参数。
     */
    private static void applyModelTokenPrice(
            ModelPrice price, String field, String costPerMillion, Long microsPerToken) {
        if (StrUtil.isNotBlank(costPerMillion)) {
            if ("input".equals(field)) {
                price.setInputCostPerMillion(costPerMillion);
            } else if ("output".equals(field)) {
                price.setOutputCostPerMillion(costPerMillion);
            } else if ("cache_read".equals(field)) {
                price.setCacheReadCostPerMillion(costPerMillion);
            } else if ("cache_write".equals(field)) {
                price.setCacheWriteCostPerMillion(costPerMillion);
            } else if ("reasoning".equals(field)) {
                price.setReasoningCostPerMillion(costPerMillion);
            }
            return;
        }
        if (microsPerToken == null) {
            return;
        }
        if ("input".equals(field)) {
            price.setInputMicrosPerToken(microsPerToken.longValue());
        } else if ("output".equals(field)) {
            price.setOutputMicrosPerToken(microsPerToken.longValue());
        } else if ("cache_read".equals(field)) {
            price.setCacheReadMicrosPerToken(microsPerToken.longValue());
        } else if ("cache_write".equals(field)) {
            price.setCacheWriteMicrosPerToken(microsPerToken.longValue());
        } else if ("reasoning".equals(field)) {
            price.setReasoningMicrosPerToken(microsPerToken.longValue());
        }
    }

    /**
     * 将输入对象转换为长整型数值。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回long Value结果。
     */
    private static long longValue(Map<String, Object> map, String key) {
        Long value = presentLongValue(map, key);
        return value == null ? 0L : value.longValue();
    }

    /**
     * 执行present长整型值相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回present Long Value结果。
     */
    private static Long presentLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(Math.max(0L, Long.parseLong(String.valueOf(value).trim())));
        } catch (Exception e) {
            log.debug("模型价格长整型配置解析失败，使用 0 作为兜底值: {}", exceptionSummary(e));
            return Long.valueOf(0L);
        }
    }

    /**
     * 执行first长整型值相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first Long Value结果。
     */
    private static long firstLongValue(Map<String, Object> map, String... keys) {
        Long value = firstPresentLongValue(map, keys);
        return value == null ? 0L : value.longValue();
    }

    /**
     * 执行firstPresent长整型值相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first Present Long Value结果。
     */
    private static Long firstPresentLongValue(Map<String, Object> map, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            Long value = presentLongValue(map, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 解析 personalities 配置映射。 */
    private static Map<String, PersonalityConfig> loadPersonalities(
            Props props, Map<String, Object> overrides) {
        Map<String, PersonalityConfig> result = new LinkedHashMap<String, PersonalityConfig>();
        if (props == null) {
            return result;
        }

        String prefix = "solonclaw.agent.personalities.";
        Map<String, String> rawEntries = new LinkedHashMap<String, String>();
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String rawKey = String.valueOf(entry.getKey());
            if (!rawKey.startsWith(prefix)) {
                continue;
            }
            rawEntries.put(rawKey, props.get(rawKey, ""));
        }
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                rawEntries.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        for (Map.Entry<String, String> entry : rawEntries.entrySet()) {
            String rawKey = entry.getKey();

            String suffix = rawKey.substring(prefix.length());
            int index = suffix.indexOf('.');
            if (index <= 0 || index >= suffix.length() - 1) {
                continue;
            }

            String name = suffix.substring(0, index).trim();
            String field = suffix.substring(index + 1).trim();
            if (StrUtil.isBlank(name) || StrUtil.isBlank(field)) {
                continue;
            }

            PersonalityConfig personality = result.get(name);
            if (personality == null) {
                personality = new PersonalityConfig();
                result.put(name, personality);
            }

            String value = entry.getValue();
            if ("description".equals(field)) {
                personality.setDescription(value);
            } else if ("systemPrompt".equals(field)) {
                personality.setSystemPrompt(value);
            } else if ("tone".equals(field)) {
                personality.setTone(value);
            } else if ("style".equals(field)) {
                personality.setStyle(value);
            }
        }
        return result;
    }

    /** 解析 gateway.platforms 配置映射。 */
    @SuppressWarnings("unchecked")
    private static Map<String, PlatformConfig> loadGatewayPlatforms(
            Props props, Map<String, Object> overrides, Map<String, Object> structuredOverrides) {
        Map<String, PlatformConfig> result = new LinkedHashMap<String, PlatformConfig>();

        // 从扁平化 overrides 中解析 solonclaw.gateway.platforms.<platform>.<field>
        String prefix = "solonclaw.gateway.platforms.";
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            String suffix = key.substring(prefix.length());
            int dot = suffix.indexOf('.');
            if (dot <= 0 || dot >= suffix.length() - 1) {
                continue;
            }
            String platform = suffix.substring(0, dot).trim().toUpperCase();
            String field = suffix.substring(dot + 1).trim();
            if (platform.length() == 0 || field.length() == 0) {
                continue;
            }
            PlatformConfig pc = result.get(platform);
            if (pc == null) {
                pc = new PlatformConfig();
                result.put(platform, pc);
            }
            applyPlatformConfigField(pc, field, entry.getValue());
        }

        // 从结构化 overrides 中解析 solonclaw.gateway.platforms 块
        Object solonclawNode = structuredOverrides.get("solonclaw");
        if (solonclawNode instanceof Map) {
            Object gatewayNode = ((Map<String, Object>) solonclawNode).get("gateway");
            if (gatewayNode instanceof Map) {
                Object platformsNode = ((Map<String, Object>) gatewayNode).get("platforms");
                if (platformsNode instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) platformsNode).entrySet()) {
                        if (entry.getKey() == null || !(entry.getValue() instanceof Map)) {
                            continue;
                        }
                        String platform = String.valueOf(entry.getKey()).trim().toUpperCase();
                        if (platform.length() == 0) {
                            continue;
                        }
                        PlatformConfig pc = result.get(platform);
                        if (pc == null) {
                            pc = new PlatformConfig();
                            result.put(platform, pc);
                        }
                        Map<?, ?> fields = (Map<?, ?>) entry.getValue();
                        for (Map.Entry<?, ?> fieldEntry : fields.entrySet()) {
                            if (fieldEntry.getKey() == null) {
                                continue;
                            }
                            applyPlatformConfigField(
                                    pc,
                                    String.valueOf(fieldEntry.getKey()).trim(),
                                    fieldEntry.getValue());
                        }
                    }
                }
            }
        }

        return result;
    }

    /** 将单个字段值应用到 PlatformConfig。 */
    private static void applyPlatformConfigField(PlatformConfig pc, String field, Object value) {
        if ("enabledToolsets".equals(field)) {
            pc.setEnabledToolsets(splitObjectList(value));
        } else if ("disabledToolsets".equals(field)) {
            pc.setDisabledToolsets(splitObjectList(value));
        } else if ("approvalRequired".equals(field)) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                pc.setApprovalRequired(
                        "true".equalsIgnoreCase(text)
                                || "1".equals(text)
                                || "yes".equalsIgnoreCase(text));
            }
        }
    }

    /** 深拷贝 gateway platforms 配置。 */
    private static Map<String, PlatformConfig> cloneGatewayPlatforms(
            Map<String, PlatformConfig> source) {
        Map<String, PlatformConfig> result = new LinkedHashMap<String, PlatformConfig>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, PlatformConfig> entry : source.entrySet()) {
            PlatformConfig src = entry.getValue();
            if (src == null) {
                continue;
            }
            PlatformConfig copy = new PlatformConfig();
            copy.setEnabledToolsets(new ArrayList<String>(src.getEnabledToolsets()));
            copy.setDisabledToolsets(new ArrayList<String>(src.getDisabledToolsets()));
            copy.setApprovalRequired(src.isApprovalRequired());
            result.put(entry.getKey(), copy);
        }
        return result;
    }

    /** 将相对路径转换为绝对路径。 */
    private File asAbsolute(File file, File base) {
        if (file.isAbsolute()) {
            return file;
        }
        return new File(base, file.getPath());
    }

    /**
     * 读取String。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的String。
     */
    private static String readString(
            Props props, Map<String, Object> overrides, String key, String defaultValue) {
        Object override = overrides.get(key);
        if (override != null) {
            return String.valueOf(override).trim();
        }
        return props.get(key, defaultValue);
    }

    /**
     * 读取原始。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的原始。
     */
    private static Object readRaw(
            Props props, Map<String, Object> overrides, String key, Object defaultValue) {
        if (overrides.containsKey(key)) {
            return overrides.get(key);
        }
        Object value = props.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 读取Int。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的Int。
     */
    private static int readInt(
            Props props, Map<String, Object> overrides, String key, int defaultValue) {
        Object override = overrides.get(key);
        if (override != null) {
            try {
                return Integer.parseInt(String.valueOf(override).trim());
            } catch (Exception e) {
                log.debug("整型覆盖配置解析失败，使用默认值: key={}, error={}", key, exceptionSummary(e));
                return defaultValue;
            }
        }
        return props.getInt(key, defaultValue);
    }

    /**
     * 读取Double。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的Double。
     */
    private static double readDouble(
            Props props, Map<String, Object> overrides, String key, double defaultValue) {
        Object override = overrides.get(key);
        if (override != null) {
            try {
                return Double.parseDouble(String.valueOf(override).trim());
            } catch (Exception e) {
                log.debug("浮点覆盖配置解析失败，使用默认值: key={}, error={}", key, exceptionSummary(e));
                return defaultValue;
            }
        }
        return props.getDouble(key, defaultValue);
    }

    /**
     * 读取Boolean。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的Boolean。
     */
    private static boolean readBoolean(
            Props props, Map<String, Object> overrides, String key, boolean defaultValue) {
        Object override = overrides.get(key);
        if (override != null) {
            String text = String.valueOf(override).trim();
            return "true".equalsIgnoreCase(text)
                    || "1".equals(text)
                    || "yes".equalsIgnoreCase(text);
        }
        return props.getBool(key, defaultValue);
    }

    /**
     * 读取Allow私聊Urls。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @return 返回读取到的Allow私聊Urls。
     */
    private static boolean readAllowPrivateUrls(Props props, Map<String, Object> overrides) {
        String env = StrUtil.nullToEmpty(System.getenv("SOLONCLAW_ALLOW_PRIVATE_URLS")).trim();
        if (env.length() > 0) {
            return parseBooleanText(env, false);
        }
        return readBoolean(props, overrides, "security.allowPrivateUrls", false);
    }

    /**
     * 读取浏览器Loopback Rewrite 启用。
     *
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @return 返回读取到的浏览器Loopback Rewrite 启用。
     */
    private static boolean readBrowserLoopbackRewriteEnabled(
            Props props, Map<String, Object> overrides) {
        return readBoolean(props, overrides, "solonclaw.browser.rewriteLoopbackUrls", false);
    }

    /**
     * 应用提供方配置装配。
     *
     * @param config 当前模块使用的配置对象。
     * @param props props 参数。
     * @param overrides overrides标识或键值。
     * @param structuredOverrides structuredOverrides标识或键值。
     */
    private static void applyProviderConfiguration(
            AppConfig config,
            Props props,
            Map<String, Object> overrides,
            Map<String, Object> structuredOverrides) {
        Map<String, ProviderConfig> providers =
                parseProviders(structuredOverrides.get("providers"), props);
        ModelConfig modelConfig = parseModelConfig(structuredOverrides.get("model"), props);
        List<FallbackProviderConfig> fallbackChain =
                parseFallbackProviders(structuredOverrides.get("fallbackProviders"));

        validateProviderConfiguration(providers, modelConfig, fallbackChain);

        config.setProviders(providers);
        config.setModel(modelConfig);
        config.setFallbackProviders(fallbackChain);

        ProviderConfig activeProvider = providers.get(modelConfig.getProviderKey());
        String effectiveModel =
                StrUtil.isNotBlank(modelConfig.getDefault())
                        ? modelConfig.getDefault().trim()
                        : StrUtil.nullToEmpty(activeProvider.getDefaultModel()).trim();

        config.getLlm().setProvider(modelConfig.getProviderKey());
        config.getLlm()
                .setDialect(LlmProviderSupport.normalizeDialect(activeProvider.getDialect()));
        config.getLlm()
                .setApiUrl(
                        LlmProviderSupport.buildApiUrl(
                                activeProvider.getBaseUrl(), activeProvider.getDialect()));
        config.getLlm().setApiKey(StrUtil.nullToEmpty(activeProvider.getApiKey()).trim());
        config.getLlm().setModel(effectiveModel);
    }

    /**
     * 解析Providers。
     *
     * @param rawProviders 原始Providers标识或键值。
     * @param props props 参数。
     * @return 返回解析后的Providers。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, ProviderConfig> parseProviders(Object rawProviders, Props props) {
        Map<String, ProviderConfig> providers = new LinkedHashMap<String, ProviderConfig>();
        providers.put(RuntimePathConstants.DEFAULT_PROVIDER_KEY, loadDefaultProvider(props));
        if (!(rawProviders instanceof Map)) {
            return providers;
        }

        Map<Object, Object> rawMap = (Map<Object, Object>) rawProviders;
        for (Map.Entry<Object, Object> entry : rawMap.entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }

            Map<Object, Object> rawProvider = (Map<Object, Object>) entry.getValue();
            ProviderConfig provider =
                    RuntimePathConstants.DEFAULT_PROVIDER_KEY.equals(key)
                            ? cloneProvider(
                                    providers.get(RuntimePathConstants.DEFAULT_PROVIDER_KEY))
                            : new ProviderConfig();
            applyProviderString(rawProvider, "name", provider, "name");
            applyProviderString(rawProvider, "baseUrl", provider, "baseUrl");
            applyProviderString(rawProvider, "apiKey", provider, "apiKey");
            applyProviderString(rawProvider, "defaultModel", provider, "defaultModel");
            applyProviderString(rawProvider, "dialect", provider, "dialect");
            applyProviderString(rawProvider, "groupId", provider, "groupId");
            applyProviderString(rawProvider, "groupLabel", provider, "groupLabel");
            applyProviderString(rawProvider, "groupDescription", provider, "groupDescription");
            applyProviderString(rawProvider, "displayDescription", provider, "displayDescription");
            applyProviderBoolean(rawProvider, "supportsVision", provider, "supportsVision");
            providers.put(key, provider);
        }

        return providers;
    }

    /**
     * 克隆提供方。
     *
     * @param source 来源参数。
     * @return 返回clone提供方结果。
     */
    private static ProviderConfig cloneProvider(ProviderConfig source) {
        ProviderConfig copy = new ProviderConfig();
        if (source != null) {
            copy.setName(source.getName());
            copy.setBaseUrl(source.getBaseUrl());
            copy.setApiKey(source.getApiKey());
            copy.setDefaultModel(source.getDefaultModel());
            copy.setDialect(source.getDialect());
            copy.setSupportsVision(source.getSupportsVision());
            copy.setGroupId(source.getGroupId());
            copy.setGroupLabel(source.getGroupLabel());
            copy.setGroupDescription(source.getGroupDescription());
            copy.setDisplayDescription(source.getDisplayDescription());
        }
        return copy;
    }

    /**
     * 应用提供方布尔值。
     *
     * @param rawProvider 原始提供方标识或键值。
     * @param key 配置键或映射键。
     * @param provider 模型或能力提供方。
     * @param field field 参数。
     */
    private static void applyProviderBoolean(
            Map<Object, Object> rawProvider, String key, ProviderConfig provider, String field) {
        if (!rawProvider.containsKey(key)) {
            return;
        }
        Boolean value = resolveOptionalBoolean(rawProvider.get(key));
        if ("supportsVision".equals(field)) {
            provider.setSupportsVision(value);
        }
    }

    /**
     * 应用提供方字符串。
     *
     * @param rawProvider 原始提供方标识或键值。
     * @param key 配置键或映射键。
     * @param provider 模型或能力提供方。
     * @param field field 参数。
     */
    private static void applyProviderString(
            Map<Object, Object> rawProvider, String key, ProviderConfig provider, String field) {
        if (!rawProvider.containsKey(key)) {
            return;
        }
        String value = readNestedString(rawProvider, key);
        if ("name".equals(field)) {
            provider.setName(value);
        } else if ("baseUrl".equals(field)) {
            provider.setBaseUrl(value);
        } else if ("apiKey".equals(field)) {
            provider.setApiKey(value);
        } else if ("defaultModel".equals(field)) {
            provider.setDefaultModel(value);
        } else if ("dialect".equals(field)) {
            provider.setDialect(value);
        } else if ("groupId".equals(field)) {
            provider.setGroupId(value);
        } else if ("groupLabel".equals(field)) {
            provider.setGroupLabel(value);
        } else if ("groupDescription".equals(field)) {
            provider.setGroupDescription(value);
        } else if ("displayDescription".equals(field)) {
            provider.setDisplayDescription(value);
        }
    }

    /**
     * 解析模型配置。
     *
     * @param rawModel 原始模型参数。
     * @param props props 参数。
     * @return 返回解析后的模型配置。
     */
    @SuppressWarnings("unchecked")
    private static ModelConfig parseModelConfig(Object rawModel, Props props) {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setProviderKey(
                StrUtil.blankToDefault(
                        props.get("model.providerKey"), RuntimePathConstants.DEFAULT_PROVIDER_KEY));
        modelConfig.setDefault(StrUtil.nullToEmpty(props.get("model.default")).trim());
        if (!(rawModel instanceof Map)) {
            return modelConfig;
        }

        Map<Object, Object> rawMap = (Map<Object, Object>) rawModel;
        String providerKey = readNestedString(rawMap, "providerKey");
        String defaultModel = readNestedString(rawMap, "default");
        if (StrUtil.isNotBlank(providerKey)) {
            modelConfig.setProviderKey(providerKey);
        }
        if (StrUtil.isNotBlank(defaultModel)) {
            modelConfig.setDefault(defaultModel);
        }
        return modelConfig;
    }

    /**
     * 解析兜底Providers。
     *
     * @param rawFallbackProviders 原始兜底Providers标识或键值。
     * @return 返回解析后的兜底Providers。
     */
    @SuppressWarnings("unchecked")
    private static List<FallbackProviderConfig> parseFallbackProviders(
            Object rawFallbackProviders) {
        List<FallbackProviderConfig> result = new ArrayList<FallbackProviderConfig>();
        if (!(rawFallbackProviders instanceof List)) {
            return result;
        }

        List<Object> rawList = (List<Object>) rawFallbackProviders;
        for (Object raw : rawList) {
            if (!(raw instanceof Map)) {
                continue;
            }

            Map<Object, Object> rawMap = (Map<Object, Object>) raw;
            FallbackProviderConfig config = new FallbackProviderConfig();
            config.setProvider(readNestedString(rawMap, "provider"));
            config.setModel(readNestedString(rawMap, "model"));
            result.add(config);
        }

        return result;
    }

    /**
     * 加载默认提供方。
     *
     * @param props props 参数。
     * @return 返回默认提供方结果。
     */
    private static ProviderConfig loadDefaultProvider(Props props) {
        String dialect =
                StrUtil.blankToDefault(
                        props.get("providers.default.dialect"),
                        RuntimePathConstants.DEFAULT_LLM_PROVIDER);
        String baseUrl =
                StrUtil.blankToDefault(
                        props.get("providers.default.baseUrl"),
                        RuntimePathConstants.DEFAULT_LLM_API_URL);
        String defaultModel =
                StrUtil.blankToDefault(
                        props.get("providers.default.defaultModel"),
                        RuntimePathConstants.DEFAULT_LLM_MODEL);
        ProviderConfig provider = new ProviderConfig();
        provider.setName(
                StrUtil.blankToDefault(props.get("providers.default.name"), "DefaultProvider"));
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(StrUtil.nullToEmpty(props.get("providers.default.apiKey")).trim());
        provider.setDefaultModel(defaultModel);
        provider.setDialect(LlmProviderSupport.normalizeDialect(dialect));
        provider.setSupportsVision(
                resolveOptionalBoolean(props.get("providers.default.supportsVision")));
        return provider;
    }

    /**
     * 校验提供方配置装配。
     *
     * @param providers 能力提供方列表。
     * @param modelConfig 模型配置对象。
     * @param fallbackChain 兜底Chain参数。
     */
    private static void validateProviderConfiguration(
            Map<String, ProviderConfig> providers,
            ModelConfig modelConfig,
            List<FallbackProviderConfig> fallbackChain) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException("至少需要配置一个 provider。");
        }
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getProviderKey())) {
            throw new IllegalStateException("model.providerKey 不能为空。");
        }
        if (!providers.containsKey(modelConfig.getProviderKey())) {
            throw new IllegalStateException(
                    "model.providerKey 未命中 providers：" + modelConfig.getProviderKey());
        }

        String globalDefaultModel = StrUtil.nullToEmpty(modelConfig.getDefault()).trim();
        for (Map.Entry<String, ProviderConfig> entry : providers.entrySet()) {
            String providerKey = StrUtil.nullToEmpty(entry.getKey()).trim();
            ProviderConfig provider = entry.getValue();
            if (provider == null) {
                throw new IllegalStateException("provider 配置不能为空：" + providerKey);
            }
            if (StrUtil.isBlank(providerKey)) {
                throw new IllegalStateException("provider key 不能为空。");
            }
            if (StrUtil.isBlank(provider.getBaseUrl())) {
                throw new IllegalStateException("provider.baseUrl 不能为空：" + providerKey);
            }
            try {
                LlmProviderSupport.validateBaseUrl(provider.getBaseUrl());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "provider.baseUrl 配置无效：" + providerKey + "，" + e.getMessage(), e);
            }
            if (StrUtil.isBlank(provider.getDialect())) {
                throw new IllegalStateException("provider.dialect 不能为空：" + providerKey);
            }
            if (!LlmProviderSupport.isSupportedDialect(provider.getDialect())) {
                throw new IllegalStateException("不支持的 provider dialect：" + provider.getDialect());
            }
            if (StrUtil.isBlank(provider.getDefaultModel())
                    && StrUtil.isBlank(globalDefaultModel)) {
                throw new IllegalStateException("provider.defaultModel 不能为空：" + providerKey);
            }
        }

        if (fallbackChain == null) {
            return;
        }
        for (FallbackProviderConfig fallback : fallbackChain) {
            if (fallback == null || StrUtil.isBlank(fallback.getProvider())) {
                throw new IllegalStateException("fallbackProviders.provider 不能为空。");
            }
            ProviderConfig provider = providers.get(fallback.getProvider().trim());
            if (provider == null) {
                throw new IllegalStateException(
                        "fallbackProviders 引用了不存在的 provider：" + fallback.getProvider());
            }
            if (StrUtil.isBlank(fallback.getModel())
                    && StrUtil.isBlank(provider.getDefaultModel())
                    && StrUtil.isBlank(globalDefaultModel)) {
                throw new IllegalStateException(
                        "fallback provider 缺少可用模型：" + fallback.getProvider());
            }
        }
    }

    /**
     * 读取Nested String。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回读取到的Nested String。
     */
    private static String readNestedString(Map<Object, Object> map, String key) {
        if (map == null || key == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 加载Structured Overrides。
     *
     * @param runtimeHome 运行时主渠道参数。
     * @return 返回Structured Overrides结果。
     */
    private static Map<String, Object> loadStructuredOverrides(File runtimeHome) {
        File configFile = new File(runtimeHome, "config.yml");
        if (!configFile.exists()) {
            return Collections.emptyMap();
        }

        try {
            Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }
            return sanitizeStructuredMap((Map<?, ?>) parsed);
        } catch (Exception e) {
            log.warn("运行时结构化配置读取失败，忽略 config.yml 覆盖: error={}", exceptionSummary(e));
            return Collections.emptyMap();
        }
    }

    /**
     * 清理Structured Map。
     *
     * @param raw 原始输入值。
     * @return 返回Structured Map结果。
     */
    private static Map<String, Object> sanitizeStructuredMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map) {
                result.put(key, sanitizeStructuredMap((Map<?, ?>) value));
            } else if (value instanceof List) {
                result.put(key, sanitizeStructuredList((List<?>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 清理Structured List。
     *
     * @param raw 原始输入值。
     * @return 返回Structured List结果。
     */
    private static List<Object> sanitizeStructuredList(List<?> raw) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : raw) {
            if (item instanceof Map) {
                result.add(sanitizeStructuredMap((Map<?, ?>) item));
            } else if (item instanceof List) {
                result.add(sanitizeStructuredList((List<?>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 加载Flat Overrides。
     *
     * @param runtimeHome 运行时主渠道参数。
     * @return 返回Flat Overrides结果。
     */
    private static Map<String, Object> loadFlatOverrides(File runtimeHome) {
        File configFile = new File(runtimeHome, "config.yml");
        if (!configFile.exists()) {
            return Collections.emptyMap();
        }

        try {
            Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            flatten("", (Map<?, ?>) parsed, result);
            return result;
        } catch (Exception e) {
            log.warn("运行时扁平配置读取失败，忽略 config.yml 覆盖: error={}", exceptionSummary(e));
            return Collections.emptyMap();
        }
    }

    /**
     * 执行flatten相关逻辑。
     *
     * @param prefix prefix 参数。
     * @param input 输入参数。
     * @param output 命令执行输出文本。
     */
    private static void flatten(String prefix, Map<?, ?> input, Map<String, Object> output) {
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key =
                    prefix.length() == 0
                            ? String.valueOf(entry.getKey())
                            : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<?, ?>) value, output);
            } else {
                output.put(key, value);
            }
        }
    }

    /**
     * 执行asAbsolute静态资源相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @param base 基础参数。
     * @return 返回as Absolute静态资源结果。
     */
    private static File asAbsoluteStatic(File file, File base) {
        if (file.isAbsolute()) {
            return file;
        }
        return new File(base, file.getPath());
    }

    /**
     * 解析运行 Jar 所在目录；测试或解包运行时回退到当前进程目录。
     *
     * @param fallbackBase 无法识别 Jar 路径时使用的目录。
     * @return 返回用于解析工作区相对路径的基准目录。
     */
    private static File jarBaseDir(File fallbackBase) {
        try {
            java.net.URL location =
                    AppConfig.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return fallbackBase;
            }
            File file = new File(location.toURI()).getAbsoluteFile();
            if (file.isFile()) {
                File parent = file.getParentFile();
                return parent == null ? fallbackBase : parent;
            }
        } catch (Exception e) {
            log.debug("运行 Jar 目录解析失败，回退到启动目录: {}", exceptionSummary(e));
        }
        return fallbackBase;
    }

    /**
     * 解析Initial运行时主渠道。
     *
     * @param props props 参数。
     * @return 返回解析后的Initial运行时主渠道。
     */
    private static String resolveInitialRuntimeHome(Props props) {
        return props.get("solonclaw.runtime.home", RuntimePathConstants.RUNTIME_HOME);
    }

    /**
     * 执行initialize运行时配置IfMissing相关逻辑。
     *
     * @param runtimeHome 运行时主渠道参数。
     */
    private static void initializeRuntimeConfigIfMissing(File runtimeHome) {
        File configFile = new File(runtimeHome, RuntimePathConstants.CONFIG_FILE_NAME);
        if (configFile.exists()) {
            return;
        }

        try {
            FileUtil.mkParentDirs(configFile);
            FileUtil.writeUtf8String(defaultRuntimeConfigContent(), configFile);
        } catch (Exception e) {
            log.warn("运行配置初始化失败，后续保存配置时仍会提示用户修复权限: error={}", exceptionSummary(e));
        }
    }

    /**
     * 执行默认运行时配置Content相关逻辑。
     *
     * @return 返回默认运行时配置Content结果。
     */
    private static String defaultRuntimeConfigContent() {
        return "# SolonClaw 最小运行配置。\n"
                + "# 启动时自动创建；可通过 Dashboard 或直接编辑本文件继续完善。\n"
                + "providers:\n"
                + "  default:\n"
                + "    name: DefaultProvider\n"
                + "    baseUrl: https://api.openai.com\n"
                + "    apiKey: \"\"\n"
                + "    defaultModel: gpt-5.4\n"
                + "    dialect: openai\n"
                + "\n"
                + "model:\n"
                + "  providerKey: default\n"
                + "  default: \"gpt-5.4\"\n"
                + "\n"
                + "fallbackProviders: []\n"
                + "\n"
                + "solonclaw:\n"
                + "  # Agent 默认工作区；相对路径按运行 Jar 所在目录解析。\n"
                + "  workspace: ./workspace\n"
                + "  dashboard:\n"
                + "    # Dashboard 访问令牌必须由部署方设置；留空时拒绝非公开 API 鉴权。\n"
                + "    accessToken: \"\"\n";
    }

    /**
     * 执行同步运行时配置Example相关逻辑。
     *
     * @param runtimeHome 运行时主渠道参数。
     */
    private static void syncRuntimeConfigExample(String runtimeHome) {
        try (InputStream stream =
                AppConfig.class
                        .getClassLoader()
                        .getResourceAsStream(RuntimePathConstants.CONFIG_EXAMPLE_FILE_NAME)) {
            if (stream == null) {
                return;
            }
            File target =
                    new File(
                            StrUtil.blankToDefault(runtimeHome, RuntimePathConstants.RUNTIME_HOME),
                            RuntimePathConstants.CONFIG_EXAMPLE_FILE_NAME);
            FileUtil.mkParentDirs(target);
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.debug("运行时示例配置同步失败，跳过非关键参考文件写入: {}", exceptionSummary(e));
        }
    }

    /**
     * 生成配置异常摘要；只返回异常类型，避免解析错误中携带的配置值或密钥进入日志。
     *
     * @param error 配置读取或转换时捕获的异常。
     * @return 可写入日志的异常类型摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    /**
     * 执行运行时Child路径相关逻辑。
     *
     * @param runtimeHome 运行时主渠道参数。
     * @param childName child名称参数。
     * @return 返回运行时Child路径。
     */
    private static String runtimeChildPath(String runtimeHome, String childName) {
        return new File(
                        StrUtil.blankToDefault(runtimeHome, RuntimePathConstants.RUNTIME_HOME),
                        childName)
                .getPath();
    }

    /**
     * 执行运行时Child路径相关逻辑。
     *
     * @param runtimeHome 运行时主渠道参数。
     * @param childName child名称参数。
     * @param fileName 文件或目录路径参数。
     * @return 返回运行时Child路径。
     */
    private static String runtimeChildPath(String runtimeHome, String childName, String fileName) {
        return new File(runtimeChildPath(runtimeHome, childName), fileName).getPath();
    }

    /** 运行时目录配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RuntimeConfig {
        /** 运行时根目录。 */
        private String home;

        /** 上下文文件目录。 */
        private String contextDir;

        /** skills 本地目录。 */
        private String skillsDir;

        /** 缓存目录。 */
        private String cacheDir;

        /** SQLite 状态库路径。 */
        private String stateDb;

        /** runtime/config.yml 路径。 */
        private String configFile;

        /** runtime/logs 目录。 */
        private String logsDir;
    }

    /** Agent 工作区配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class WorkspaceConfig {
        /** Agent 文件读写、普通命令和项目上下文默认执行的目录。 */
        private String dir = RuntimePathConstants.DEFAULT_WORKSPACE;
    }

    /** 大模型接入配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class LlmConfig {
        /** 运行时 provider key。 */
        private String provider;

        /** 协议方言。 */
        private String dialect;

        /** 请求地址。 */
        private String apiUrl;

        /** 访问密钥。 */
        private String apiKey;

        /** 默认模型名。 */
        private String model;

        /** 是否使用流式输出。 */
        private boolean stream;

        /** 推理强度。 */
        private String reasoningEffort;

        /** 温度参数。 */
        private double temperature;

        /** 最大输出 token。 */
        private int maxTokens;

        /** 模型上下文窗口大小，用于自动压缩阈值计算。 */
        private int contextWindowTokens;

        /** 提示词缓存配置。 */
        private PromptCacheConfig promptCache = new PromptCacheConfig();
    }

    /** 提示词缓存配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PromptCacheConfig {
        /** 标记该配置项或记录是否处于启用状态。 */
        private boolean enabled;

        /** 记录提示词缓存中的ttl。 */
        private String ttl = "5m";

        /** 记录提示词缓存中的layout。 */
        private String layout = "system_and_3";
    }

    /** 命名 provider 配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ProviderConfig {
        /** 记录提供方中的名称。 */
        private String name;

        /** 记录提供方中的基础URL。 */
        private String baseUrl;

        /** 记录提供方中的api键。 */
        private String apiKey;

        /** 记录提供方中的默认模型。 */
        private String defaultModel;

        /** 记录提供方中的协议方言。 */
        private String dialect;

        /** 是否启用supportsVision。 */
        private Boolean supportsVision;

        /** 记录提供方中的群组标识。 */
        private String groupId;

        /** 记录提供方中的群组Label。 */
        private String groupLabel;

        /** 记录提供方中的群组描述。 */
        private String groupDescription;

        /** 记录提供方中的展示描述。 */
        private String displayDescription;
    }

    /** 当前主模型选择配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ModelConfig {
        /** 记录模型中的提供方键。 */
        private String providerKey;

        /** 记录模型中的默认模型。 */
        private String defaultModel;

        /**
         * 读取默认。
         *
         * @return 返回读取到的默认。
         */
        public String getDefault() {
            return defaultModel;
        }

        /**
         * 写入默认。
         *
         * @param defaultModel 默认模型参数。
         */
        public void setDefault(String defaultModel) {
            this.defaultModel = defaultModel;
        }
    }

    /** 主模型故障切换项。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class FallbackProviderConfig {
        /** 记录兜底提供方中的提供方。 */
        private String provider;

        /** 记录兜底提供方中的模型。 */
        private String model;
    }

    /** 调度器配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SchedulerConfig {
        /** 是否启用调度器。 */
        private boolean enabled;

        /** 调度轮询周期，单位秒。 */
        private int tickSeconds;

        /** 是否默认包装 cron 投递回复。 */
        private boolean wrapResponse = true;

        /** cron 脚本执行超时时间，单位秒。 */
        private int scriptTimeoutSeconds = 120;

        /** cron Agent 无活动超时时间，单位秒；0 表示不限制。 */
        private int inactivityTimeoutSeconds = 600;

        /** 未设置 job.enabled_toolsets 时，cron 平台默认启用的工具集；空列表表示沿用全部默认工具。 */
        private List<String> enabledToolsets = new ArrayList<String>();
    }

    /** 上下文压缩配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class CompressionConfig {
        /** 是否启用自动压缩。 */
        private boolean enabled = true;

        /** 触发压缩的上下文阈值百分比。 */
        private double thresholdPercent = CompressionConstants.DEFAULT_THRESHOLD_PERCENT;

        /** 可选的压缩摘要模型。 */
        private String summaryModel;

        /** 头部消息保护数量。 */
        private int protectHeadMessages = CompressionConstants.DEFAULT_PROTECT_HEAD_MESSAGES;

        /** 尾部消息保护比例。 */
        private double tailRatio = CompressionConstants.DEFAULT_TAIL_RATIO;
    }

    /** 任务后学习闭环配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class LearningConfig {
        /** 是否启用自动学习。 */
        private boolean enabled = true;

        /** 触发自动学习的最少工具调用数。 */
        private int toolCallThreshold = 5;

        /** 辅助模型分类/总结调用总超时，单位秒。 */
        private int auxiliaryTimeoutSeconds = 60;
    }

    /** 技能后台维护配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class CuratorConfig {
        /** 是否启用技能后台维护。 */
        private boolean enabled = true;

        /** 后台巡检周期，单位小时。 */
        private int intervalHours = 168;

        /** 最小空闲窗口，单位小时。 */
        private double minIdleHours = 2.0D;

        /** 多久未使用后标记为 stale。 */
        private int staleAfterDays = 30;

        /** 多久未使用后归档。 */
        private int archiveAfterDays = 90;
    }

    /** 技能目录配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SkillsConfig {
        /** 额外只读技能目录清单。 */
        private List<String> externalDirs = new ArrayList<String>();

        /** 是否启用 SKILL.md 模板变量替换。 */
        private boolean templateVars = true;

        /** 是否启用 SKILL.md inline shell 预处理。 */
        private boolean inlineShell = false;

        /** inline shell 预处理超时时间，单位秒。 */
        private int inlineShellTimeoutSeconds = 10;
    }

    /** 文件快照与回滚配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RollbackConfig {
        /** 是否启用文件快照与回滚。 */
        private boolean enabled = true;

        /** 单来源键保留的最大 checkpoint 数。 */
        private int maxCheckpointsPerSource =
                CheckpointConstants.DEFAULT_MAX_CHECKPOINTS_PER_SOURCE;

        /** 单文件快照大小上限，单位 MB。 */
        private int maxFileSizeMb = CheckpointConstants.DEFAULT_MAX_FILE_SIZE_MB;

        /** 默认排除模式，避免 checkpoint 膨胀或保存密钥。 */
        private List<String> excludePatterns =
                new ArrayList<String>(
                        Arrays.asList(
                                "node_modules/",
                                "dist/",
                                "build/",
                                "target/",
                                "out/",
                                ".next/",
                                ".nuxt/",
                                "__pycache__/",
                                "*.pyc",
                                "*.pyo",
                                ".cache/",
                                ".pytest_cache/",
                                ".mypy_cache/",
                                ".ruff_cache/",
                                "coverage/",
                                ".coverage",
                                ".venv/",
                                "venv/",
                                "env/",
                                ".git/",
                                ".hg/",
                                ".svn/",
                                ".worktrees/",
                                "*.so",
                                "*.dylib",
                                "*.dll",
                                "*.o",
                                "*.a",
                                "*.jar",
                                "*.class",
                                "*.exe",
                                "*.obj",
                                "*.mp4",
                                "*.mov",
                                "*.mkv",
                                "*.webm",
                                "*.zip",
                                "*.tar",
                                "*.tar.gz",
                                "*.tgz",
                                "*.7z",
                                "*.rar",
                                "*.iso",
                                ".env",
                                ".envrc",
                                ".env.*",
                                ".env.local",
                                ".env.*.local",
                                ".DS_Store",
                                "Thumbs.db",
                                "*.log"));
    }

    /** 最终回复运行态 footer 配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RuntimeFooterConfig {
        /** 默认关闭，避免污染现有渠道回复。 */
        private boolean enabled = false;

        /** footer 字段顺序。 */
        private List<String> fields =
                new ArrayList<String>(Arrays.asList("model", "context_pct", "cwd"));
    }

    /** 聊天窗口显示配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class DisplayConfig {
        /** 默认工具进度模式。 */
        private String toolProgress = "all";

        /** 是否默认展示 reasoning。 */
        private boolean showReasoning;

        /** 恢复会话时是否展示紧凑历史：full / minimal。 */
        private String resumeDisplay = "full";

        /** 工具参数预览长度。 */
        private int toolPreviewLength = 80;

        /** reasoning/进度消息节流毫秒数。 */
        private int progressThrottleMs = 1500;

        /** 最终回复运行态 footer。 */
        private RuntimeFooterConfig runtimeFooter = new RuntimeFooterConfig();
    }

    /** Agent 行为配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class AgentConfig {
        /** 预定义人格列表。 */
        private Map<String, PersonalityConfig> personalities =
                new LinkedHashMap<String, PersonalityConfig>();

        /** HEARTBEAT.md 相关调度配置。 */
        private HeartbeatConfig heartbeat = new HeartbeatConfig();
    }

    /** heartbeat 调度配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class HeartbeatConfig {
        /** 固定轮询间隔（分钟）；0 表示关闭 heartbeat。 */
        private int intervalMinutes = RuntimePathConstants.DEFAULT_HEARTBEAT_INTERVAL_MINUTES;
    }

    /** 主动协作配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ProactiveConfig {
        /** 是否启用主动协作调度；关闭后不扫描候选事项也不主动触达用户。 */
        private boolean enabled = true;

        /** 主动协作扫描间隔，单位分钟。 */
        private int intervalMinutes = 30;

        /** 服务启动后首次扫描前的延迟，单位秒，避免启动阶段抢占资源。 */
        private int initialDelaySeconds = 60;

        /** 单日最多主动触达次数，用于限制打扰频率。 */
        private int dailyMaxContacts = 3;

        /** 同一用户或会话两次主动触达之间的冷却时间，单位分钟。 */
        private int cooldownMinutes = 120;

        /** 免打扰开始小时，使用 0-23 的本地小时。 */
        private int quietStartHour = 23;

        /** 免打扰结束小时，使用 0-23 的本地小时。 */
        private int quietEndHour = 8;

        /** 允许主动触达的最低候选置信度，低于该值仅记录不投递。 */
        private double minConfidenceToContact = 0.65D;

        /** 是否启用大模型辅助判断候选事项是否值得主动触达。 */
        private boolean llmDecisionEnabled = true;

        /** 是否启用大模型润色主动协作投递文案。 */
        private boolean llmPolishEnabled = true;

        /** 单次扫描最多评估的候选事项数量。 */
        private int maxCandidatesPerTick = 20;

        /** 单次扫描最多实际触达次数，防止一次扫描集中打扰。 */
        private int maxContactsPerTick = 1;

        /** 候选事项有效期，单位小时，超过后不再触达。 */
        private int candidateTtlHours = 72;

        /** 是否启用仓库状态检查候选源。 */
        private boolean repositoryCheckEnabled = true;

        /** 仓库状态检查的最小间隔，单位分钟。 */
        private int repositoryCheckIntervalMinutes = 360;

        /** 会话记录回看窗口，单位天。 */
        private int sessionLookbackDays = 30;

        /** Agent 运行记录回看窗口，单位天。 */
        private int runLookbackDays = 14;

        /** 定时任务记录回看窗口，单位天。 */
        private int cronLookbackDays = 14;

        /** 是否启用长时间空闲后的关怀式确认候选。 */
        private boolean careCheckinEnabled = true;

        /** 用户长时间无互动后触发关怀候选的空闲阈值，单位小时。 */
        private int careCheckinAfterIdleHours = 48;

        /** 主动协作投递预览前缀，用于渠道消息中标识触达来源。 */
        private String deliveryPreviewPrefix = "主动协作";
    }

    /** ReAct 推理控制配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ReActConfig {
        /** 主代理最大推理步数。 */
        private int maxSteps = 12;

        /** 主代理决策重试次数。 */
        private int retryMax = 3;

        /** 主代理决策重试基础延迟（毫秒）。 */
        private int retryDelayMs = 2000;

        /** 子代理最大推理步数。 */
        private int delegateMaxSteps = 18;

        /** 子代理决策重试次数。 */
        private int delegateRetryMax = 4;

        /** 子代理决策重试基础延迟（毫秒）。 */
        private int delegateRetryDelayMs = 2500;

        /** 是否启用 ReAct 工作记忆摘要守卫。 */
        private boolean summarizationEnabled = true;

        /** ReAct 摘要守卫触发的消息阈值。 */
        private int summarizationMaxMessages = 40;

        /** ReAct 摘要守卫触发的 token 阈值。 */
        private int summarizationMaxTokens = 32000;

        /** 是否启用重复工具调用软提醒。 */
        private boolean toolLoopWarningsEnabled = true;

        /** 是否启用重复工具调用硬停。 */
        private boolean toolLoopHardStopEnabled = false;

        /** 相同参数失败达到该次数后提醒。 */
        private int toolLoopExactFailureWarnAfter = 2;

        /** 相同参数失败达到该次数后，在硬停模式下阻断下一次执行。 */
        private int toolLoopExactFailureBlockAfter = 5;

        /** 同一工具连续失败达到该次数后提醒。 */
        private int toolLoopSameToolFailureWarnAfter = 3;

        /** 同一工具连续失败达到该次数后，在硬停模式下终止本轮。 */
        private int toolLoopSameToolFailureHaltAfter = 8;

        /** 只读工具相同结果达到该次数后提醒。 */
        private int toolLoopNoProgressWarnAfter = 2;

        /** 只读工具相同结果达到该次数后，在硬停模式下阻断下一次执行。 */
        private int toolLoopNoProgressBlockAfter = 5;
    }

    /** 承载Trace配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TraceConfig {
        /** 运行轨迹保留天数。 */
        private int retentionDays = 14;

        /** 每个 run 最大外层 attempt 数。 */
        private int maxAttempts = 2;

        /** 工具结果预览最大长度。 */
        private int toolPreviewLength = 1200;
    }

    /** 承载任务配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TaskConfig {
        /** 同一会话 busy 时的默认策略：queue / interrupt / steer / reject。 */
        private String busyPolicy = "interrupt";

        /** /restart 等待运行中任务 drain 的最长时间，单位秒；0 表示立即重启。 */
        private int restartDrainTimeoutSeconds = 180;

        /** stale run 判定窗口，单位分钟。 */
        private int staleAfterMinutes = 60;

        /** 子 Agent 最大并发。 */
        private int subagentMaxConcurrency = 3;

        /** 子 Agent 最大 spawn 深度。 */
        private int subagentMaxDepth = 1;

        /** 工具输出超过该长度时应落盘/摘要化。 */
        private int toolOutputInlineLimit = 50000;

        /** 单轮工具输出累计超过该长度时，后续输出会落盘/摘要化。 */
        private int toolOutputTurnBudget = 200000;

        /** 文件读取/分页输出的最大行数限制。 */
        private int toolOutputMaxLines = 2000;

        /** 文件读取/分页输出的单行截断长度。 */
        private int toolOutputMaxLineLength = 2000;

        /** 媒体缓存 TTL，单位小时。 */
        private int mediaCacheTtlHours = 168;
    }

    /** 承载终端配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TerminalConfig {
        /** 相对 runtime home 的凭据文件挂载清单。 */
        private List<String> credentialFiles = new ArrayList<String>();

        /** 允许技能显式传给本地子进程的第三方环境变量名。 */
        private List<String> envPassthrough = new ArrayList<String>();

        /** 执行非 Windows 本地 shell 前静默 source 的初始化文件。 */
        private List<String> shellInitFiles = new ArrayList<String>();

        /** 未显式配置初始化文件时自动尝试 ~/.profile 等文件。 */
        private boolean autoSourceBashrc = true;

        /** 用于 sudo -S 改写的 sudo 密码。 */
        private String sudoPassword;

        /** 本地文件写入安全根目录；配置后写入必须限制在该目录内。 */
        private String writeSafeRoot;

        /** 前台 terminal 最大超时时间，单位秒。 */
        private int maxForegroundTimeoutSeconds = 600;

        /** 前台 terminal 瞬时失败重试次数。 */
        private int foregroundMaxRetries = 3;

        /** 前台 terminal 执行异常重试的指数退避基准，单位秒；默认 2 秒，即 2/4/8。 */
        private int foregroundRetryBaseDelaySeconds = 2;

        /** process(wait) 单次阻塞时长限制，单位秒。 */
        private int processWaitTimeoutSeconds = 180;
    }

    /** 承载价格配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PricingConfig {
        /** 保存prices集合，维持调用顺序或去重语义。 */
        private List<ModelPrice> prices = new ArrayList<ModelPrice>();
    }

    /** 承载插件配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PluginConfig {
        /** 保存启用状态集合，维持调用顺序或去重语义。 */
        private List<String> enabled = new ArrayList<String>();

        /** 保存disabled集合，维持调用顺序或去重语义。 */
        private List<String> disabled = new ArrayList<String>();
    }

    /** 承载安全配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SecurityConfig {
        /** 是否允许 URL 工具访问内网/私有地址；默认关闭，云元数据地址始终阻断。 */
        private boolean allowPrivateUrls = false;

        /** 容器内浏览器访问宿主机服务时，是否改写页面导航里的 loopback 地址。 */
        private boolean rewriteBrowserLoopbackUrls = false;

        /** 容器内浏览器访问宿主机 loopback 服务使用的主机别名。 */
        private String browserLoopbackHostAlias = "host.docker.internal";

        /** 是否启用 Tirith 命令内容安全扫描。 */
        private boolean tirithEnabled = true;

        /** Tirith 可执行文件路径。 */
        private String tirithPath = "tirith";

        /** Tirith 单次扫描超时时间，单位秒。 */
        private int tirithTimeoutSeconds = 5;

        /** Tirith 不可用或超时时是否放行；默认 fail-closed，避免扫描缺失时静默放行。 */
        private boolean tirithFailOpen = false;

        /** 文件路径安全预检模式：strict / bypass。默认 strict，先阻断敏感系统或凭据路径。 */
        private String fileGuardrailMode = "strict";

        /** URL 安全预检模式：strict / bypass。默认 strict，先阻断 metadata 与敏感 URL 参数。 */
        private String urlGuardrailMode = "strict";

        /** Agent 工具安全策略模式：approval / strict / bypass / smart。默认 approval，危险行为必须审批。 */
        private String guardrailMode = "approval";

        /** Cron 工具安全策略模式：approval / strict / bypass / approve。默认 strict，避免无人值守自动放行。 */
        private String guardrailCronMode = "strict";

        /** Cron 审批记忆范围：job / session / global。 */
        private String guardrailCronScope = "job";

        /** 允许跳过硬阻断的 hardline 类别；* 表示所有类别。 */
        private List<String> hardlineAllowlist = new ArrayList<String>(defaultHardlineAllowlist());

        /** 网站访问阻断策略。 */
        private WebsiteBlocklistConfig websiteBlocklist = new WebsiteBlocklistConfig();
    }

    /** 承载网站块list配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class WebsiteBlocklistConfig {
        /** 是否启用域名阻断策略。 */
        private boolean enabled = false;

        /** 阻断域名列表，支持 example.com 和 *.example.com。 */
        private List<String> domains = new ArrayList<String>();

        /** 共享阻断列表文件，支持相对 runtime home 或绝对路径。 */
        private List<String> sharedFiles = new ArrayList<String>();
    }

    /** 承载Web配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class WebConfig {
        /** Websearch 后端；solon-ai 为默认内置实现，brave-free/ddgs 对齐可选搜索后端。 */
        private String searchBackend = "solon-ai";

        /** Brave Search API key；为空时也会尝试读取 BRAVE_SEARCH_API_KEY 环境变量。 */
        private String braveSearchApiKey;
    }

    /** 承载Approvals配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ApprovalsConfig {
        /** 子 Agent 遇到危险命令时是否自动批准一次，默认拒绝。 */
        private boolean subagentAutoApprove = false;

        /** CLI/直接审批超时秒数。 */
        private int timeoutSeconds = 60;

        /** 网关/渠道审批超时秒数。 */
        private int gatewayTimeoutSeconds = 300;

        /** /reload-mcp 是否需要确认，默认开启。 */
        private boolean mcpReloadConfirm = true;
    }

    /** 承载MCP配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class McpConfig {
        /** MCP 工具适配默认关闭。 */
        private boolean enabled = false;

        /** MCP 服务器 OAuth 认证配置。 */
        private McpOAuth oauth = new McpOAuth();
    }

    /** MCP 服务器 OAuth 认证配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class McpOAuth {
        /** OAuth 客户端 ID。 */
        private String clientId;

        /** OAuth 客户端密钥。 */
        private String clientSecret;

        /** OAuth token 端点 URL。 */
        private String tokenUrl;

        /** OAuth 请求的 scope。 */
        private String scope;
    }

    /** 单个人格定义。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PersonalityConfig {
        /** 描述文案。 */
        private String description;

        /** 系统提示词主体。 */
        private String systemPrompt;

        /** 额外语气提示。 */
        private String tone;

        /** 额外风格提示。 */
        private String style;

        /** 合并为最终注入文本。 */
        public String toPrompt() {
            StringBuilder buffer = new StringBuilder();
            if (StrUtil.isNotBlank(systemPrompt)) {
                buffer.append(systemPrompt.trim());
            }
            if (StrUtil.isNotBlank(tone)) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append("Tone: ").append(tone.trim());
            }
            if (StrUtil.isNotBlank(style)) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append("Style: ").append(style.trim());
            }
            return buffer.toString();
        }
    }

    /** 全部渠道配置集合。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ChannelsConfig {
        /** 飞书渠道配置。 */
        private ChannelConfig feishu = new ChannelConfig();

        /** 钉钉渠道配置。 */
        private ChannelConfig dingtalk = new ChannelConfig();

        /** 企微渠道配置。 */
        private ChannelConfig wecom = new ChannelConfig();

        /** 微信渠道配置。 */
        private ChannelConfig weixin = new ChannelConfig();

        /** QQ Bot 渠道配置。 */
        private ChannelConfig qqbot = new ChannelConfig();

        /** 腾讯元宝渠道配置。 */
        private ChannelConfig yuanbao = new ChannelConfig();
    }

    /** 单个渠道配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ChannelConfig {
        /** 是否启用该渠道。 */
        private boolean enabled;

        /** 飞书应用 ID。 */
        private String appId;

        /** 飞书应用密钥。 */
        private String appSecret;

        /** 飞书/Lark 租户域。 */
        private String domain = "feishu";

        /** 钉钉客户端 ID。 */
        private String clientId;

        /** 钉钉客户端密钥。 */
        private String clientSecret;

        /** 企微机器人标识。 */
        private String botId;

        /** 企微密钥。 */
        private String secret;

        /** 微信令牌。 */
        private String token;

        /** 微信 accountId。 */
        private String accountId;

        /** 钉钉机器人编码。 */
        private String robotCode;

        /** 钉钉 Cool App 编码。 */
        private String coolAppCode;

        /** WebSocket 地址。 */
        private String websocketUrl;

        /** Stream 地址。 */
        private String streamUrl;

        /** Long Poll 地址。 */
        private String longPollUrl;

        /** 渠道基础地址。 */
        private String baseUrl;

        /** 渠道 CDN 基础地址。 */
        private String cdnBaseUrl;

        /** 渠道允许名单。 */
        private List<String> allowedUsers = new ArrayList<String>();

        /** 私聊访问策略。 */
        private String dmPolicy = GatewayBehaviorConstants.DM_POLICY_OPEN;

        /** 群聊访问策略。 */
        private String groupPolicy = GatewayBehaviorConstants.GROUP_POLICY_OPEN;

        /** 群聊允许名单。 */
        private List<String> groupAllowedUsers = new ArrayList<String>();

        /** 群聊会话硬白名单，非空时只响应列表内群聊。 */
        private List<String> allowedChats = new ArrayList<String>();

        /** 企微按群发送者 allowlist。 */
        private Map<String, List<String>> groupMemberAllowedUsers =
                new LinkedHashMap<String, List<String>>();

        /** 飞书 bot open id。 */
        private String botOpenId;

        /** 飞书 bot user id。 */
        private String botUserId;

        /** 飞书 bot 展示名。 */
        private String botName;

        /** 是否允许该渠道所有用户访问。 */
        private boolean allowAllUsers;

        /** 未授权私聊行为。 */
        private String unauthorizedDmBehavior =
                GatewayBehaviorConstants.UNAUTHORIZED_DM_BEHAVIOR_PAIR;

        /** 微信是否按多行强制拆分。 */
        private boolean splitMultilineMessages;

        /** 微信入站文本去抖批量延迟。 */
        private double textBatchDelaySeconds = 3.0D;

        /** 微信入站长分片文本去抖批量延迟。 */
        private double textBatchSplitDelaySeconds = 5.0D;

        /** 微信分片发送间隔。 */
        private double sendChunkDelaySeconds = 0.35D;

        /** 微信分片重试次数。 */
        private int sendChunkRetries = 2;

        /** 微信分片重试间隔。 */
        private double sendChunkRetryDelaySeconds = 1.0D;

        /** 渠道默认工具进度模式。 */
        private String toolProgress;

        /** 钉钉长任务进度卡模板 ID。 */
        private String progressCardTemplateId;

        /** 渠道级 runtime footer 开关，null 表示继承全局。 */
        private Boolean runtimeFooterEnabled;

        /** 飞书文档评论智能回复开关。 */
        private boolean commentEnabled;

        /** 飞书评论与会话绑定文件。 */
        private String commentPairingFile;

        /** 钉钉 AI Card 是否使用增量流式更新。 */
        private boolean aiCardStreamingEnabled = true;

        /** 渠道 REST API 域名。 */
        private String apiDomain;

        /** 渠道是否支持 Markdown 文本。 */
        private boolean markdownSupport = true;
    }

    /** 网关通用授权配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class GatewayConfig {
        /** 全局允许名单。 */
        private List<String> allowedUsers = new ArrayList<String>();

        /** 是否允许所有用户访问。 */
        private boolean allowAllUsers;

        /** 记录消息网关中的injection密钥。 */
        private String injectionSecret;

        /** 记录消息网关中的injectionMax正文字节。 */
        private int injectionMaxBodyBytes = 65536;

        /** 记录消息网关中的injectionReplay窗口Seconds。 */
        private int injectionReplayWindowSeconds = 300;

        /** 是否启用过滤器SilenceNarration。 */
        private boolean filterSilenceNarration = true;

        /** 各平台工具集权限配置，键为平台名称（大写），值为该平台的工具集策略。 */
        private Map<String, PlatformConfig> platforms = new LinkedHashMap<String, PlatformConfig>();
    }

    /** 单平台工具集权限配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PlatformConfig {
        /** 该平台允许使用的工具集列表；空列表表示不限制（使用全局默认）。 */
        private List<String> enabledToolsets = new ArrayList<String>();

        /** 该平台禁用的工具集列表；优先级高于 enabledToolsets。 */
        private List<String> disabledToolsets = new ArrayList<String>();

        /** 该平台是否强制要求审批。 */
        private boolean approvalRequired = false;
    }

    /** 承载控制台配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class DashboardConfig {
        /** 记录控制台中的access token。 */
        private String accessToken;

        /** 记录控制台中的bind主机。 */
        private String bindHost;

        /** 记录控制台中的bind端口。 */
        private int bindPort = 8080;
    }
}
