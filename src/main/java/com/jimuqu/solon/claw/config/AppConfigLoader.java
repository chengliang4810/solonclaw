package com.jimuqu.solon.claw.config;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig.ChannelConfig;
import com.jimuqu.solon.claw.config.AppConfig.FallbackProviderConfig;
import com.jimuqu.solon.claw.config.AppConfig.ModelConfig;
import com.jimuqu.solon.claw.config.AppConfig.PersonalityConfig;
import com.jimuqu.solon.claw.config.AppConfig.PlatformConfig;
import com.jimuqu.solon.claw.config.AppConfig.ProactiveConfig;
import com.jimuqu.solon.claw.config.AppConfig.ProviderConfig;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.BootstrapPromptBudgetSupport;
import com.jimuqu.solon.claw.support.RuntimePathSupport;
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
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** 负责从 Solon Props 与运行时 config.yml 装配 AppConfig，避免主配置模型承担加载细节。 */
final class AppConfigLoader {
    /** 配置加载日志，异常摘要只记录类型，避免把配置值或密钥写入日志。 */
    private static final Logger log = LoggerFactory.getLogger(AppConfigLoader.class);

    /** 默认 provider 可通过扁平 Props 显式声明的模型能力键。 */
    private static final List<String> PROVIDER_CAPABILITY_KEYS =
            Arrays.asList(
                    "tool_calling",
                    "vision",
                    "audio",
                    "attachment",
                    "pdf",
                    "reasoning",
                    "structured_output",
                    "open_weights",
                    "interleaved",
                    "prompt_cache",
                    "streaming");

    private AppConfigLoader() {}

    /**
     * 从 Solon Props 构建应用配置。
     *
     * @param props Solon 启动时加载的配置源
     * @return 标准化后的配置对象
     */
    public static AppConfig load(Props props) {
        return load(props, true);
    }

    /**
     * 构建不影响当前进程全局配置解析器的独立配置快照。
     *
     * @param props 包含目标工作区的配置源。
     * @return 独立 Profile 的标准化配置快照。
     */
    static AppConfig loadDetached(Props props) {
        return load(props, false);
    }

