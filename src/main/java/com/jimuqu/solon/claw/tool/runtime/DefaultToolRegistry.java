package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.plugin.ToolRegistration;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.web.DashboardAnalyticsService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardPlatformToolsetsService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.talents.sys.ShellTalent;
import org.noear.solon.ai.talents.sys.SystemClockTalent;
import org.noear.solon.ai.talents.gateway.ToolGatewayTalent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认工具注册表。 */
public class DefaultToolRegistry implements ToolRegistry {
    /** 记录工具偏好写入失败的低敏诊断日志，不输出来源正文或配置内容。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultToolRegistry.class);

    /** 默认内置工具清单。 */
    private static final List<String> TOOL_NAMES =
            Arrays.asList(
                    ToolNameConstants.FILE_READ,
                    ToolNameConstants.FILE_WRITE,
                    ToolNameConstants.READ_FILE,
                    ToolNameConstants.WRITE_FILE,
                    ToolNameConstants.SEARCH_FILES,
                    ToolNameConstants.FILE_LIST,
                    ToolNameConstants.FILE_DELETE,
                    ToolNameConstants.PATCH,
                    ToolNameConstants.EXECUTE_SHELL,
                    ToolNameConstants.TERMINAL,
                    ToolNameConstants.PROCESS,
                    ToolNameConstants.EXECUTE_CODE,
                    ToolNameConstants.EXECUTE_PYTHON,
                    ToolNameConstants.EXECUTE_JS,
                    ToolNameConstants.GET_CURRENT_TIME,
                    ToolNameConstants.TODO,
                    ToolNameConstants.AGENT_MANAGE,
                    ToolNameConstants.RUN_MANAGE,
                    ToolNameConstants.DELEGATE_TASK,
                    ToolNameConstants.MEMORY,
                    ToolNameConstants.SESSION_SEARCH,
                    ToolNameConstants.SESSION_MANAGE,
                    ToolNameConstants.ANALYTICS_MANAGE,
                    ToolNameConstants.SKILLS_LIST,
                    ToolNameConstants.SKILL_VIEW,
                    ToolNameConstants.SKILL_MANAGE,
                    ToolNameConstants.SKILLS_HUB_SEARCH,
                    ToolNameConstants.SKILLS_HUB_INSPECT,
                    ToolNameConstants.SKILLS_HUB_INSTALL,
                    ToolNameConstants.SKILLS_HUB_LIST,
                    ToolNameConstants.SKILLS_HUB_CHECK,
                    ToolNameConstants.SKILLS_HUB_UPDATE,
                    ToolNameConstants.SKILLS_HUB_AUDIT,
                    ToolNameConstants.SKILLS_HUB_UNINSTALL,
                    ToolNameConstants.SKILLS_HUB_TAP,
                    ToolNameConstants.SEND_MESSAGE,
                    ToolNameConstants.CRONJOB,
                    ToolNameConstants.CONFIG_GET,
                    ToolNameConstants.CONFIG_SET,
                    ToolNameConstants.CONFIG_SET_SECRET,
                    ToolNameConstants.CONFIG_REFRESH,
                    ToolNameConstants.TOOL_GATEWAY,
                    ToolNameConstants.MCP,
                    ToolNameConstants.MCP_MANAGE,
                    ToolNameConstants.CURATOR_MANAGE,
                    ToolNameConstants.PLATFORM_TOOLSETS_MANAGE,
                    ToolNameConstants.PROVIDER_MANAGE,
                    ToolNameConstants.CODESEARCH,
                    ToolNameConstants.WEBSEARCH,
                    ToolNameConstants.WEBFETCH,
                    ToolNameConstants.IMAGE_GENERATE,
                    ToolNameConstants.TEXT_TO_SPEECH,
                    ToolNameConstants.SPEECH_TRANSCRIBE,
                    ToolNameConstants.BROWSER,
                    ToolNameConstants.SECURITY_AUDIT,
                    ToolNameConstants.CLARIFY);

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 偏好存储。 */
    private final SqlitePreferenceStore preferenceStore;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** Agent profile 服务。 */
    private final AgentProfileService agentProfileService;

