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
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.ToolRegistration;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.skills.sys.ShellSkill;
import org.noear.solon.ai.skills.sys.SystemClockSkill;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;

/** 默认工具注册表。 */
public class DefaultToolRegistry implements ToolRegistry {
    /** 默认内置工具清单。 */
    private static final List<String> TOOL_NAMES =
            Arrays.asList(
                    ToolNameConstants.FILE_READ,
                    ToolNameConstants.FILE_WRITE,
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
                    ToolNameConstants.DELEGATE_TASK,
                    ToolNameConstants.MEMORY,
                    ToolNameConstants.SESSION_SEARCH,
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

    /** 运行时配置服务。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 运行时配置刷新服务。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 文件/URL 安全策略。 */
    private final SecurityPolicyService securityPolicyService;

    /** MCP 运行时工具发现服务。 */
    private final McpRuntimeService mcpRuntimeService;

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
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

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
                null,
                null,
                null,
                null,
                null,
                null);
    }

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
                null,
                mcpRuntimeService,
                null,
                null,
                null,
                null);
    }

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
                processRegistry,
                mcpRuntimeService,
                null,
                null,
                null,
                null);
    }

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
                processRegistry,
                mcpRuntimeService,
                null,
                imageGenerationService,
                speechService,
                null);
    }

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
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                null,
                null,
                null);
    }

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
                processRegistry,
                mcpRuntimeService,
                null,
                null,
                null,
                pluginTools);
    }

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
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                null);
    }

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
            SpeechService speechService,
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
        this.mcpRuntimeService = mcpRuntimeService;
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
        this.pluginTools =
                pluginTools == null
                        ? Collections.<ToolRegistration>emptyList()
                        : new ArrayList<ToolRegistration>(pluginTools);
    }

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

    @Override
    public List<Object> resolveEnabledTools(String sourceKey) {
        return resolveEnabledTools(sourceKey, null);
    }

    @Override
    public List<Object> resolveEnabledTools(String sourceKey, AgentRuntimeScope agentScope) {
        List<Object> tools = new ArrayList<Object>();

        MemoryTools memoryTools = new MemoryTools(memoryService);
        SessionSearchTools sessionSearchTools =
                new SessionSearchTools(sessionSearchService, sourceKey);
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
        DelegateTools delegateTools = new DelegateTools(delegationService, sourceKey);
        ConfigTools configTools =
                new ConfigTools(runtimeSettingsService, gatewayRuntimeRefreshService, appConfig);
        String sysWorkDir = resolveWorkDir(agentScope);
        SolonClawFileStateTracker fileStateTracker = new SolonClawFileStateTracker();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        sysWorkDir,
                        securityPolicyService,
                        appConfig,
                        fileStateTracker);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(sysWorkDir, securityPolicyService, fileStateTracker);
        ProcessRegistry activeProcessRegistry = resolveProcessRegistry();
        ShellSkill shellSkill =
                new SolonClawShellSkill(
                        sysWorkDir, appConfig, securityPolicyService, activeProcessRegistry);
        ProcessTools processTools =
                new ProcessTools(activeProcessRegistry, sysWorkDir, securityPolicyService, appConfig);
        SolonClawCodeExecutionSkills.SafePythonSkill pythonSkill =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        sysWorkDir, defaultPythonCommand(), securityPolicyService);
        SolonClawCodeExecutionSkills.SafeNodejsSkill nodejsSkill =
                new SolonClawCodeExecutionSkills.SafeNodejsSkill(sysWorkDir, securityPolicyService);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCodeTool =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        sysWorkDir, defaultPythonCommand(), securityPolicyService, appConfig);
        SystemClockSkill systemClockSkill = new SystemClockSkill();
        SolonClawWebTools.SafeWebsearchTool websearchTool =
                new SolonClawWebTools.SafeWebsearchTool(
                        securityPolicyService,
                        org.noear.solon.ai.skills.web.WebsearchTool.getInstance(),
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
        List<Object> gatewayCandidates = new ArrayList<Object>();

        for (String toolName : AgentRuntimePolicy.resolveAllowedTools(agentScope, TOOL_NAMES)) {
            if (!isEnabled(sourceKey, toolName)) {
                continue;
            }

            if (isFileTool(toolName)) {
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
                // Added after direct tools are collected to avoid recursively wrapping itself.
            } else if (ToolNameConstants.MCP.equals(toolName)) {
                if (mcpRuntimeService != null) {
                    tools.addAll(mcpRuntimeService.resolveEnabledToolProviders());
                }
            } else if (ToolNameConstants.MEMORY.equals(toolName)) {
                tools.add(memoryTools);
            } else if (ToolNameConstants.SESSION_SEARCH.equals(toolName)) {
                tools.add(sessionSearchTools);
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
            } else if (ToolNameConstants.DELEGATE_TASK.equals(toolName)) {
                tools.add(delegateTools);
            } else if (ToolNameConstants.WEBSEARCH.equals(toolName)) {
                tools.add(websearchTool);
            } else if (ToolNameConstants.WEBFETCH.equals(toolName)) {
                tools.add(webfetchTool);
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
            ToolGatewaySkill gatewaySkill = buildToolGateway(gatewayCandidates);
            if (gatewaySkill != null) {
                tools.add(gatewaySkill);
            }
        }
        return tools;
    }

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

    private ToolGatewaySkill buildToolGateway(List<Object> candidates) {
        ToolGatewaySkill gateway =
                new ToolGatewaySkill()
                        .dynamicThreshold(0)
                        .listThreshold(40)
                        .searchThreshold(100);
        boolean added = false;
        for (Object candidate : candidates) {
            if (candidate == null || candidate instanceof ToolGatewaySkill) {
                continue;
            }
            if (candidate instanceof ToolProvider) {
                gateway.addTool((ToolProvider) candidate);
                added = true;
            } else if (candidate instanceof Skill) {
                for (FunctionTool tool : ((Skill) candidate).getTools(Prompt.of(""))) {
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

    private boolean isFileTool(String toolName) {
        return ToolNameConstants.FILE_READ.equals(toolName)
                || ToolNameConstants.FILE_WRITE.equals(toolName)
                || ToolNameConstants.FILE_LIST.equals(toolName)
                || ToolNameConstants.FILE_DELETE.equals(toolName);
    }

    @Override
    public List<String> resolveEnabledToolNames(String sourceKey) {
        return resolveEnabledToolNames(sourceKey, null);
    }

    @Override
    public List<String> resolveEnabledToolNames(String sourceKey, AgentRuntimeScope agentScope) {
        List<String> result = new ArrayList<String>();
        for (String toolName : AgentRuntimePolicy.resolveAllowedTools(agentScope, TOOL_NAMES)) {
            if (isEnabled(sourceKey, toolName)) {
                result.add(toolName);
            }
        }
        for (String toolName : pluginToolNames()) {
            if (isPluginToolAllowed(agentScope, sourceKey, toolName) && isEnabled(sourceKey, toolName)) {
                result.add(toolName);
            }
        }
        return result;
    }

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
            if (ToolNameConstants.TOOL_GATEWAY.equals(toolName)) {
                return preferenceStore.isToolEnabled(sourceKey, toolName, false);
            }
            return preferenceStore.isToolEnabled(sourceKey, toolName);
        } catch (SQLException e) {
            return false;
        }
    }

    /** 设置工具启用状态。 */
    private void setToolEnabled(String sourceKey, String toolName, boolean enabled) {
        try {
            preferenceStore.setToolEnabled(sourceKey, toolName, enabled);
        } catch (SQLException ignored) {
            // V1 忽略偏好写入失败。
        }
    }

    private String defaultPythonCommand() {
        return isWindows() ? "python" : "python3";
    }

    private String resolveWorkDir(AgentRuntimeScope agentScope) {
        if (agentScope != null && StrUtil.isNotBlank(agentScope.getWorkspaceDir())) {
            return agentScope.getWorkspaceDir();
        }
        return appConfig.getRuntime().getHome();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private ProcessRegistry resolveProcessRegistry() {
        return processRegistry == null ? new ProcessRegistry(appConfig) : processRegistry;
    }

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

    private boolean isPluginToolAllowed(AgentRuntimeScope agentScope, String sourceKey, String toolName) {
        if (isDelegateSourceKey(sourceKey) && !hasExplicitScopedToolToggle(sourceKey, toolName)) {
            return false;
        }
        return AgentRuntimePolicy.resolveAllowedTools(agentScope, listToolNames()).contains(toolName);
    }

    private boolean isDelegateSourceKey(String sourceKey) {
        return StrUtil.nullToEmpty(sourceKey).contains(":delegate:");
    }

    private boolean hasExplicitScopedToolToggle(String sourceKey, String toolName) {
        try {
            return preferenceStore.hasScopedToolToggle(sourceKey, toolName);
        } catch (SQLException e) {
            return false;
        }
    }
}
