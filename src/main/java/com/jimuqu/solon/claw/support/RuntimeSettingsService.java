package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 运行时设置读取与修改服务。 */
public class RuntimeSettingsService {
    /** 记录运行时设置摘要降级路径的低敏诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsService.class);

    /** 配置键WHITE列表的统一常量值。 */
    private static final List<String> CONFIG_KEY_WHITELIST =
            Arrays.asList(
                    "llm.stream",
                    "llm.reasoningEffort",
                    "llm.temperature",
                    "llm.maxTokens",
                    "llm.contextWindowTokens",
                    "llm.contextFallbackTokens",
                    "llm.modelsDevRefreshEnabled",
                    "display.toolProgress",
                    "display.showReasoning",
                    "display.toolPreviewLength",
                    "display.progressThrottleMs",
                    "display.metadataFooter.enabled",
                    "display.metadataFooter.fields",
                    "display.platforms.feishu.metadataFooter.enabled",
                    "display.platforms.dingtalk.metadataFooter.enabled",
                    "display.platforms.wecom.metadataFooter.enabled",
                    "display.platforms.weixin.metadataFooter.enabled",
                    "display.platforms.qqbot.metadataFooter.enabled",
                    "display.platforms.yuanbao.metadataFooter.enabled",
                    "scheduler.enabled",
                    "scheduler.tickSeconds",
                    "scheduler.wrapResponse",
                    "scheduler.enabledToolsets",
                    "compression.enabled",
                    "compression.thresholdPercent",
                    "compression.protectHeadMessages",
                    "compression.tailRatio",
                    "learning.enabled",
                    "learning.toolCallThreshold",
                    "learning.auxiliaryTimeoutSeconds",
                    "memory.archive.enabled",
                    "memory.archive.retentionDays",
                    "memory.archive.intervalHours",
                    "memory.archive.maxFilesPerRun",
                    "memory.archive.aiSummaryEnabled",
                    "memory.archive.auxiliaryTimeoutSeconds",
                    "skills.curator.enabled",
                    "skills.curator.intervalHours",
                    "skills.curator.minIdleHours",
                    "skills.curator.staleAfterDays",
                    "skills.curator.archiveAfterDays",
                    "reflection.enabled",
                    "reflection.intervalHours",
                    "reflection.lookbackDays",
                    "task.busyPolicy",
                    "task.toolOutputInlineLimit",
                    "task.toolOutputTurnBudget",
                    "task.bootstrapPromptFileCharLimit",
                    "task.bootstrapPromptTotalCharBudget",
                    "task.toolOutputMaxLines",
                    "task.toolOutputMaxLineLength",
                    "solonclaw.task.toolOutputInlineLimit",
                    "solonclaw.task.toolOutputTurnBudget",
                    "solonclaw.task.bootstrapPromptFileCharLimit",
                    "solonclaw.task.bootstrapPromptTotalCharBudget",
                    "solonclaw.task.toolOutputMaxLines",
                    "solonclaw.task.toolOutputMaxLineLength",
                    "agent.heartbeat.intervalMinutes",
                    "rollback.enabled",
                    "rollback.maxCheckpointsPerSource",
                    "rollback.maxFileSizeMb",
                    "rollback.excludePatterns",
                    "react.maxSteps",
                    "react.retryMax",
                    "react.retryDelayMs",
                    "react.delegateMaxSteps",
                    "react.delegateRetryMax",
                    "react.delegateRetryDelayMs",
                    "react.summarizationEnabled",
                    "react.summarizationMaxMessages",
                    "react.summarizationMaxTokens",
                    "react.toolLoopWarningsEnabled",
                    "react.toolLoopHardStopEnabled",
                    "react.toolLoopExactFailureWarnAfter",
                    "react.toolLoopExactFailureBlockAfter",
                    "react.toolLoopSameToolFailureWarnAfter",
                    "react.toolLoopSameToolFailureHaltAfter",
                    "react.toolLoopNoProgressWarnAfter",
                    "react.toolLoopNoProgressBlockAfter",
                    "gateway.allowedUsers",
                    "gateway.allowAllUsers",
                    "gateway.injectionSecret",
                    "gateway.injectionMaxBodyBytes",
                    "gateway.injectionReplayWindowSeconds",
                    "security.allowPrivateUrls",
                    "solonclaw.browser.rewriteLoopbackUrls",
                    "solonclaw.browser.loopbackHostAlias",
                    "security.tirithEnabled",
                    "security.tirithPath",
                    "security.tirithTimeoutSeconds",
                    "security.tirithFailOpen",
                    "security.fileGuardrailMode",
                    "security.urlGuardrailMode",
                    "security.websiteBlocklist.enabled",
                    "security.websiteBlocklist.domains",
                    "security.websiteBlocklist.sharedFiles",
                    "security.hardlineAllowlist",
                    "security.guardrailMode",
                    "security.guardrailCronMode",
                    "security.guardrailCronScope",
                    "approvals.subagentAutoApprove",
                    "approvals.timeoutSeconds",
                    "approvals.mcpReloadConfirm",
                    "approvals.deny",
                    "solonclaw.terminal.credentialFiles",
                    "solonclaw.terminal.envPassthrough",
                    "solonclaw.terminal.sudoPassword",
                    "solonclaw.terminal.maxForegroundTimeoutSeconds",
                    "solonclaw.terminal.foregroundMaxRetries",
                    "solonclaw.terminal.foregroundRetryBaseDelaySeconds",
                    "solonclaw.terminal.processWaitTimeoutSeconds");