    /** 定时任务仓储。 */
    private final CronJobService cronJobService;

    /** 渠道投递服务。 */
    private final DeliveryService deliveryService;

    /** 长期记忆服务。 */
    private final MemoryService memoryService;

    /** 会话搜索服务。 */
    private final SessionSearchService sessionSearchService;

    /** 本地技能目录服务。 */
    private final LocalSkillService localSkillService;

    /** Skills Hub 服务。 */
    private final SkillHubService skillHubService;

    /** checkpoint 服务。 */
    private final CheckpointService checkpointService;

    /** 委托服务。 */
    private final DelegationService delegationService;

    /** 附件缓存服务。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 工作区配置服务。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 工作区配置刷新服务。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 文件/URL 安全策略。 */
    private final SecurityPolicyService securityPolicyService;

    /** 危险或外部操作审批服务。 */
    private final DangerousCommandApprovalService approvalService;

    /** MCP 运行时工具发现服务。 */
    private final McpRuntimeService mcpRuntimeService;

    /** Dashboard MCP 服务，用于给 Agent 暴露服务端管理工具。 */
    private final DashboardMcpService dashboardMcpService;

    /** Dashboard 技能维护服务，用于给 Agent 暴露维护建议管理工具。 */
    private final DashboardCuratorService dashboardCuratorService;

    /** Dashboard 平台工具集服务，用于给 Agent 暴露渠道工具集管理工具。 */
    private final DashboardPlatformToolsetsService dashboardPlatformToolsetsService;

    /** Dashboard provider 服务，用于给 Agent 暴露模型提供方管理工具。 */
    private final DashboardProviderService dashboardProviderService;

    /** 受管后台进程注册表。 */
    private final ProcessRegistry processRegistry;

    /** 浏览器自动化运行时。 */
    private final BrowserRuntimeService browserRuntimeService;

    /** 图片生成服务。 */
    private final ImageGenerationService imageGenerationService;

    /** 语音服务。 */
    private final SpeechService speechService;

    /** 插件注册工具。 */
    private final List<ToolRegistration> pluginTools;

    /** Dashboard 运行服务，用于给 Agent 暴露一等运行管理工具。 */
    private final DashboardRunService dashboardRunService;

