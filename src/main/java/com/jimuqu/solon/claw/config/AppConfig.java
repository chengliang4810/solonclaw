package com.jimuqu.solon.claw.config;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.support.RuntimePathSupport;
import com.jimuqu.solon.claw.support.constants.CheckpointConstants;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.solon.core.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** goal 目标循环配置。 */
    private GoalConfig goal = new GoalConfig();

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

    /** 多 Profile 管理配置。 */
    private ProfilesConfig profiles = new ProfilesConfig();

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

    /** TTS 与独立语音转写配置。 */
    private SpeechConfig speech = new SpeechConfig();

    /** 终端/沙箱执行配置。 */
    private TerminalConfig terminal = new TerminalConfig();

    /** 安全策略配置。 */
    private SecurityConfig security = new SecurityConfig();

    /** Web 工具配置。 */
    private WebConfig web = new WebConfig();

    /** 记录应用中的价格。 */
    private PricingConfig pricing = new PricingConfig();

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
        return AppConfigLoader.load(props);
    }

    /**
     * 从指定 Props 构建独立配置快照，不切换当前进程的运行时配置解析器。
     *
     * <p>该入口用于 Dashboard 管理其他 Profile，避免并发请求把全局配置解析器临时指向另一个工作区。
     *
     * @param props 包含目标工作区的配置源。
     * @return 独立 Profile 的标准化配置快照。
     */
    public static AppConfig loadDetached(Props props) {
        return AppConfigLoader.loadDetached(props);
    }

    /** 标准化路径：所有工作文件由 Agent 工作区统一承载。 */
    public void normalizePaths() {
        File userDir = new File(System.getProperty("user.dir"));
        File workspaceBase = RuntimePathSupport.jarBaseDir(AppConfig.class, userDir);
        File workspaceHome =
                asAbsolute(
                        new File(
                                StrUtil.blankToDefault(
                                        workspace.getDir(),
                                        RuntimePathConstants.DEFAULT_WORKSPACE)),
                        workspaceBase);
        workspace.setDir(workspaceHome.getAbsolutePath());
        runtime.setHome(workspaceHome.getAbsolutePath());
        runtime.setContextDir(
                new File(workspaceHome, RuntimePathConstants.CONTEXT_DIR_NAME).getAbsolutePath());
        runtime.setSkillsDir(
                new File(workspaceHome, RuntimePathConstants.SKILLS_DIR_NAME).getAbsolutePath());
        runtime.setCacheDir(
                new File(workspaceHome, RuntimePathConstants.CACHE_DIR_NAME).getAbsolutePath());
        runtime.setStateDb(
                new File(
                                new File(workspaceHome, RuntimePathConstants.DATA_DIR_NAME),
                                RuntimePathConstants.STATE_DB_FILE_NAME)
                        .getAbsolutePath());
        runtime.setConfigFile(
                new File(workspaceHome, RuntimePathConstants.CONFIG_FILE_NAME).getAbsolutePath());
        runtime.setLogsDir(
                new File(workspaceHome, RuntimePathConstants.LOGS_DIR_NAME).getAbsolutePath());
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
        copyGoal(other.getGoal());
        copyCurator(other.getCurator());
        copySkills(other.getSkills());
        copyRollback(other.getRollback());
        copyDisplay(other.getDisplay());
        copyReact(other.getReact());
        copyTrace(other.getTrace());
        copyTask(other.getTask());
        copySpeech(other.getSpeech());
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
        this.gateway.setProcessingReactionsEnabled(
                other.getGateway().isProcessingReactionsEnabled());
        this.gateway.setMultiplexProfiles(other.getGateway().isMultiplexProfiles());
        this.gateway.setPlatforms(cloneGatewayPlatforms(other.getGateway().getPlatforms()));
        this.profiles.setMaxNamedProfiles(other.getProfiles().getMaxNamedProfiles());
        this.dashboard.setAccessToken(other.getDashboard().getAccessToken());
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
        this.llm.setContextFallbackTokens(other.getContextFallbackTokens());
        this.llm.setModelsDevRefreshEnabled(other.isModelsDevRefreshEnabled());
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
            copy.setCapabilities(
                    source.getCapabilities() == null
                            ? new LinkedHashMap<String, Boolean>()
                            : new LinkedHashMap<String, Boolean>(source.getCapabilities()));
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
     * 复制 goal 配置。
     *
     * @param other 源配置。
     */
    private void copyGoal(GoalConfig other) {
        this.goal.setMaxTurns(other.getMaxTurns());
        this.goal.setJudgeTimeoutSeconds(other.getJudgeTimeoutSeconds());
        this.goal.setJudgeMaxTokens(other.getJudgeMaxTokens());
        this.goal.setJudgeProvider(other.getJudgeProvider());
        this.goal.setJudgeModel(other.getJudgeModel());
        this.goal.setMaxConsecutiveParseFailures(other.getMaxConsecutiveParseFailures());
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
        this.task.setBootstrapPromptFileCharLimit(other.getBootstrapPromptFileCharLimit());
        this.task.setBootstrapPromptTotalCharBudget(other.getBootstrapPromptTotalCharBudget());
        this.task.setToolOutputMaxLines(other.getToolOutputMaxLines());
        this.task.setToolOutputMaxLineLength(other.getToolOutputMaxLineLength());
        this.task.setMediaCacheTtlHours(other.getMediaCacheTtlHours());
        this.task.setProfileTaskMaxConcurrency(other.getProfileTaskMaxConcurrency());
        this.task.setProfileTaskDefaultTimeoutMinutes(other.getProfileTaskDefaultTimeoutMinutes());
        this.task.setProfileTaskMaxTimeoutMinutes(other.getProfileTaskMaxTimeoutMinutes());
        this.task.setProfileTaskMaxAttempts(other.getProfileTaskMaxAttempts());
    }

    /**
     * 复制语音配置，保证运行时刷新后内置 TTS 与 STT Provider 立即使用新参数。
     *
     * @param other 待复制的语音配置。
     */
    private void copySpeech(SpeechConfig other) {
        this.speech.getTts().setEnabled(other.getTts().isEnabled());
        this.speech.getTts().setEndpoint(other.getTts().getEndpoint());
        this.speech.getTts().setApiKey(other.getTts().getApiKey());
        this.speech.getTts().setModel(other.getTts().getModel());
        this.speech.getTts().setVoice(other.getTts().getVoice());
        this.speech.getTts().setResponseFormat(other.getTts().getResponseFormat());
        this.speech.getTts().setSpeed(other.getTts().getSpeed());
        this.speech.getTts().setTimeoutSeconds(other.getTts().getTimeoutSeconds());
        this.speech.getStt().setEnabled(other.getStt().isEnabled());
        this.speech.getStt().setEndpoint(other.getStt().getEndpoint());
        this.speech.getStt().setApiKey(other.getStt().getApiKey());
        this.speech.getStt().setModel(other.getStt().getModel());
        this.speech.getStt().setLanguage(other.getStt().getLanguage());
        this.speech.getStt().setPrompt(other.getStt().getPrompt());
        this.speech.getStt().setTimeoutSeconds(other.getStt().getTimeoutSeconds());
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
        this.security.setHardlineAllowlist(
                other.getHardlineAllowlist() == null
                        ? new ArrayList<String>()
                        : new ArrayList<String>(other.getHardlineAllowlist()));
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
        this.approvals.setMcpReloadConfirm(other.isMcpReloadConfirm());
        this.approvals.setDeny(other.getDeny());
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
        this.proactive.setIntervalHours(other.getIntervalHours());
        this.proactive.setDeliveryTarget(other.getDeliveryTarget());
        this.proactive.setTopicCooldownHours(other.getTopicCooldownHours());
        this.proactive.setQuietHoursEnabled(other.isQuietHoursEnabled());
        this.proactive.setQuietStart(other.getQuietStart());
        this.proactive.setQuietEnd(other.getQuietEnd());
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
        target.setRequireMention(source.isRequireMention());
        target.setFreeResponseChats(new ArrayList<String>(source.getFreeResponseChats()));
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

    /** 深拷贝 gateway platforms 配置，确保热更新时不会共享可变集合。 */
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
     * 生成配置异常摘要；只返回异常类型，避免解析错误中携带的配置值或密钥进入日志。
     *
     * @param error 配置读取或转换时捕获的异常。
     * @return 可写入日志的异常类型摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
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

    /** 工作区派生目录配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RuntimeConfig {
        /** 工作区根目录。 */
        private String home;

        /** 上下文文件目录。 */
        private String contextDir;

        /** skills 本地目录。 */
        private String skillsDir;

        /** 缓存目录。 */
        private String cacheDir;

        /** SQLite 状态库路径。 */
        private String stateDb;

        /** workspace/config.yml 路径。 */
        private String configFile;

        /** workspace/logs 目录。 */
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

        /**
         * 模型上下文窗口大小，用于自动压缩阈值计算。
         *
         * <p>设为 0（默认）时按模型自动识别（在线目录 → 硬编码目录 → 兜底）； 设为大于 0 的值时作为全局强制覆盖，对齐外部对标仓库的显式配置覆盖语义。
         */
        private int contextWindowTokens;

        /**
         * 自动识别失败时的兜底上下文窗口大小。
         *
         * <p>对齐外部对标仓库的 DEFAULT_FALLBACK_CONTEXT，默认 256000。
         */
        private int contextFallbackTokens = 256000;

        /** 是否启用 models.dev/OpenRouter 在线目录刷新，用于自动识别模型上下文长度。 */
        private boolean modelsDevRefreshEnabled = true;

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

        /** 显式模型能力元数据；未配置的能力才允许回退到协议或模型家族推断。 */
        private Map<String, Boolean> capabilities = new LinkedHashMap<String, Boolean>();

        /**
         * 读取图片输入能力；优先兼容现有直接字段，再读取统一能力元数据。
         *
         * @return 返回显式图片输入能力，未配置时返回 null。
         */
        public Boolean getSupportsVision() {
            if (supportsVision != null) {
                return supportsVision;
            }
            return capabilities == null ? null : capabilities.get("vision");
        }

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

    /** goal 目标循环相关配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class GoalConfig {
        /** 默认轮次预算上限。 */
        private int maxTurns = 20;

        /** judge 辅助调用超时秒数。 */
        private int judgeTimeoutSeconds = 30;

        /** judge 最大 token 数。 */
        private int judgeMaxTokens = 4096;

        /** judge 使用的 provider，留空则用会话默认。 */
        private String judgeProvider = "";

        /** judge 使用的 model，留空则用会话默认。 */
        private String judgeModel = "";

        /** 连续 JSON 解析失败上限，超过自动暂停。 */
        private int maxConsecutiveParseFailures = 3;
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
        /** 是否启用主动提醒。 */
        private boolean enabled = true;

        /** 两次主动提醒检查之间的小时数。 */
        private double intervalHours = 4D;

        /** 提醒投递目标；当前只支持 main，表示最近的主对话。 */
        private String deliveryTarget = "main";

        /** 相同话题再次提醒前至少间隔的小时数。 */
        private double topicCooldownHours = 8D;

        /** 是否启用免打扰时段。 */
        private boolean quietHoursEnabled = true;

        /** 免打扰开始时间，格式为 HH:mm。 */
        private String quietStart = "22:00";

        /** 免打扰结束时间，格式为 HH:mm。 */
        private String quietEnd = "08:00";
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

        /** 单个静态 bootstrap 上下文块的最大字符数，避免单一文件挤占系统提示词。 */
        private int bootstrapPromptFileCharLimit = 12000;

        /** 静态 bootstrap 系统提示词的总字符预算，与工具输出预算独立。 */
        private int bootstrapPromptTotalCharBudget = 48000;

        /** 文件读取/分页输出的最大行数限制。 */
        private int toolOutputMaxLines = 2000;

        /** 文件读取/分页输出的单行截断长度。 */
        private int toolOutputMaxLineLength = 2000;

        /** 媒体缓存 TTL，单位小时。 */
        private int mediaCacheTtlHours = 168;

        /** 跨 Profile 协作任务全局并发；同一目标 Profile 固定为一。 */
        private int profileTaskMaxConcurrency = 5;

        /** 跨 Profile 协作任务默认单次超时分钟数。 */
        private int profileTaskDefaultTimeoutMinutes = 30;

        /** 跨 Profile 协作任务单次超时上限分钟数。 */
        private int profileTaskMaxTimeoutMinutes = 240;

        /** 跨 Profile 协作任务最大执行次数，包含首次执行。 */
        private int profileTaskMaxAttempts = 5;
    }

    /** 承载 TTS 与独立语音转写配置，两个 Provider 可分别启停和指向不同服务。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SpeechConfig {
        /** OpenAI 兼容文本转语音配置。 */
        private TtsConfig tts = new TtsConfig();

        /** OpenAI 兼容独立语音转写配置。 */
        private SttConfig stt = new SttConfig();
    }

    /** OpenAI 兼容 TTS HTTP Provider 配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TtsConfig {
        /** 是否启用内置 TTS Provider；默认关闭，避免未配置时虚报可用。 */
        private boolean enabled = false;

        /** 完整的 OpenAI 兼容 `/audio/speech` 请求地址。 */
        private String endpoint = "https://api.openai.com/v1/audio/speech";

        /** TTS 服务密钥；本地免鉴权兼容服务可留空。 */
        private String apiKey = "";

        /** 默认语音合成模型。 */
        private String model = "gpt-4o-mini-tts";

        /** 调用方未指定 voice 时使用的默认音色。 */
        private String voice = "alloy";

        /** 默认输出格式，可选 mp3、opus、aac、flac、wav、pcm。 */
        private String responseFormat = "mp3";

        /** 默认语速，OpenAI 兼容范围为 0.25 到 4.0。 */
        private double speed = 1.0d;

        /** 单次 TTS HTTP 请求超时，单位秒。 */
        private int timeoutSeconds = 120;
    }

    /** OpenAI 兼容独立 STT HTTP Provider 配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SttConfig {
        /** 是否启用内置独立 STT Provider；默认关闭，避免未配置时虚报可用。 */
        private boolean enabled = false;

        /** 完整的 OpenAI 兼容 `/audio/transcriptions` 请求地址。 */
        private String endpoint = "https://api.openai.com/v1/audio/transcriptions";

        /** STT 服务密钥；本地免鉴权兼容服务可留空。 */
        private String apiKey = "";

        /** 默认语音转写模型。 */
        private String model = "gpt-4o-mini-transcribe";

        /** 可选默认语言代码；留空时由服务自动识别。 */
        private String language = "";

        /** 可选转写提示词，用于补充专有名词或语言上下文。 */
        private String prompt = "";

        /** 单次 STT HTTP 请求超时，单位秒。 */
        private int timeoutSeconds = 120;
    }

    /** 承载终端配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TerminalConfig {
        /** 相对 workspace 的凭据文件挂载清单。 */
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

    /** 承载安全配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SecurityConfig {
        /** 是否允许 URL 工具访问内网/私有地址；云元数据地址始终阻断。 */
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

        /** Tirith 不可用或超时时是否放行；默认 fail-open，对齐外部对标仓库在扫描缺失时放行而非阻断的行为。 */
        private boolean tirithFailOpen = true;

        /** 文件路径安全预检模式：strict / bypass；默认 strict。 */
        private String fileGuardrailMode = "strict";

        /** URL 安全预检模式：strict / bypass；默认 strict。 */
        private String urlGuardrailMode = "strict";

        /** Agent 工具安全策略模式：approval / bypass / smart。默认 approval，危险行为必须审批。 */
        private String guardrailMode = "approval";

        /** Cron 工具安全策略模式：strict / approval / bypass / approve。默认 strict。 */
        private String guardrailCronMode = "strict";

        /** Cron 审批记忆范围：job / session / global。 */
        private String guardrailCronScope = "job";

        /** 允许跳过硬阻断的 hardline 类别；默认空列表，不放行任何类别。 */
        private List<String> hardlineAllowlist = new ArrayList<String>();

        /** 网站访问阻断策略。 */
        private WebsiteBlocklistConfig websiteBlocklist = new WebsiteBlocklistConfig();
    }

    /** 承载网站域名和共享规则文件配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class WebsiteBlocklistConfig {
        /** 是否启用域名阻断策略。 */
        private boolean enabled = false;

        /** 阻断域名列表，支持精确域名和通配子域名。 */
        private List<String> domains = new ArrayList<String>();

        /** 共享阻断列表文件，支持 workspace 相对路径或绝对路径。 */
        private List<String> sharedFiles = new ArrayList<String>();
    }

    /** 承载Web配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class WebConfig {
        /** Websearch 后端；当前使用 solon-ai 内置实现。 */
        private String searchBackend = "solon-ai";

        /** Brave Search API key；为空时也会尝试读取 BRAVE_SEARCH_API_KEY 环境变量。 */
        private String braveSearchApiKey;
    }

    /** 承载Approvals配置并集中创建运行组件。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ApprovalsConfig {
        /** 子 Agent 遇到可审批危险命令时是否自动批准一次；默认拒绝。 */
        private boolean subagentAutoApprove = false;

        /** 所有审批（包括消息渠道待审批）的统一超时秒数。 */
        private int timeoutSeconds = 180;

        /** /reload-mcp 是否需要确认，默认开启。 */
        private boolean mcpReloadConfirm = true;

        /**
         * 用户自定义不可绕过的命令 deny 列表，支持 fnmatch glob。
         *
         * <p>对齐外部对标仓库的 approvals.deny：即使 bypass/yolo 模式也不放过， 用于补充内置 hardline 规则。例如 {@code "git push
         * --force*"}、{@code "*rm -rf*"}。
         */
        private java.util.List<String> deny = new java.util.ArrayList<>();
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

        /** 群聊消息是否必须提及机器人，默认保持渠道现有安全行为。 */
        private boolean requireMention = true;

        /** 群聊免提及响应会话列表，命中后可不 @ 机器人直接响应。 */
        private List<String> freeResponseChats = new ArrayList<String>();

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

        /** 渠道级 metadata footer 开关，null 表示继承全局。 */
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

        /** 是否启用渠道处理状态表情回应，用于在原消息上标记处理中和完成状态。 */
        private boolean processingReactionsEnabled = true;

        /** 是否由默认 Profile 的单个网关进程承载全部 Profile，使各 Profile 可同时运行独立机器人账号。 */
        private boolean multiplexProfiles = true;

        /** 各平台工具集权限配置，键为平台名称（大写），值为该平台的工具集策略。 */
        private Map<String, PlatformConfig> platforms = new LinkedHashMap<String, PlatformConfig>();
    }

    /** 多 Profile 管理配置。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ProfilesConfig {
        /** 命名 Profile 数量上限，不包含 default。 */
        private int maxNamedProfiles = 10;
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