    /** 渠道键SUFFIXWHITE列表的统一常量值。 */
    private static final List<String> CHANNEL_KEY_SUFFIX_WHITELIST =
            Arrays.asList(
                    ".enabled",
                    ".allowedUsers",
                    ".allowAllUsers",
                    ".unauthorizedDmBehavior",
                    ".dmPolicy",
                    ".groupPolicy",
                    ".groupAllowedUsers",
                    ".allowedChats",
                    ".requireMention",
                    ".freeResponseChats",
                    ".mentionPatterns",
                    ".websocketUrl",
                    ".streamUrl",
                    ".coolAppCode",
                    ".baseUrl",
                    ".cdnBaseUrl",
                    ".longPollUrl",
                    ".splitMultilineMessages",
                    ".textBatchDelaySeconds",
                    ".textBatchSplitDelaySeconds",
                    ".sendChunkDelaySeconds",
                    ".sendChunkRetries",
                    ".sendChunkRetryDelaySeconds",
                    ".toolProgress",
                    ".progressCardTemplateId",
                    ".approvalCardTemplateId",
                    ".metadataFooter.enabled",
                    ".comment.enabled",
                    ".comment.pairingFile",
                    ".aiCardStreaming.enabled",
                    ".apiDomain",
                    ".markdownSupport",
                    ".appId",
                    ".appSecret",
                    ".clientId",
                    ".clientSecret",
                    ".robotCode",
                    ".botId",
                    ".secret",
                    ".token",
                    ".accountId",
                    ".botOpenId",
                    ".botUserId",
                    ".botName");

    /** 注入应用配置，用于运行时设置。 */
    private final AppConfig appConfig;

    /** 保存global设置仓储集合，维持调用顺序或去重语义。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 注入控制台配置服务，用于调用对应业务能力。 */
    private final DashboardConfigService dashboardConfigService;

    /** 注入控制台工作区配置服务，用于调用对应业务能力。 */
    private final DashboardRuntimeConfigService dashboardRuntimeConfigService;

    /** 注入应用版本服务，用于调用对应业务能力。 */
    private final AppVersionService appVersionService;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 注入控制台提供方服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.web.DashboardProviderService dashboardProviderService;