    /** 用量事件仓储，用于给 Agent 暴露与 Dashboard 一致的用量分析。 */
    private final UsageEventRepository usageEventRepository;

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                (SecurityPolicyService) null,
                (DangerousCommandApprovalService) null,
                (ProcessRegistry) null,
                (McpRuntimeService) null,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (BrowserRuntimeService) null,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入 MCP 管理和浏览器运行时依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService Agent profile 服务依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param dashboardMcpService Dashboard MCP服务依赖。
     * @param dashboardCuratorService Dashboard 技能维护服务依赖。
     * @param dashboardPlatformToolsetsService Dashboard 平台工具集服务依赖。
     * @param dashboardProviderService Dashboard provider 服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            DashboardMcpService dashboardMcpService,
            DashboardCuratorService dashboardCuratorService,
            DashboardPlatformToolsetsService dashboardPlatformToolsetsService,
            DashboardProviderService dashboardProviderService,
            BrowserRuntimeService browserRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                dashboardMcpService,
                dashboardCuratorService,
                dashboardPlatformToolsetsService,
                dashboardProviderService,
                browserRuntimeService,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                (ProcessRegistry) null,
                (McpRuntimeService) null,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (BrowserRuntimeService) null,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param mcpRuntimeService MCP运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            McpRuntimeService mcpRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                (ProcessRegistry) null,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (BrowserRuntimeService) null,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (BrowserRuntimeService) null,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (BrowserRuntimeService) null,
                imageGenerationService,
                speechService,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                browserRuntimeService,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param pluginTools 插件Tools参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            List<ToolRegistration> pluginTools) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (BrowserRuntimeService) null,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                pluginTools);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param pluginTools 插件Tools参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            List<ToolRegistration> pluginTools) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                approvalService,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                pluginTools);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param dashboardCuratorService Dashboard 技能维护服务依赖。
     * @param dashboardPlatformToolsetsService Dashboard 平台工具集服务依赖。
     * @param dashboardProviderService Dashboard provider 服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param dashboardRunService Dashboard运行服务依赖。
     * @param usageEventRepository 用量事件仓储依赖。
     * @param pluginTools 插件Tools参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            DashboardMcpService dashboardMcpService,
            DashboardCuratorService dashboardCuratorService,
            DashboardPlatformToolsetsService dashboardPlatformToolsetsService,
            DashboardProviderService dashboardProviderService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            DashboardRunService dashboardRunService,
            UsageEventRepository usageEventRepository,
            List<ToolRegistration> pluginTools) {
        this.appConfig = appConfig;
        this.preferenceStore = preferenceStore;
        this.sessionRepository = sessionRepository;
        this.agentProfileService = agentProfileService;
        this.cronJobService = cronJobService;
        this.deliveryService = deliveryService;
        this.memoryService = memoryService;
        this.sessionSearchService = sessionSearchService;
        this.localSkillService = localSkillService;
        this.skillHubService = skillHubService;
        this.checkpointService = checkpointService;
        this.delegationService = delegationService;
        this.attachmentCacheService = attachmentCacheService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.securityPolicyService = securityPolicyService;
        this.approvalService = approvalService;
        this.mcpRuntimeService = mcpRuntimeService;
        this.dashboardMcpService = dashboardMcpService;
        this.dashboardCuratorService = dashboardCuratorService;
        this.dashboardPlatformToolsetsService = dashboardPlatformToolsetsService;
        this.dashboardProviderService = dashboardProviderService;
        this.processRegistry = processRegistry;
        this.browserRuntimeService =
                browserRuntimeService == null
                        ? new BrowserRuntimeService(
                                appConfig,
                                Collections.<BrowserProvider>emptyList(),
                                securityPolicyService)
                        : browserRuntimeService;
        this.imageGenerationService = imageGenerationService;
        this.speechService = speechService;
        this.dashboardRunService = dashboardRunService;
        this.usageEventRepository = usageEventRepository;
        this.pluginTools =
                pluginTools == null
                        ? Collections.<ToolRegistration>emptyList()
                        : new ArrayList<ToolRegistration>(pluginTools);
        if (this.approvalService != null) {
            this.approvalService.setExternalNetworkPluginTools(pluginToolNames());
        }
    }

    /**
     * 列出工具Names。
     *
     * @return 返回工具Names列表。
     */
    @Override
    public List<String> listToolNames() {
        List<String> result = new ArrayList<String>(TOOL_NAMES);
        Set<String> seen = new LinkedHashSet<String>(TOOL_NAMES);
        for (ToolRegistration registration : pluginTools) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isNotBlank(name) && seen.add(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具对象。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回解析后的启用工具。
     */
    @Override
    public List<Object> resolveEnabledTools(String sourceKey) {
        return resolveEnabledTools(sourceKey, null);
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具对象。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的启用工具。
     */
    @Override
    public List<Object> resolveEnabledTools(String sourceKey, AgentRuntimeScope agentScope) {
        List<Object> tools = new ArrayList<Object>();

        MemoryTools memoryTools = new MemoryTools(memoryService);
        SessionSearchTools sessionSearchTools =
                new SessionSearchTools(sessionSearchService, sourceKey);
        SessionManageTools sessionManageTools =
                new SessionManageTools(
                        new DashboardSessionService(sessionRepository, checkpointService));
        AnalyticsManageTools analyticsManageTools =
                new AnalyticsManageTools(
                        new DashboardAnalyticsService(sessionRepository, usageEventRepository));
        SkillTools skillTools =
                new SkillTools(
                        localSkillService,
                        checkpointService,
                        sessionRepository,
                        sourceKey,
                        agentScope,
                        cronJobService);
        SkillHubTools skillHubTools = new SkillHubTools(skillHubService);
        MessagingTools messagingTools =
                new MessagingTools(deliveryService, sourceKey, attachmentCacheService, appConfig);
        CronjobTools cronjobTools = new CronjobTools(cronJobService, sourceKey);
        TodoTools todoTools = new TodoTools(appConfig, sourceKey);
        AgentTools agentTools = new AgentTools(agentProfileService, sessionRepository, sourceKey);
        RunTools runTools = new RunTools(dashboardRunService);
        McpManageTools mcpManageTools = new McpManageTools(dashboardMcpService);
        CuratorManageTools curatorManageTools = new CuratorManageTools(dashboardCuratorService);
        PlatformToolsetsManageTools platformToolsetsManageTools =
                new PlatformToolsetsManageTools(dashboardPlatformToolsetsService);
        ProviderManageTools providerManageTools = new ProviderManageTools(dashboardProviderService);
        DelegateTools delegateTools = new DelegateTools(delegationService, sourceKey);
        ConfigTools configTools =
                new ConfigTools(runtimeSettingsService, gatewayRuntimeRefreshService, appConfig);
        String sysWorkDir = resolveWorkDir(agentScope);
        SolonClawFileStateTracker fileStateTracker = new SolonClawFileStateTracker();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        sysWorkDir, securityPolicyService, appConfig, fileStateTracker);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(sysWorkDir, securityPolicyService, fileStateTracker);
        ProcessRegistry activeProcessRegistry = resolveProcessRegistry();
        ShellTalent shellSkill =
                new SolonClawShellSkill(
                        sysWorkDir, appConfig, securityPolicyService, activeProcessRegistry);
        ProcessTools processTools =
                new ProcessTools(
                        activeProcessRegistry, sysWorkDir, securityPolicyService, appConfig);
        SolonClawCodeExecutionSkills.SafePythonSkill pythonSkill =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        sysWorkDir, defaultPythonCommand(), securityPolicyService);
        SolonClawCodeExecutionSkills.SafeNodejsSkill nodejsSkill =
                new SolonClawCodeExecutionSkills.SafeNodejsSkill(sysWorkDir, securityPolicyService);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCodeTool =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        sysWorkDir, defaultPythonCommand(), securityPolicyService, appConfig);
        SystemClockTalent systemClockSkill = new SystemClockTalent();
        SolonClawWebTools.SafeWebsearchTool websearchTool =
                new SolonClawWebTools.SafeWebsearchTool(
                        securityPolicyService,
                        new org.noear.solon.ai.talents.web.WebsearchTalent(),
                        appConfig);
        SolonClawWebTools.SafeWebfetchTool webfetchTool =
                new SolonClawWebTools.SafeWebfetchTool(securityPolicyService);
        SolonClawWebTools.SafeCodeSearchTool codeSearchTool =
                new SolonClawWebTools.SafeCodeSearchTool(securityPolicyService);
        BrowserTools browserTools = new BrowserTools(browserRuntimeService);
        SecurityAuditTools securityAuditTools =
                new SecurityAuditTools(
                        securityPolicyService,
                        new DangerousCommandApprovalService(null, appConfig, securityPolicyService),
                        new TirithSecurityService(appConfig),
                        appConfig);
        MediaSpeechTools mediaSpeechTools =
                imageGenerationService == null || speechService == null
                        ? null
                        : new MediaSpeechTools(imageGenerationService, speechService);
        boolean fileSkillAdded = false;
        boolean shellSkillAdded = false;
        boolean clockSkillAdded = false;
        boolean websearchToolAdded = false;
        boolean webfetchToolAdded = false;
        List<Object> gatewayCandidates = new ArrayList<Object>();

        for (String toolName : AgentRuntimePolicy.resolveAllowedTools(agentScope, TOOL_NAMES)) {
            if (!isEnabled(sourceKey, toolName)) {
                continue;
            }

            if (ToolNameConstants.isFileTool(toolName)) {
                if (!fileSkillAdded) {
                    tools.add(fileSkill);
                    fileSkillAdded = true;
                }
            } else if (ToolNameConstants.PATCH.equals(toolName)) {
                tools.add(patchTools);
            } else if (ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                    || ToolNameConstants.TERMINAL.equals(toolName)) {
                if (!shellSkillAdded) {
                    tools.add(shellSkill);
                    shellSkillAdded = true;
                }
            } else if (ToolNameConstants.PROCESS.equals(toolName)) {
                tools.add(processTools);
            } else if (ToolNameConstants.EXECUTE_CODE.equals(toolName)) {
                tools.add(executeCodeTool);
            } else if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
                tools.add(pythonSkill);
            } else if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
                tools.add(nodejsSkill);
            } else if (ToolNameConstants.GET_CURRENT_TIME.equals(toolName)) {
                if (!clockSkillAdded) {
                    tools.add(systemClockSkill);
                    clockSkillAdded = true;
                }
            } else if (ToolNameConstants.CONFIG_GET.equals(toolName)) {
                tools.add(new ConfigTools.ConfigGetTool(configTools));
            } else if (ToolNameConstants.CONFIG_SET.equals(toolName)) {
                tools.add(new ConfigTools.ConfigSetTool(configTools));
            } else if (ToolNameConstants.CONFIG_SET_SECRET.equals(toolName)) {
                tools.add(new ConfigTools.ConfigSetSecretTool(configTools));
            } else if (ToolNameConstants.CONFIG_REFRESH.equals(toolName)) {
                tools.add(new ConfigTools.ConfigRefreshTool(configTools));
            } else if (ToolNameConstants.TOOL_GATEWAY.equals(toolName)) {
                // 在直接工具收集完成后再加入，避免递归包装自身。
            } else if (ToolNameConstants.MCP.equals(toolName)) {
                if (mcpRuntimeService != null) {
                    tools.addAll(mcpRuntimeService.resolveEnabledToolProviders());
                }
            } else if (ToolNameConstants.MCP_MANAGE.equals(toolName)) {
                tools.add(mcpManageTools);
            } else if (ToolNameConstants.CURATOR_MANAGE.equals(toolName)) {
                tools.add(curatorManageTools);
            } else if (ToolNameConstants.PLATFORM_TOOLSETS_MANAGE.equals(toolName)) {
                tools.add(platformToolsetsManageTools);
            } else if (ToolNameConstants.PROVIDER_MANAGE.equals(toolName)) {
                tools.add(providerManageTools);
            } else if (ToolNameConstants.MEMORY.equals(toolName)) {
                tools.add(memoryTools);
            } else if (ToolNameConstants.SESSION_SEARCH.equals(toolName)) {
                tools.add(sessionSearchTools);
            } else if (ToolNameConstants.SESSION_MANAGE.equals(toolName)) {
                tools.add(sessionManageTools);
            } else if (ToolNameConstants.ANALYTICS_MANAGE.equals(toolName)) {
                tools.add(analyticsManageTools);
            } else if (ToolNameConstants.SKILLS_LIST.equals(toolName)) {
                tools.add(new SkillTools.SkillsListTool(skillTools));
            } else if (ToolNameConstants.SKILL_VIEW.equals(toolName)) {
                tools.add(new SkillTools.SkillViewTool(skillTools));
            } else if (ToolNameConstants.SKILL_MANAGE.equals(toolName)) {
                tools.add(new SkillTools.SkillManageTool(skillTools));
            } else if (ToolNameConstants.SKILLS_HUB_SEARCH.equals(toolName)) {
                tools.add(new SkillHubTools.SearchTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_INSPECT.equals(toolName)) {
                tools.add(new SkillHubTools.InspectTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_INSTALL.equals(toolName)) {
                tools.add(new SkillHubTools.InstallTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_LIST.equals(toolName)) {
                tools.add(new SkillHubTools.ListTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_CHECK.equals(toolName)) {
                tools.add(new SkillHubTools.CheckTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_UPDATE.equals(toolName)) {
                tools.add(new SkillHubTools.UpdateTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_AUDIT.equals(toolName)) {
                tools.add(new SkillHubTools.AuditTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_UNINSTALL.equals(toolName)) {
                tools.add(new SkillHubTools.UninstallTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_TAP.equals(toolName)) {
                tools.add(new SkillHubTools.TapTool(skillHubTools));
            } else if (ToolNameConstants.SEND_MESSAGE.equals(toolName)) {
                tools.add(messagingTools);
            } else if (ToolNameConstants.CRONJOB.equals(toolName)) {
                tools.add(cronjobTools);
            } else if (ToolNameConstants.TODO.equals(toolName)) {
                tools.add(todoTools);
            } else if (ToolNameConstants.AGENT_MANAGE.equals(toolName)) {
                tools.add(agentTools);
            } else if (ToolNameConstants.RUN_MANAGE.equals(toolName)) {
                tools.add(runTools);
            } else if (ToolNameConstants.DELEGATE_TASK.equals(toolName)) {
                tools.add(delegateTools);
            } else if (ToolNameConstants.WEBSEARCH.equals(toolName)) {
                if (!websearchToolAdded) {
                    tools.add(websearchTool);
                    websearchToolAdded = true;
                }
            } else if (ToolNameConstants.WEBFETCH.equals(toolName)) {
                if (!webfetchToolAdded) {
                    tools.add(webfetchTool);
                    webfetchToolAdded = true;
                }
            } else if (ToolNameConstants.CODESEARCH.equals(toolName)) {
                tools.add(codeSearchTool);
            } else if (ToolNameConstants.IMAGE_GENERATE.equals(toolName)
                    || ToolNameConstants.TEXT_TO_SPEECH.equals(toolName)
                    || ToolNameConstants.SPEECH_TRANSCRIBE.equals(toolName)) {
                if (mediaSpeechTools != null && !tools.contains(mediaSpeechTools)) {
                    tools.add(mediaSpeechTools);
                }
            } else if (ToolNameConstants.BROWSER.equals(toolName)) {
                tools.add(browserTools);
            } else if (ToolNameConstants.SECURITY_AUDIT.equals(toolName)) {
                tools.add(securityAuditTools);
            } else if (ToolNameConstants.CLARIFY.equals(toolName)) {
                tools.add(new ClarifyTools());
            }
        }
        for (FunctionTool pluginTool : resolvePluginTools(sourceKey, agentScope)) {
            tools.add(pluginTool);
        }
        if (isGatewayEnabled(sourceKey, agentScope)) {
            gatewayCandidates.addAll(tools);
            ToolGatewayTalent gatewaySkill = buildToolGateway(gatewayCandidates);
            if (gatewaySkill != null) {
                tools.add(gatewaySkill);
            }
        }
        return tools;
    }

    /**
     * 判断是否消息网关启用。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 如果消息网关启用满足条件则返回 true，否则返回 false。
     */
    private boolean isGatewayEnabled(String sourceKey, AgentRuntimeScope agentScope) {
        if (!AgentRuntimePolicy.isToolAllowed(agentScope, ToolNameConstants.TOOL_GATEWAY)) {
            return false;
        }
        try {
            return preferenceStore.isToolEnabled(sourceKey, ToolNameConstants.TOOL_GATEWAY, false);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 构建工具消息网关。
     *
     * @param candidates candidates标识或键值。
     * @return 返回创建好的工具消息网关。
     */
    private ToolGatewayTalent buildToolGateway(List<Object> candidates) {
        ToolGatewayTalent gateway =
                new ToolGatewayTalent().dynamicThreshold(0).listThreshold(40).searchThreshold(100);
        boolean added = false;
        for (Object candidate : candidates) {
            if (candidate == null || candidate instanceof ToolGatewayTalent) {
                continue;
            }
            if (candidate instanceof ToolProvider) {
                gateway.addTool((ToolProvider) candidate);
                added = true;
            } else if (candidate instanceof Talent) {
                for (FunctionTool tool : ((Talent) candidate).getTools(Prompt.of(""))) {
                    gateway.addTool(tool);
                    added = true;
                }
            } else if (candidate instanceof FunctionTool) {
                gateway.addTool((FunctionTool) candidate);
                added = true;
            }
        }
        return added ? gateway : null;
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具名称。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回解析后的启用工具Names。
     */
    @Override
    public List<String> resolveEnabledToolNames(String sourceKey) {
        return resolveEnabledToolNames(sourceKey, null);
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具名称。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的启用工具Names。
     */
    @Override
    public List<String> resolveEnabledToolNames(String sourceKey, AgentRuntimeScope agentScope) {
        List<String> result = new ArrayList<String>();
        for (String toolName : AgentRuntimePolicy.resolveAllowedTools(agentScope, TOOL_NAMES)) {
            if (isEnabled(sourceKey, toolName)) {
                result.add(toolName);
            }
        }
        for (String toolName : pluginToolNames()) {
            if (isPluginToolAllowed(agentScope, sourceKey, toolName)
                    && isEnabled(sourceKey, toolName)) {
                result.add(toolName);
            }
        }
        return result;
    }

    /**
     * 启用工具。
     *
     * @param sourceKey 渠道来源键。
     * @param toolNames 工具Names参数。
     */
    @Override
    public void enableTools(String sourceKey, List<String> toolNames) {
        for (String toolName : toolNames) {
            if (TOOL_NAMES.contains(toolName)) {
                setToolEnabled(sourceKey, toolName, true);
            } else if (pluginToolNames().contains(toolName)) {
                setToolEnabled(sourceKey, toolName, true);
            }
        }
    }

    /**
     * 禁用工具。
     *
     * @param sourceKey 渠道来源键。
     * @param toolNames 工具Names参数。
     */
    @Override
    public void disableTools(String sourceKey, List<String> toolNames) {
        for (String toolName : toolNames) {
            if (TOOL_NAMES.contains(toolName)) {
                setToolEnabled(sourceKey, toolName, false);
            } else if (pluginToolNames().contains(toolName)) {
                setToolEnabled(sourceKey, toolName, false);
            }
        }
    }

    /** 读取工具启用状态。 */
    private boolean isEnabled(String sourceKey, String toolName) {
        try {
            if (ToolNameConstants.isFileTool(toolName) && areCoreFileToolsDisabled(sourceKey)) {
                return false;
            }
            if (ToolNameConstants.TOOL_GATEWAY.equals(toolName)) {
                return preferenceStore.isToolEnabled(sourceKey, toolName, false);
            }
            return preferenceStore.isToolEnabled(sourceKey, toolName);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 判断文件工具组是否已整体禁用。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果文件工具组被整体禁用则返回 true。
     */
    private boolean areCoreFileToolsDisabled(String sourceKey) throws SQLException {
        for (String fileToolName : coreFileToolNames()) {
            if (!preferenceStore.hasScopedToolToggle(sourceKey, fileToolName)
                    || preferenceStore.isToolEnabled(sourceKey, fileToolName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 文件工具名集合。
     *
     * @return 返回文件工具名集合。
     */
    private List<String> coreFileToolNames() {
        return Arrays.asList(
                ToolNameConstants.FILE_READ,
                ToolNameConstants.FILE_WRITE,
                ToolNameConstants.READ_FILE,
                ToolNameConstants.WRITE_FILE,
                ToolNameConstants.SEARCH_FILES,
                ToolNameConstants.FILE_LIST,
                ToolNameConstants.FILE_DELETE);
    }

    /** 设置工具启用状态。 */
    private void setToolEnabled(String sourceKey, String toolName, boolean enabled) {
        try {
            preferenceStore.setToolEnabled(sourceKey, toolName, enabled);
        } catch (SQLException e) {
            log.warn("工具启用偏好写入失败 source={} tool={} enabled={} error={}",
                    SecretRedactor.redact(sourceKey, 120),
                    SecretRedactor.redact(toolName, 120),
                    Boolean.valueOf(enabled),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 执行默认Python命令相关逻辑。
     *
     * @return 返回默认Python命令结果。
     */
    private String defaultPythonCommand() {
        return isWindows() ? "python" : "python3";
    }

    /**
     * 解析Work Dir。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的Work Dir。
     */
    private String resolveWorkDir(AgentRuntimeScope agentScope) {
        if (agentScope != null && StrUtil.isNotBlank(agentScope.getWorkspaceDir())) {
            return agentScope.getWorkspaceDir();
        }
        return appConfig.getWorkspace().getDir();
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 解析进程注册表。
     *
     * @return 返回解析后的进程注册表。
     */
    private ProcessRegistry resolveProcessRegistry() {
        return processRegistry == null ? new ProcessRegistry(appConfig) : processRegistry;
    }

    /**
     * 解析插件工具。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的插件工具。
     */
    private List<FunctionTool> resolvePluginTools(String sourceKey, AgentRuntimeScope agentScope) {
        List<FunctionTool> result = new ArrayList<FunctionTool>();
        Set<String> builtinNames = new LinkedHashSet<String>(TOOL_NAMES);
        Set<String> seen = new LinkedHashSet<String>();
        for (final ToolRegistration registration : pluginTools) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isBlank(name)
                    || builtinNames.contains(name)
                    || !seen.add(name)
                    || !isPluginToolAllowed(agentScope, sourceKey, name)
                    || !isEnabled(sourceKey, name)) {
                continue;
            }
            FunctionToolDesc tool = new FunctionToolDesc(name);
            tool.description(StrUtil.blankToDefault(registration.getDescription(), "Plugin tool"));
            if (registration.getSchema() != null && !registration.getSchema().isEmpty()) {
                tool.inputSchema(org.noear.snack4.ONode.serialize(registration.getSchema()));
            }
            tool.doHandle(
                    args -> {
                        return registration.getHandler() == null
                                ? ""
                                : registration.getHandler().apply(args);
                    });
            result.add(tool);
        }
        return result;
    }

    /**
     * 执行插件工具Names相关逻辑。
     *
     * @return 返回插件工具Names结果。
     */
    private List<String> pluginToolNames() {
        List<String> result = new ArrayList<String>();
        Set<String> builtinNames = new LinkedHashSet<String>(TOOL_NAMES);
        Set<String> seen = new LinkedHashSet<String>();
        for (ToolRegistration registration : pluginTools) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isNotBlank(name) && !builtinNames.contains(name) && seen.add(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 判断是否插件工具Allowed。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果插件工具Allowed满足条件则返回 true，否则返回 false。
     */
    private boolean isPluginToolAllowed(
            AgentRuntimeScope agentScope, String sourceKey, String toolName) {
        if (isDelegateSourceKey(sourceKey) && !hasExplicitScopedToolToggle(sourceKey, toolName)) {
            return false;
        }
        return AgentRuntimePolicy.resolveAllowedTools(agentScope, listToolNames())
                .contains(toolName);
    }

    /**
     * 判断是否委托来源键。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果委托来源键满足条件则返回 true，否则返回 false。
     */
    private boolean isDelegateSourceKey(String sourceKey) {
        return StrUtil.nullToEmpty(sourceKey).contains(":delegate:");
    }

    /**
     * 判断是否存在Explicit Scoped工具Toggle。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果Explicit Scoped工具Toggle满足条件则返回 true，否则返回 false。
     */
    private boolean hasExplicitScopedToolToggle(String sourceKey, String toolName) {
        try {
            return preferenceStore.hasScopedToolToggle(sourceKey, toolName);
        } catch (SQLException e) {
            return false;
        }
    }
}