    /** 按是否注册全局解析器装配配置，保持普通启动与 Profile 管理共用同一套解析规则。 */
    private static AppConfig load(Props props, boolean registerGlobalResolver) {
        AppConfig config = new AppConfig();
        File userDir = new File(System.getProperty("user.dir"));
        File workspaceBase = RuntimePathSupport.jarBaseDir(AppConfig.class, userDir);
        File workspaceHome =
                asAbsoluteStatic(
                        new File(resolveInitialWorkspace(props, Collections.emptyMap())),
                        workspaceBase);
        // 只读取启动前已经存在的运行配置；自动生成的模板不能在首次启动时覆盖命令行参数。
        Map<String, Object> overrides = loadFlatOverrides(workspaceHome);
        Map<String, Object> structuredOverrides = loadStructuredOverrides(workspaceHome);
        initializeRuntimeConfigIfMissing(workspaceHome, props);
        RuntimeConfigResolver configResolver =
                registerGlobalResolver
                        ? RuntimeConfigResolver.initialize(workspaceHome.getAbsolutePath())
                        : RuntimeConfigResolver.open(workspaceHome.getAbsolutePath());

        config.getRuntime().setHome(resolveConfigString(workspaceHome.getPath()));
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
        config.getWorkspace().setDir(resolveConfigString(workspaceHome.getPath()));

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
                .setContextFallbackTokens(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.llm.contextFallbackTokens",
                                                256000)),
                                256000));
        config.getLlm()
                .setModelsDevRefreshEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.llm.modelsDevRefreshEnabled",
                                        true)));
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
                                        "solonclaw.display.metadataFooter.enabled",
                                        false)));
        config.getDisplay()
                .getRuntimeFooter()
                .setFields(
                        resolveList(
                                readRaw(
                                        props,
                                        overrides,
                                        "solonclaw.display.metadataFooter.fields",
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
                                        "solonclaw.display.platforms.feishu.metadataFooter.enabled",
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
                                        "solonclaw.display.platforms.dingtalk.metadataFooter.enabled",
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
                                        "solonclaw.display.platforms.wecom.metadataFooter.enabled",
                                        null)));

        applyChannelConfig(
                config.getChannels().getWeixin(),
                props,
                overrides,
                "weixin",
                GatewayBehaviorConstants.DM_POLICY_PAIRING,
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
                                        "solonclaw.display.platforms.weixin.metadataFooter.enabled",
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
                                        "solonclaw.display.platforms.qqbot.metadataFooter.enabled",
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
                                        "solonclaw.display.platforms.yuanbao.metadataFooter.enabled",
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
                .setProcessingReactionsEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props,
                                        overrides,
                                        "solonclaw.gateway.processingReactionsEnabled",
                                        true)));
        config.getGateway().setMultiplexProfiles(readMultiplexProfiles(props, overrides));
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
                .setBootstrapPromptFileCharLimit(
                        BootstrapPromptBudgetSupport.normalizeFileCharLimit(
                                positiveInt(
                                        resolveInt(
                                                readInt(
                                                        props,
                                                        overrides,
                                                        "solonclaw.task.bootstrapPromptFileCharLimit",
                                                        12000)),
                                        12000)));
        config.getTask()
                .setBootstrapPromptTotalCharBudget(
                        BootstrapPromptBudgetSupport.normalizeTotalCharBudget(
                                positiveInt(
                                        resolveInt(
                                                readInt(
                                                        props,
                                                        overrides,
                                                        "solonclaw.task.bootstrapPromptTotalCharBudget",
                                                        48000)),
                                        48000)));
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
        config.getSpeech()
                .getTts()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "solonclaw.speech.tts.enabled", false)));
        config.getSpeech()
                .getTts()
                .setEndpoint(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.speech.tts.endpoint",
                                        "https://api.openai.com/v1/audio/speech")));
        config.getSpeech()
                .getTts()
                .setApiKey(
                        resolveConfigString(
                                readString(props, overrides, "solonclaw.speech.tts.apiKey", "")));
        config.getSpeech()
                .getTts()
                .setModel(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.speech.tts.model",
                                        "gpt-4o-mini-tts")));
        config.getSpeech()
                .getTts()
                .setVoice(
                        resolveConfigString(
                                readString(
                                        props, overrides, "solonclaw.speech.tts.voice", "alloy")));
        config.getSpeech()
                .getTts()
                .setResponseFormat(
                        normalizeSpeechOutputFormat(
                                resolveConfigString(
                                        readString(
                                                props,
                                                overrides,
                                                "solonclaw.speech.tts.responseFormat",
                                                "mp3"))));
        config.getSpeech()
                .getTts()
                .setSpeed(
                        normalizeSpeechSpeed(
                                resolveDouble(
                                        readDouble(
                                                props,
                                                overrides,
                                                "solonclaw.speech.tts.speed",
                                                1.0d))));
        config.getSpeech()
                .getTts()
                .setTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.speech.tts.timeoutSeconds",
                                                120)),
                                120));
        config.getSpeech()
                .getStt()
                .setEnabled(
                        resolveBoolean(
                                readBoolean(
                                        props, overrides, "solonclaw.speech.stt.enabled", false)));
        config.getSpeech()
                .getStt()
                .setEndpoint(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.speech.stt.endpoint",
                                        "https://api.openai.com/v1/audio/transcriptions")));
        config.getSpeech()
                .getStt()
                .setApiKey(
                        resolveConfigString(
                                readString(props, overrides, "solonclaw.speech.stt.apiKey", "")));
        config.getSpeech()
                .getStt()
                .setModel(
                        resolveConfigString(
                                readString(
                                        props,
                                        overrides,
                                        "solonclaw.speech.stt.model",
                                        "gpt-4o-mini-transcribe")));
        config.getSpeech()
                .getStt()
                .setLanguage(
                        resolveConfigString(
                                readString(props, overrides, "solonclaw.speech.stt.language", "")));
        config.getSpeech()
                .getStt()
                .setPrompt(
                        resolveConfigString(
                                readString(props, overrides, "solonclaw.speech.stt.prompt", "")));
        config.getSpeech()
                .getStt()
                .setTimeoutSeconds(
                        positiveInt(
                                resolveInt(
                                        readInt(
                                                props,
                                                overrides,
                                                "solonclaw.speech.stt.timeoutSeconds",
                                                120)),
                                120));
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
                                readBoolean(props, overrides, "security.tirithFailOpen", true)));
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
                                        Collections.emptyList())));
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
        config.getApprovals().setDeny(resolveList(readRaw(props, overrides, "approvals.deny", "")));
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

        validateSpeechConfiguration(config.getSpeech());
        config.normalizePaths();
        syncRuntimeConfigExample(config.getRuntime().getHome());
        return config;
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
        channelConfig.setBaseUrl(
                resolveConfigString(
                        readString(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".baseUrl",
                                "")));
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
        channelConfig.setRequireMention(
                resolveBoolean(
                        readBoolean(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".requireMention",
                                true)));
        channelConfig.setFreeResponseChats(
                resolveList(
                        readRaw(
                                props,
                                overrides,
                                "solonclaw.channels." + channelName + ".freeResponseChats",
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

    /** 规范化 OpenAI 兼容 TTS 输出格式，只保留服务端明确支持的音频类型。 */
    private static String normalizeSpeechOutputFormat(String raw) {
        String value = StrUtil.blankToDefault(raw, "mp3").trim().toLowerCase();
        if ("mp3".equals(value)
                || "opus".equals(value)
                || "aac".equals(value)
                || "flac".equals(value)
                || "wav".equals(value)
                || "pcm".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "solonclaw.speech.tts.responseFormat 只支持 mp3、opus、aac、flac、wav、pcm，当前值：" + raw);
    }

    /** 规范化 OpenAI 兼容 TTS 语速，防止无效配置到请求阶段才失败。 */
    private static double normalizeSpeechSpeed(double value) {
        if (Double.isFinite(value) && value >= 0.25d && value <= 4.0d) {
            return value;
        }
        throw new IllegalStateException("solonclaw.speech.tts.speed 只支持 0.25 到 4.0，当前值：" + value);
    }

    /**
     * 校验启用的语音 Provider 配置，避免把无效端点或空模型报告为可用。
     *
     * @param speechConfig 待校验的 TTS 与 STT 配置。
     */
    private static void validateSpeechConfiguration(AppConfig.SpeechConfig speechConfig) {
        if (speechConfig.getTts().isEnabled()) {
            validateSpeechEndpoint(
                    "solonclaw.speech.tts",
                    speechConfig.getTts().getEndpoint(),
                    speechConfig.getTts().getModel());
        }
        if (speechConfig.getStt().isEnabled()) {
            validateSpeechEndpoint(
                    "solonclaw.speech.stt",
                    speechConfig.getStt().getEndpoint(),
                    speechConfig.getStt().getModel());
        }
    }

    /** 校验单个 OpenAI 兼容语音端点与模型。 */
    private static void validateSpeechEndpoint(String key, String endpoint, String model) {
        if (StrUtil.isBlank(endpoint)) {
            throw new IllegalStateException(key + ".endpoint 不能为空。");
        }
        if (StrUtil.isBlank(model)) {
            throw new IllegalStateException(key + ".model 不能为空。");
        }
        try {
            LlmProviderSupport.validateBaseUrl(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + ".endpoint 配置无效：" + e.getMessage(), e);
        }
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
        if ("approval".equals(value) || "bypass".equals(value) || "smart".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.guardrailMode 只支持 approval、bypass、smart，当前值：" + raw);
    }

    /**
     * 规范化定时任务防护模式，只接受当前配置枚举。
     *
     * @param raw 原始输入值。
     * @return 返回防护定时任务模式结果。
     */
    private static String normalizeGuardrailCronMode(String raw) {
        String value = StrUtil.blankToDefault(raw, "strict").trim().toLowerCase();
        if ("strict".equals(value)
                || "approval".equals(value)
                || "bypass".equals(value)
                || "approve".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.guardrailCronMode 只支持 strict、approval、bypass、approve，当前值：" + raw);
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

    /** 规范化文件与 URL 的二元预检模式，只接受 strict 和 bypass。 */
    private static String normalizeBinaryGuardrailMode(String raw) {
        String value = StrUtil.blankToDefault(raw, "strict").trim().toLowerCase();
        if ("strict".equals(value) || "bypass".equals(value)) {
            return value;
        }
        throw new IllegalStateException(
                "security.fileGuardrailMode/security.urlGuardrailMode 只支持 strict、bypass，当前值："
                        + raw);
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
        price.applyTokenPrice(field, costPerMillion, microsPerToken);
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
        Object direct = props.get((Object) key);
        if (direct != null) {
            return String.valueOf(direct).trim();
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
        Object value = props.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            log.debug("整型配置解析失败，使用默认值: key={}, error={}", key, exceptionSummary(e));
            return defaultValue;
        }
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

    /** 读取私网 URL 放行开关；环境变量只接受当前项目命名。 */
    private static boolean readAllowPrivateUrls(Props props, Map<String, Object> overrides) {
        String env =
                StrUtil.nullToEmpty(
                                ProfileRuntimeScope.environmentValue(
                                        "SOLONCLAW_ALLOW_PRIVATE_URLS"))
                        .trim();
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
     * 读取 Profile 复用网关开关；当前项环境变量只接受当前项目命名和明确布尔令牌。
     *
     * <p>优先级为环境变量、config.yml、内置默认值。空白或未知环境变量不会覆盖文件配置。
     */
    private static boolean readMultiplexProfiles(Props props, Map<String, Object> overrides) {
        boolean configured =
                readBoolean(props, overrides, "solonclaw.gateway.multiplexProfiles", false);
        return resolveMultiplexProfiles(
                ProfileRuntimeScope.environmentValue("SOLONCLAW_GATEWAY_MULTIPLEX_PROFILES"),
                configured);
    }

    /** 根据三态环境变量解析复用网关开关，供配置加载与定向回归共用。 */
    static boolean resolveMultiplexProfiles(String raw, boolean configured) {
        if (raw == null || raw.trim().length() == 0) {
            return configured;
        }
        String token = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (Arrays.asList("1", "true", "yes", "on").contains(token)) {
            return true;
        }
        if (Arrays.asList("0", "false", "no", "off").contains(token)) {
            return false;
        }
        log.warn("忽略无法识别的 SOLONCLAW_GATEWAY_MULTIPLEX_PROFILES，继续使用 config.yml 配置。");
        return configured;
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
            applyProviderCapabilities(rawProvider, provider);
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
            copy.setCapabilities(
                    source.getCapabilities() == null
                            ? new LinkedHashMap<String, Boolean>()
                            : new LinkedHashMap<String, Boolean>(source.getCapabilities()));
            copy.setGroupId(source.getGroupId());
            copy.setGroupLabel(source.getGroupLabel());
            copy.setGroupDescription(source.getGroupDescription());
            copy.setDisplayDescription(source.getDisplayDescription());
        }
        return copy;
    }

    /**
     * 读取 provider.capabilities 中的显式布尔能力元数据。
     *
     * @param rawProvider 原始 provider 配置。
     * @param provider 目标 provider 配置。
     */
    @SuppressWarnings("unchecked")
    private static void applyProviderCapabilities(
            Map<Object, Object> rawProvider, ProviderConfig provider) {
        Object rawCapabilities = rawProvider.get("capabilities");
        if (!(rawCapabilities instanceof Map)) {
            return;
        }
        Map<String, Boolean> capabilities =
                provider.getCapabilities() == null
                        ? new LinkedHashMap<String, Boolean>()
                        : new LinkedHashMap<String, Boolean>(provider.getCapabilities());
        for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) rawCapabilities).entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            Boolean value = resolveOptionalBoolean(entry.getValue());
            if (StrUtil.isNotBlank(key) && value != null) {
                capabilities.put(key, value);
            }
        }
        provider.setCapabilities(capabilities);
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
        Map<String, Boolean> capabilities = new LinkedHashMap<String, Boolean>();
        for (String key : PROVIDER_CAPABILITY_KEYS) {
            Boolean value =
                    resolveOptionalBoolean(props.get("providers.default.capabilities." + key));
            if (value != null) {
                capabilities.put(key, value);
            }
        }
        provider.setCapabilities(capabilities);
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
     * @param workspaceHome 运行时主渠道参数。
     * @return 返回Structured Overrides结果。
     */
    private static Map<String, Object> loadStructuredOverrides(File workspaceHome) {
        File configFile = new File(workspaceHome, "config.yml");
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
     * @param workspaceHome 运行时主渠道参数。
     * @return 返回Flat Overrides结果。
     */
    private static Map<String, Object> loadFlatOverrides(File workspaceHome) {
        File configFile = new File(workspaceHome, "config.yml");
        if (!configFile.exists()) {
            return Collections.emptyMap();
        }

        try {
            Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            ConfigFlattenSupport.flatten("", (Map<?, ?>) parsed, result);
            return result;
        } catch (Exception e) {
            log.warn("运行时扁平配置读取失败，忽略 config.yml 覆盖: error={}", exceptionSummary(e));
            return Collections.emptyMap();
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
     * 解析启动阶段的工作区目录；工作区配置文件也放在该目录下。
     *
     * @param props Solon 启动配置。
     * @param overrides 已读取的工作区配置覆盖项，首次探测时可为空。
     * @return 工作区目录配置值。
     */
    private static String resolveInitialWorkspace(Props props, Map<String, Object> overrides) {
        return readString(
                props, overrides, "solonclaw.workspace", RuntimePathConstants.DEFAULT_WORKSPACE);
    }

    /**
     * 执行initialize工作区配置IfMissing相关逻辑。
     *
     * @param workspaceHome 工作区根目录。
     * @param props Solon 启动配置，用于生成首次运行配置中的非密 provider 字段。
     */
    private static void initializeRuntimeConfigIfMissing(File workspaceHome, Props props) {
        File configFile = new File(workspaceHome, RuntimePathConstants.CONFIG_FILE_NAME);
        if (configFile.exists()) {
            return;
        }

        try {
            FileUtil.mkParentDirs(configFile);
            FileUtil.writeUtf8String(defaultRuntimeConfigContent(props), configFile);
        } catch (Exception e) {
            log.warn("运行配置初始化失败，后续保存配置时仍会提示用户修复权限: error={}", exceptionSummary(e));
        }
    }

    /**
     * 生成默认工作区配置内容；只持久化启动 provider 的非密字段，避免首次运行模板反向覆盖命令行模型配置。
     *
     * @return 返回默认工作区配置Content结果。
     */
    private static String defaultRuntimeConfigContent(Props props) {
        ProviderConfig provider = loadDefaultProvider(props);
        ModelConfig model = parseModelConfig(null, props);
        String modelDefault =
                StrUtil.blankToDefault(model.getDefault(), provider.getDefaultModel());
        return "# solonclaw 最小运行配置。\n"
                + "# 启动时自动创建；可通过 Dashboard 或直接编辑本文件继续完善。\n"
                + "providers:\n"
                + "  default:\n"
                + "    name: "
                + yamlDoubleQuoted(provider.getName())
                + "\n"
                + "    baseUrl: "
                + yamlDoubleQuoted(provider.getBaseUrl())
                + "\n"
                + "    apiKey: \"\"\n"
                + "    defaultModel: "
                + yamlDoubleQuoted(provider.getDefaultModel())
                + "\n"
                + "    dialect: "
                + yamlDoubleQuoted(provider.getDialect())
                + "\n"
                + "\n"
                + "model:\n"
                + "  providerKey: "
                + yamlDoubleQuoted(model.getProviderKey())
                + "\n"
                + "  default: "
                + yamlDoubleQuoted(modelDefault)
                + "\n"
                + "\n"
                + "fallbackProviders: []\n"
                + "\n"
                + "solonclaw:\n"
                + "  dashboard:\n"
                + "    # Dashboard 访问令牌必须由部署方设置；留空时拒绝非公开 API 鉴权。\n"
                + "    accessToken: \"\"\n";
    }

    /**
     * 将配置值写成 YAML 双引号字符串，保证 URL、模型名和中文展示名可安全落盘。
     *
     * @param value 原始配置值。
     * @return YAML 双引号字符串。
     */
    private static String yamlDoubleQuoted(String value) {
        String raw = StrUtil.nullToEmpty(value);
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 执行同步工作区配置Example相关逻辑。
     *
     * @param workspaceHome 工作区根目录。
     */
    private static void syncRuntimeConfigExample(String workspaceHome) {
        try (InputStream stream =
                AppConfig.class
                        .getClassLoader()
                        .getResourceAsStream(RuntimePathConstants.CONFIG_EXAMPLE_FILE_NAME)) {
            if (stream == null) {
                return;
            }
            File target =
                    new File(
                            StrUtil.blankToDefault(
                                    workspaceHome, RuntimePathConstants.WORKSPACE_HOME),
                            RuntimePathConstants.CONFIG_EXAMPLE_FILE_NAME);
            FileUtil.mkParentDirs(target);
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.debug("工作区示例配置同步失败，跳过非关键参考文件写入: {}", exceptionSummary(e));
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
     * 执行工作区Child路径相关逻辑。
     *
     * @param workspaceHome 工作区根目录。
     * @param childName child名称参数。
     * @return 返回工作区Child路径。
     */
    private static String runtimeChildPath(String workspaceHome, String childName) {
        return new File(
                        StrUtil.blankToDefault(workspaceHome, RuntimePathConstants.WORKSPACE_HOME),
                        childName)
                .getPath();
    }

    /**
     * 执行工作区Child路径相关逻辑。
     *
     * @param workspaceHome 工作区根目录。
     * @param childName child名称参数。
     * @param fileName 文件或目录路径参数。
     * @return 返回工作区Child路径。
     */
    private static String runtimeChildPath(
            String workspaceHome, String childName, String fileName) {
        return new File(runtimeChildPath(workspaceHome, childName), fileName).getPath();
    }
}