    /**
     * 创建运行时设置服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param dashboardConfigService dashboard配置Service配置对象。
     * @param dashboardRuntimeConfigService dashboard工作区配置Service配置对象。
     * @param appVersionService 应用版本服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param dashboardProviderService dashboard提供方Service标识或键值。
     */
    public RuntimeSettingsService(
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            DeliveryService deliveryService,
            DashboardConfigService dashboardConfigService,
            DashboardRuntimeConfigService dashboardRuntimeConfigService,
            AppVersionService appVersionService,
            LlmProviderService llmProviderService,
            com.jimuqu.solon.claw.web.DashboardProviderService dashboardProviderService) {
        this.appConfig = appConfig;
        this.globalSettingRepository = globalSettingRepository;
        this.deliveryService = deliveryService;
        this.dashboardConfigService = dashboardConfigService;
        this.dashboardRuntimeConfigService = dashboardRuntimeConfigService;
        this.appVersionService = appVersionService;
        this.llmProviderService = llmProviderService;
        this.dashboardProviderService = dashboardProviderService;
    }

    /**
     * 解析生效模型。
     *
     * @param session 会话参数。
     * @return 返回解析后的生效模型。
     */
    public ResolvedModel resolveEffectiveModel(SessionRecord session) {
        String override =
                session == null ? "" : StrUtil.nullToEmpty(session.getModelOverride()).trim();
        LlmProviderService.ResolvedProvider resolved =
                llmProviderService.resolveEffectiveProvider(session);
        return new ResolvedModel(
                resolved.getProviderKey(),
                resolved.getDialect(),
                resolved.getModel(),
                override.length() > 0);
    }

    /**
     * 构建Agent运行时提示词。
     *
     * @param sourceKey 渠道来源键。
     * @param session 会话参数。
     * @param enabledToolNames 启用状态工具Names开关值。
     * @return 返回创建好的Agent运行时提示词。
     */
    public String buildAgentRuntimePrompt(
            String sourceKey, SessionRecord session, List<String> enabledToolNames) {
        return buildAgentRuntimePrompt(sourceKey, session, enabledToolNames, null);
    }

    /**
     * 构建Agent运行时提示词。
     *
     * @param sourceKey 渠道来源键。
     * @param session 会话参数。
     * @param enabledToolNames 启用状态工具Names开关值。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回创建好的Agent运行时提示词。
     */
    public String buildAgentRuntimePrompt(
            String sourceKey,
            SessionRecord session,
            List<String> enabledToolNames,
            AgentRuntimeScope agentScope) {
        String[] parts = SourceKeySupport.split(sourceKey);
        ResolvedModel resolved = resolveEffectiveModel(session);
        List<String> channelStates = new ArrayList<String>();
        try {
            for (ChannelStatus status : deliveryService.statuses()) {
                if (status.getPlatform() == null) {
                    continue;
                }
                channelStates.add(
                        status.getPlatform().name().toLowerCase()
                                + "(enabled="
                                + status.isEnabled()
                                + ",connected="
                                + status.isConnected()
                                + ")");
            }
        } catch (Exception e) {
            log.debug("运行时渠道状态读取失败，按空状态列表兜底 error={}", exceptionSummary(e));
        }

        StringBuilder buffer = new StringBuilder();
        LlmProviderService.ResolvedProvider globalResolved =
                llmProviderService.resolveEffectiveProvider(null);
        String agentWorkspace =
                agentScope == null
                        ? StrUtil.nullToEmpty(appConfig.getWorkspace().getDir())
                        : StrUtil.nullToEmpty(agentScope.getWorkspaceDir());
        buffer.append("[Agent Runtime]\n");
        buffer.append("source_key=").append(StrUtil.nullToEmpty(sourceKey)).append('\n');
        buffer.append("platform=").append(StrUtil.nullToEmpty(parts[0])).append('\n');
        buffer.append("chat_id=").append(StrUtil.nullToEmpty(parts[1])).append('\n');
        buffer.append("user_id=").append(StrUtil.nullToEmpty(parts[2])).append('\n');
        buffer.append("session_id=")
                .append(session == null ? "" : StrUtil.nullToEmpty(session.getSessionId()))
                .append('\n');
        buffer.append("branch=")
                .append(session == null ? "" : StrUtil.nullToEmpty(session.getBranchName()))
                .append('\n');
        buffer.append("default_provider=")
                .append(StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()))
                .append('\n');
        buffer.append("default_model=")
                .append(StrUtil.nullToEmpty(globalResolved.getModel()))
                .append('\n');
        buffer.append("effective_provider=")
                .append(StrUtil.nullToEmpty(resolved.provider))
                .append('\n');
        buffer.append("effective_dialect=")
                .append(StrUtil.nullToEmpty(resolved.dialect))
                .append('\n');
        buffer.append("effective_model=").append(StrUtil.nullToEmpty(resolved.model)).append('\n');
        buffer.append("session_input_tokens=")
                .append(session == null ? 0 : session.getCumulativeInputTokens())
                .append('\n');
        buffer.append("session_output_tokens=")
                .append(session == null ? 0 : session.getCumulativeOutputTokens())
                .append('\n');
        buffer.append("session_total_tokens=")
                .append(session == null ? 0 : session.getCumulativeTotalTokens())
                .append('\n');
        buffer.append("react_summarization_enabled=")
                .append(appConfig.getReact().isSummarizationEnabled())
                .append('\n');
        buffer.append("react_summarization_max_messages=")
                .append(appConfig.getReact().getSummarizationMaxMessages())
                .append('\n');
        buffer.append("react_summarization_max_tokens=")
                .append(appConfig.getReact().getSummarizationMaxTokens())
                .append('\n');
        buffer.append("app_version=")
                .append(StrUtil.nullToEmpty(appVersionService.currentTag()))
                .append('\n');
        buffer.append("deployment_mode=")
                .append(StrUtil.nullToEmpty(appVersionService.deploymentMode()))
                .append('\n');
        buffer.append("has_session_model_override=").append(resolved.sessionOverride).append('\n');
        buffer.append("enabled_tools=").append(join(enabledToolNames)).append('\n');
        buffer.append("channels=").append(join(channelStates)).append('\n');
        buffer.append("\n[Workspace]\n");
        buffer.append("Working directory: ").append(agentWorkspace).append('\n');
        buffer.append("Single global file workspace unless explicitly told otherwise.\n");
        buffer.append(
                "File reads are allowed subject to security guardrails. File writes inside the workspace are free; writes outside it require approval. Command and network operations follow their own security and approval policies regardless of working directory. Secrets are always redacted.\n");
        appendShellGuidance(buffer, enabledToolNames);
        buffer.append(
                "Only change your own configuration through /model, config_set, or config_set_secret. Secret keys must use config_set_secret and must never be copied from redacted read_file output. If you edit workspace/config.yml directly for non-secret keys, call config_refresh afterward; it validates YAML first and refuses invalid config. Global changes take effect on the next message.");
        return buffer.toString();
    }

    /**
     * 执行describe模型相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回describe模型结果。
     */
    public String describeModel(SessionRecord session) {
        ResolvedModel resolved = resolveEffectiveModel(session);
        StringBuilder buffer = new StringBuilder();
        buffer.append("current.provider=")
                .append(StrUtil.nullToDefault(resolved.provider, "default"))
                .append('\n');
        buffer.append("current.dialect=")
                .append(StrUtil.nullToDefault(resolved.dialect, ""))
                .append('\n');
        buffer.append("current.model=")
                .append(StrUtil.nullToDefault(resolved.model, "default"))
                .append('\n');
        buffer.append("current.apiUrl=")
                .append(
                        StrUtil.nullToDefault(
                                llmProviderService.resolveEffectiveProvider(session).getApiUrl(),
                                ""))
                .append('\n');
        buffer.append("session.override=")
                .append(
                        session == null
                                ? ""
                                : StrUtil.nullToDefault(session.getModelOverride(), ""))
                .append('\n');
        buffer.append("global.provider=")
                .append(StrUtil.nullToDefault(appConfig.getModel().getProviderKey(), ""))
                .append('\n');
        buffer.append("global.model=")
                .append(
                        StrUtil.nullToDefault(
                                llmProviderService.resolveEffectiveProvider(null).getModel(), ""))
                .append('\n');
        return buffer.toString().trim();
    }

    /**
     * 写入Global模型。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     */
    public void setGlobalModel(String provider, String model) {
        dashboardProviderService.updateDefaultModel(provider, model);
    }

    /**
     * 校验会话级模型并生成带 Provider 的稳定覆盖值。
     *
     * @param provider 可选 Provider；为空时使用当前全局 Provider。
     * @param model 待选择的模型。
     * @return provider:model 形式的会话覆盖值。
     */
    public String normalizeSessionModelOverride(String provider, String model) {
        String providerKey = StrUtil.nullToEmpty(provider).trim();
        if (StrUtil.isBlank(providerKey)) {
            providerKey = llmProviderService.resolveEffectiveProvider(null).getProviderKey();
        }
        LlmProviderService.ResolvedProvider resolved =
                llmProviderService.resolveProvider(providerKey, model);
        return resolved.getProviderKey() + ":" + resolved.getModel();
    }

    /**
     * 读取配置Value。
     *
     * @param key 配置键或映射键。
     * @return 返回读取到的配置Value。
     */
    public Object getConfigValue(String key) {
        rejectModelOrProviderConfigKey(key);
        ensureConfigKeyAllowed(key);
        Object raw = RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome()).getRaw(key);
        if (raw != null) {
            return raw;
        }
        return readNested(dashboardConfigService.getConfig(), key);
    }

    /**
     * 写入配置Value。
     *
     * @param key 配置键或映射键。
     * @param rawValue 原始值参数。
     */
    public void setConfigValue(String key, String rawValue) {
        rejectModelOrProviderConfigKey(key);
        ensureConfigKeyAllowed(key);
        if (isSecretConfigKey(key)) {
            throw new IllegalArgumentException(key + " 是密钥配置，请使用 config_set_secret 更新。");
        }
        persistConfigValue(
                key, parseValueForKey(key, rawValue), shouldReconnectChannelsForConfigKey(key));
    }

    /** 拒绝模型或 Provider 配置通过通用配置工具读写。 */
    private void rejectModelOrProviderConfigKey(String key) {
        ModelConfigKeySupport.requireGeneralConfigKey(key);
    }

    /**
     * 写入密钥Value。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    public void setSecretValue(String key, String value) {
        rejectModelOrProviderConfigKey(key);
        dashboardRuntimeConfigService.updateSecret(
                key, value, shouldReconnectChannelsForRuntimeKey(key));
    }

    /**
     * 确保配置键Allowed。
     *
     * @param key 配置键或映射键。
     */
    private void ensureConfigKeyAllowed(String key) {
        if (CONFIG_KEY_WHITELIST.contains(key)) {
            return;
        }
        rejectModelOrProviderConfigKey(key);
        if (key != null
                && (key.startsWith("channels.feishu.")
                        || key.startsWith("channels.dingtalk.")
                        || key.startsWith("channels.wecom.")
                        || key.startsWith("channels.weixin.")
                        || key.startsWith("channels.qqbot.")
                        || key.startsWith("channels.yuanbao."))) {
            for (String suffix : CHANNEL_KEY_SUFFIX_WHITELIST) {
                if (key.endsWith(suffix)) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported config key: " + key);
    }

    /**
     * 解析Value For键。
     *
     * @param key 配置键或映射键。
     * @param rawValue 原始值参数。
     * @return 返回解析后的Value For键。
     */
    private Object parseValueForKey(String key, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (key.endsWith(".enabled")
                || key.endsWith(".allowAllUsers")
                || key.endsWith(".requireMention")
                || key.endsWith(".splitMultilineMessages")
                || key.endsWith(".comment.enabled")
                || key.endsWith(".aiCardStreaming.enabled")
                || key.endsWith(".markdownSupport")
                || key.endsWith(".metadataFooter.enabled")
                || "llm.stream".equals(key)
                || "display.showReasoning".equals(key)
                || "display.metadataFooter.enabled".equals(key)
                || "scheduler.enabled".equals(key)
                || "scheduler.wrapResponse".equals(key)
                || "compression.enabled".equals(key)
                || "learning.enabled".equals(key)
                || "memory.archive.enabled".equals(key)
                || "memory.archive.aiSummaryEnabled".equals(key)
                || "rollback.enabled".equals(key)
                || "skills.curator.enabled".equals(key)
                || "reflection.enabled".equals(key)
                || "gateway.allowAllUsers".equals(key)
                || "security.allowPrivateUrls".equals(key)
                || "solonclaw.browser.rewriteLoopbackUrls".equals(key)
                || "security.tirithEnabled".equals(key)
                || "security.tirithFailOpen".equals(key)
                || "security.websiteBlocklist.enabled".equals(key)
                || "approvals.subagentAutoApprove".equals(key)
                || "approvals.mcpReloadConfirm".equals(key)) {
            return "true".equalsIgnoreCase(value)
                    || "1".equals(value)
                    || "yes".equalsIgnoreCase(value);
        }
        if (key.endsWith("sendChunkRetries")
                || "scheduler.tickSeconds".equals(key)
                || "learning.toolCallThreshold".equals(key)
                || "learning.auxiliaryTimeoutSeconds".equals(key)
                || "memory.archive.retentionDays".equals(key)
                || "memory.archive.intervalHours".equals(key)
                || "memory.archive.maxFilesPerRun".equals(key)
                || "memory.archive.auxiliaryTimeoutSeconds".equals(key)
                || "agent.heartbeat.intervalMinutes".equals(key)
                || "rollback.maxCheckpointsPerSource".equals(key)
                || "rollback.maxFileSizeMb".equals(key)
                || "react.maxSteps".equals(key)
                || "react.retryMax".equals(key)
                || "react.retryDelayMs".equals(key)
                || "react.delegateMaxSteps".equals(key)
                || "react.delegateRetryMax".equals(key)
                || "react.delegateRetryDelayMs".equals(key)
                || "react.summarizationMaxMessages".equals(key)
                || "react.summarizationMaxTokens".equals(key)
                || "react.toolLoopExactFailureWarnAfter".equals(key)
                || "react.toolLoopExactFailureBlockAfter".equals(key)
                || "react.toolLoopSameToolFailureWarnAfter".equals(key)
                || "react.toolLoopSameToolFailureHaltAfter".equals(key)
                || "react.toolLoopNoProgressWarnAfter".equals(key)
                || "react.toolLoopNoProgressBlockAfter".equals(key)
                || "compression.protectHeadMessages".equals(key)
                || "skills.curator.intervalHours".equals(key)
                || "skills.curator.staleAfterDays".equals(key)
                || "skills.curator.archiveAfterDays".equals(key)
                || "reflection.intervalHours".equals(key)
                || "reflection.lookbackDays".equals(key)
                || "display.toolPreviewLength".equals(key)
                || "display.progressThrottleMs".equals(key)
                || "llm.maxTokens".equals(key)
                || "llm.contextWindowTokens".equals(key)
                || "llm.contextFallbackTokens".equals(key)
                || "gateway.injectionMaxBodyBytes".equals(key)
                || "gateway.injectionReplayWindowSeconds".equals(key)
                || "security.tirithTimeoutSeconds".equals(key)
                || "approvals.timeoutSeconds".equals(key)
                || "solonclaw.terminal.maxForegroundTimeoutSeconds".equals(key)
                || "solonclaw.terminal.foregroundMaxRetries".equals(key)
                || "solonclaw.terminal.foregroundRetryBaseDelaySeconds".equals(key)
                || "solonclaw.terminal.processWaitTimeoutSeconds".equals(key)
                || "solonclaw.task.toolOutputInlineLimit".equals(key)
                || "solonclaw.task.toolOutputTurnBudget".equals(key)
                || "solonclaw.task.bootstrapPromptFileCharLimit".equals(key)
                || "solonclaw.task.bootstrapPromptTotalCharBudget".equals(key)
                || "solonclaw.task.toolOutputMaxLines".equals(key)
                || "solonclaw.task.toolOutputMaxLineLength".equals(key)) {
            return Integer.valueOf(value);
        }
        if ("react.summarizationEnabled".equals(key)
                || "react.toolLoopWarningsEnabled".equals(key)
                || "react.toolLoopHardStopEnabled".equals(key)) {
            return "true".equalsIgnoreCase(value)
                    || "1".equals(value)
                    || "yes".equalsIgnoreCase(value);
        }
        if (key.endsWith("textBatchDelaySeconds")
                || key.endsWith("textBatchSplitDelaySeconds")
                || key.endsWith("sendChunkDelaySeconds")
                || key.endsWith("sendChunkRetryDelaySeconds")
                || "llm.temperature".equals(key)
                || "compression.thresholdPercent".equals(key)
                || "compression.tailRatio".equals(key)
                || "skills.curator.minIdleHours".equals(key)) {
            return Double.valueOf(value);
        }
        if (key.endsWith("allowedUsers")
                || key.endsWith("groupAllowedUsers")
                || key.endsWith("allowedChats")
                || key.endsWith("freeResponseChats")
                || key.endsWith("mentionPatterns")
                || "display.metadataFooter.fields".equals(key)
                || "gateway.allowedUsers".equals(key)
                || "security.websiteBlocklist.domains".equals(key)
                || "security.websiteBlocklist.sharedFiles".equals(key)
                || "security.hardlineAllowlist".equals(key)
                || "solonclaw.terminal.credentialFiles".equals(key)
                || "solonclaw.terminal.envPassthrough".equals(key)
                || "rollback.excludePatterns".equals(key)
                || "scheduler.enabledToolsets".equals(key)) {
            List<String> values = new ArrayList<String>();
            if (value.length() == 0) {
                return values;
            }
            for (String item : value.split(",")) {
                if (StrUtil.isNotBlank(item)) {
                    values.add(item.trim());
                }
            }
            return values;
        }
        return value;
    }

    /**
     * 读取Nested。
     *
     * @param root root 参数。
     * @param key 配置键或映射键。
     * @return 返回读取到的Nested。
     */
    @SuppressWarnings("unchecked")
    private Object readNested(Map<String, Object> root, String key) {
        String[] parts = key.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    /**
     * 执行join相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回join结果。
     */
    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(value.trim());
        }
        return buffer.toString();
    }

    /**
     * 追加Shell Guidance。
     *
     * @param buffer buffer 参数。
     * @param enabledToolNames 启用状态工具Names开关值。
     */
    private void appendShellGuidance(StringBuilder buffer, List<String> enabledToolNames) {
        if (enabledToolNames == null
                || !enabledToolNames.contains(ToolNameConstants.EXECUTE_SHELL)) {
            return;
        }

        buffer.append("shell_probe_policy=Use execute_shell for environment detection.\n");
        buffer.append(
                "shell_probe_example=Use execute_shell with commands like: command -v git >/dev/null 2>&1 && git --version || echo git_missing\n");
        if (enabledToolNames.contains(ToolNameConstants.PROCESS)) {
            buffer.append(
                    "background_process_policy=Use terminal(command=..., background=true, notify_on_complete=true) or process(action=start, command=...) for long-running commands; manage them with process(action=poll|log|wait|kill). Avoid '&', nohup, disown, or foreground watch/server processes.\n");
        }
    }

    /**
     * 执行persist配置值相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param reconnectChannels reconnectChannels 参数。
     */
    private void persistConfigValue(String key, Object value, boolean reconnectChannels) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put(canonicalDashboardConfigKey(key), value);
        dashboardConfigService.savePartialFlat(updates, reconnectChannels);
    }

    /**
     * 执行规范控制台配置键相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回规范控制台配置键结果。
     */
    private String canonicalDashboardConfigKey(String key) {
        return key;
    }

    /**
     * 判断是否需要Reconnect Channels For配置键。
     *
     * @param key 配置键或映射键。
     * @return 如果Reconnect Channels For配置键满足条件则返回 true，否则返回 false。
     */
    private boolean shouldReconnectChannelsForConfigKey(String key) {
        return key != null && (key.startsWith("channels.") || key.startsWith("gateway.injection"));
    }

    /**
     * 判断是否需要Reconnect Channels For运行时键。
     *
     * @param key 配置键或映射键。
     * @return 如果Reconnect Channels For运行时键满足条件则返回 true，否则返回 false。
     */
    private boolean shouldReconnectChannelsForRuntimeKey(String key) {
        return key != null && key.startsWith("solonclaw.channels.");
    }

    /**
     * 判断是否密钥配置键。
     *
     * @param key 配置键或映射键。
     * @return 如果密钥配置键满足条件则返回 true，否则返回 false。
     */
    public boolean isSecretConfigKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim();
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if ("providers.default.apikey".equals(lower)
                || "gateway.injectionsecret".equals(lower)
                || "solonclaw.gateway.injectionsecret".equals(lower)
                || "solonclaw.terminal.sudopassword".equals(lower)
                || "solonclaw.dashboard.accesstoken".equals(lower)) {
            return true;
        }
        return lower.endsWith(".apikey")
                || lower.endsWith(".api_key")
                || lower.endsWith(".appsecret")
                || lower.endsWith(".app_secret")
                || lower.endsWith(".clientsecret")
                || lower.endsWith(".client_secret")
                || lower.endsWith(".access_token")
                || lower.endsWith(".accesstoken")
                || lower.endsWith(".secret")
                || lower.endsWith(".token");
    }

    /** 承载Resolved模型相关状态和辅助逻辑。 */
    public static class ResolvedModel {
        /** 记录Resolved模型中的提供方。 */
        private final String provider;

        /** 记录Resolved模型中的协议方言。 */
        private final String dialect;

        /** 记录Resolved模型中的模型。 */
        private final String model;

        /** 是否启用会话Override。 */
        private final boolean sessionOverride;

        /**
         * 创建Resolved模型实例，并注入运行所需依赖。
         *
         * @param provider 模型或能力提供方。
         * @param dialect dialect 参数。
         * @param model 模型名称。
         * @param sessionOverride 会话Override标识或键值。
         */
        public ResolvedModel(
                String provider, String dialect, String model, boolean sessionOverride) {
            this.provider = provider;
            this.dialect = dialect;
            this.model = model;
            this.sessionOverride = sessionOverride;
        }

        /**
         * 读取提供方。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取模型。
         *
         * @return 返回读取到的模型。
         */
        public String getModel() {
            return model;
        }

        /**
         * 读取协议方言。
         *
         * @return 返回读取到的协议方言。
         */
        public String getDialect() {
            return dialect;
        }

        /**
         * 判断是否会话Override。
         *
         * @return 如果会话Override满足条件则返回 true，否则返回 false。
         */
        public boolean isSessionOverride() {
            return sessionOverride;
        }
    }

    /**
     * 生成异常类型摘要，避免日志携带配置值、会话内容或异常消息。
     *
     * @param error 异常对象。
     * @return 仅包含异常类型的安全摘要。
     */
    private static String exceptionSummary(Exception error) {
        if (error == null) {
            return "unknown";
        }
        return error.getClass().getSimpleName();
    }
}
